package com.douai.module.feature;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A loopback HTTP proxy that re-serves a remote media URL.
 *
 * <p>Douyin throttles or downgrades the CDN response when the same asset is requested
 * repeatedly. Serving the asset from a local server keeps the upstream connection count
 * low and lets the module answer range requests from a partially filled cache file.
 *
 * <p>The proxy binds to {@code 127.0.0.1} only and is reachable from the hosting process
 * through {@code http://127.0.0.1:<port>/<token>/<encoded upstream url>}.
 */
final class LocalVideoProxy {
    private static final String TAG = "DouAiLocalProxy";
    /** Upper bound for a single request line or header block. */
    private static final int MAX_HEADER_BYTES = 8192;
    /** Socket read timeout in milliseconds. */
    private static final int SOCKET_TIMEOUT_MS = 30_000;

    private final Map<String, String> upstreamByPath = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lock = new Object();

    private ExecutorService workers;
    private Semaphore upstreamSlots;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private String token;
    private int port;
    private File cacheDirectory;
    private VideoProxyCachePolicy cachePolicy = new VideoProxyCachePolicy(false, 0, false);
    private volatile boolean unmeteredNetwork = true;

    /** Returns {@code true} while the proxy server is listening. */
    boolean running() {
        return running.get();
    }

    int port() {
        return port;
    }

    /**
     * Applies the cache configuration.
     *
     * @param directory the directory that holds the cached media files
     * @param policy the cache policy in effect
     */
    void configureCache(File directory, VideoProxyCachePolicy policy) {
        synchronized (lock) {
            cacheDirectory = directory;
            cachePolicy = policy == null ? new VideoProxyCachePolicy(false, 0, false) : policy;
        }
    }

    /** Tells the proxy whether the current network is unmetered. */
    void setUnmeteredNetwork(boolean unmetered) {
        this.unmeteredNetwork = unmetered;
    }

    /**
     * Starts the proxy on an ephemeral loopback port.
     *
     * @param maxConnections the maximum number of simultaneous upstream connections
     * @return {@code true} when the server is listening
     */
    boolean start(int maxConnections) {
        if (running.get()) return true;
        synchronized (lock) {
            if (running.get()) return true;
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
                port = serverSocket.getLocalPort();
                token = Long.toHexString(System.nanoTime()) + Integer.toHexString(port);
                workers = Executors.newCachedThreadPool();
                upstreamSlots = new Semaphore(Math.max(1, maxConnections));
                running.set(true);
                acceptThread = new Thread(this::acceptLoop, "douai-local-video-proxy");
                acceptThread.setDaemon(true);
                acceptThread.start();
                Log.i(TAG, "local video proxy listening on 127.0.0.1:" + port);
                return true;
            } catch (IOException failure) {
                Log.w(TAG, "unable to start the local video proxy", failure);
                running.set(false);
                return false;
            }
        }
    }

    void stop() {
        ExecutorService pool;
        synchronized (lock) {
            running.set(false);
            ServerSocket socket = serverSocket;
            serverSocket = null;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Closing a socket that already failed is not actionable.
                }
            }
            pool = workers;
            workers = null;
        }
        if (pool != null) pool.shutdownNow();
    }

    /**
     * Registers an upstream URL and returns the loopback URL that serves it.
     *
     * @param upstream remote media URL
     * @return the loopback URL, or {@code null} when the proxy is not running
     */
    String register(String upstream) {
        if (!running.get() || upstream == null || upstream.isEmpty()) return null;
        synchronized (lock) {
            if (token == null) return null;
            String path = "/" + token + "/" + encode(upstream);
            upstreamByPath.put(path, upstream);
            return "http://127.0.0.1:" + port + path;
        }
    }

    void unregister(String loopbackUrl) {
        if (loopbackUrl == null) return;
        synchronized (lock) {
            upstreamByPath.remove(pathOf(loopbackUrl));
        }
    }

    private String pathOf(String loopbackUrl) {
        int scheme = loopbackUrl.indexOf("://");
        if (scheme < 0) return loopbackUrl;
        int path = loopbackUrl.indexOf('/', scheme + 3);
        return path < 0 ? loopbackUrl : loopbackUrl.substring(path);
    }

    private void acceptLoop() {
        while (running.get()) {
            ServerSocket socket = serverSocket;
            if (socket == null) return;
            ExecutorService pool = workers;
            if (pool == null) return;
            try {
                Socket client = socket.accept();
                pool.execute(() -> handle(client));
            } catch (IOException failure) {
                if (running.get()) Log.w(TAG, "accept failed", failure);
                return;
            } catch (RuntimeException failure) {
                Log.w(TAG, "accept failed", failure);
            }
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeSimple(out, 400, "Bad Request");
                return;
            }
            Map<String, String> headers = readHeaders(in);
            String path = parts[1];
            String upstream;
            synchronized (lock) {
                upstream = upstreamByPath.get(path);
            }
            if (upstream == null) {
                writeSimple(out, 404, "Not Found");
                return;
            }
            serve(out, upstream, headers);
        } catch (IOException failure) {
            Log.w(TAG, "request failed", failure);
        } catch (RuntimeException failure) {
            Log.w(TAG, "request failed", failure);
        }
    }

    private void serve(OutputStream out, String upstream, Map<String, String> headers)
            throws IOException {
        String range = headers.get("range");
        File cacheFile = cacheFileFor(upstream);
        boolean cacheable = cacheFile != null && cachePolicy.allowsCaching(unmeteredNetwork);
        if (cacheable && serveFromCache(out, cacheFile, range)) return;

        Semaphore slots = upstreamSlots;
        if (slots == null) {
            writeSimple(out, 503, "Service Unavailable");
            return;
        }
        boolean acquired = false;
        try {
            slots.acquire();
            acquired = true;
            fetchAndServe(out, upstream, range, cacheable ? cacheFile : null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writeSimple(out, 503, "Service Unavailable");
        } finally {
            if (acquired) slots.release();
        }
    }

    /**
     * Tries to answer the request from the cache file.
     *
     * @return {@code true} when the response was fully served from the cache
     */
    private boolean serveFromCache(OutputStream out, File cacheFile, String range)
            throws IOException {
        if (!cacheFile.isFile() || cacheFile.length() <= 0L) return false;
        long length = cacheFile.length();
        VideoRangeRequest request = VideoRangeRequest.parse(range, length);
        if (request != null && request.isUnsatisfiable()) {
            writeSimple(out, 416, "Range Not Satisfiable");
            return true;
        }
        long start = request == null ? 0L : request.start();
        long count = request == null ? length : request.length();
        try (RandomAccessFile file = new RandomAccessFile(cacheFile, "r")) {
            file.seek(start);
            writeHeaders(out, request == null ? 200 : 206, count,
                    request == null ? null : request.contentRange(length));
            copy(file, out, count);
            out.flush();
        }
        return true;
    }

    private void fetchAndServe(OutputStream out, String upstream, String range, File cacheFile)
            throws IOException {
        HttpURLConnection connection = null;
        RandomAccessFile cache = null;
        try {
            connection = (HttpURLConnection) new URL(upstream).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(SOCKET_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            if (range != null) connection.setRequestProperty("Range", range);
            connection.connect();
            int status = connection.getResponseCode();
            long contentLength = connection.getContentLengthLong();
            VideoRangeRequest request = VideoRangeRequest.parse(range, contentLength);
            if (request != null && request.isUnsatisfiable()) {
                writeSimple(out, 416, "Range Not Satisfiable");
                return;
            }
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                writeSimple(out, status, connection.getResponseMessage());
                return;
            }
            long offset = 0L;
            if (cacheFile != null) {
                offset = request == null ? 0L : request.start();
                cache = openCache(cacheFile, offset);
            }
            if (request == null) {
                writeHeaders(out, 200, contentLength, null);
                copy(connection.getInputStream(), out, -1L, cache, offset);
                out.flush();
                return;
            }
            writeHeaders(out, 206, request.length(), request.contentRange(contentLength));
            copy(connection.getInputStream(), out, request.length(), cache, offset);
            out.flush();
        } finally {
            closeQuietly(cache);
            if (connection != null) connection.disconnect();
        }
    }

    private RandomAccessFile openCache(File cacheFile, long offset) throws IOException {
        File parent = cacheFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            return null;
        }
        RandomAccessFile file = new RandomAccessFile(cacheFile, "rw");
        if (offset > 0L) file.seek(offset);
        return file;
    }

    private void closeQuietly(RandomAccessFile file) {
        if (file == null) return;
        try {
            file.close();
        } catch (IOException ignored) {
            // A failed close does not invalidate the response that was already written.
        }
    }

    private File cacheFileFor(String upstream) {
        VideoProxyCachePolicy policy = cachePolicy;
        if (!policy.isEnabled() || !VideoProxyCachePolicy.isCacheableMedia(upstream)) return null;
        File directory = cacheDirectory;
        if (directory == null) return null;
        String key = VideoCacheKey.keyFor(upstream);
        if (key == null) return null;
        String name = VideoCacheKey.fileNameFor(key);
        return name == null ? null : new File(directory, name);
    }

    private void copy(InputStream in, OutputStream out, long limit) throws IOException {
        copy(in, out, limit, null, 0L);
    }

    private void copy(InputStream in, OutputStream out, long limit, RandomAccessFile cache,
            long cacheOffset) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long written = 0L;
        int read;
        while ((read = in.read(buffer)) > 0) {
            int allowed = read;
            if (limit >= 0L && written + allowed > limit) allowed = (int) (limit - written);
            if (allowed > 0) {
                out.write(buffer, 0, allowed);
                if (cache != null) cache.write(buffer, 0, allowed);
                written += allowed;
            }
            if (limit >= 0L && written >= limit) break;
            if (limit < 0L && written % (4L * 1024 * 1024) == 0L) out.flush();
        }
    }

    private void copy(RandomAccessFile in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long written = 0L;
        int read;
        while (written < limit && (read = in.read(buffer)) > 0) {
            int allowed = (int) Math.min((long) read, limit - written);
            out.write(buffer, 0, allowed);
            written += allowed;
        }
    }

    private void writeHeaders(OutputStream out, int status, long contentLength,
            String contentRange) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("HTTP/1.1 ").append(status).append(' ')
                .append(status == 206 ? "Partial Content" : "OK").append("\r\n");
        builder.append("Accept-Ranges: ").append(VideoRangeRequest.acceptRanges()).append("\r\n");
        if (contentLength >= 0L) {
            builder.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        if (contentRange != null) {
            builder.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        builder.append("Connection: close\r\n\r\n");
        out.write(builder.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    private void writeSimple(OutputStream out, int status, String message) throws IOException {
        byte[] body = (message == null ? "" : message).getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder();
        builder.append("HTTP/1.1 ").append(status).append(' ').append(message == null ? "" : message)
                .append("\r\n");
        builder.append("Content-Length: ").append(body.length).append("\r\n");
        builder.append("Connection: close\r\n\r\n");
        out.write(builder.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        int value;
        while ((value = in.read()) >= 0) {
            if (value == '\n') {
                String line = builder.toString();
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                return line;
            }
            builder.append((char) value);
            if (builder.length() > MAX_HEADER_BYTES) return null;
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.put(VideoRangeRequest.normaliseHeaderName(line.substring(0, colon)),
                    line.substring(colon + 1).trim());
        }
        return headers;
    }

    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException failure) {
            throw new IllegalStateException("UTF-8 is always available", failure);
        }
    }
}
