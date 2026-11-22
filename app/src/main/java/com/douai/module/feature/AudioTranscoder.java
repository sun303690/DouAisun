package com.douai.module.feature;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Ported from douyim's {@code AudioTranscoder}: decode the video's audio track and re-encode it as
 * real MP3 through {@link Mp3Encoder}. The original called {@code VideoDownloader.ensureEnabled()}
 * to abort when the user flipped the master switch mid-job; here the same check is injected so the
 * class stays independent of the download plumbing.
 */
final class AudioTranscoder {

    /** Thrown when the caller wants the job abandoned (switch off, activity gone, …). */
    interface Guard {
        void check() throws IOException;
    }

    private AudioTranscoder() {}

    /**
     * MIME of the first audio track in {@code input}, or {@code null} when the file carries none.
     * Lets the caller keep an already-MP3 stream untouched instead of re-encoding it.
     */
    static String probeAudioMime(File input) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(input.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) return mime;
            }
            return null;
        } finally {
            extractor.release();
        }
    }

    static void toMp3(File input, File output, Guard guard) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        Mp3Encoder encoder = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    format = candidate;
                    extractor.selectTrack(i);
                    break;
                }
            }
            if (format == null) throw new IOException("当前视频没有可提取的音轨");
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            codec.configure(format, null, null, 0);
            codec.start();
            boolean inputEnded = false;
            boolean outputEnded = false;
            long lastProgress = SystemClock.elapsedRealtime();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try (FileOutputStream stream = new FileOutputStream(output)) {
                while (!outputEnded) {
                    if (guard != null) guard.check();
                    if (!inputEnded) {
                        int index = codec.dequeueInputBuffer(10_000);
                        if (index >= 0) {
                            ByteBuffer buffer = codec.getInputBuffer(index);
                            if (buffer == null) throw new IOException("audio input buffer unavailable");
                            buffer.clear();
                            int size = extractor.readSampleData(buffer, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEnded = true;
                            } else {
                                codec.queueInputBuffer(index, 0, size,
                                        extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                            lastProgress = SystemClock.elapsedRealtime();
                        }
                    }
                    int index = codec.dequeueOutputBuffer(info, 10_000);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        format = codec.getOutputFormat();
                    }
                    if (index >= 0) {
                        try {
                            if (info.size > 0
                                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                                int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                                int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                        ? format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                        : AudioFormat.ENCODING_PCM_16BIT;
                                if (encoding != AudioFormat.ENCODING_PCM_16BIT
                                        && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                                    throw new IOException("unsupported PCM encoding " + encoding);
                                }
                                boolean floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT;
                                if (encoder == null) encoder = new Mp3Encoder(rate, channels);
                                if (!encoder.matches(rate, channels)) {
                                    throw new IOException("audio format changed midstream");
                                }
                                ByteBuffer buffer = codec.getOutputBuffer(index);
                                if (buffer == null) throw new IOException("audio output buffer unavailable");
                                buffer.position(info.offset);
                                buffer.limit(info.offset + info.size);
                                encoder.write(buffer, floatPcm, stream);
                            }
                            outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                            lastProgress = SystemClock.elapsedRealtime();
                        } finally {
                            codec.releaseOutputBuffer(index, false);
                        }
                    }
                    if (SystemClock.elapsedRealtime() - lastProgress > 30_000L) {
                        throw new IOException("audio decoder timed out");
                    }
                }
                if (encoder == null) throw new IOException("当前视频音轨为空");
                encoder.finish(stream);
            }
        } finally {
            if (encoder != null) encoder.close();
            if (codec != null) codec.release();
            extractor.release();
        }
    }
}
