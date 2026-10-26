package com.douai.module.feature;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public final class SpeechCacheDirectoryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void concurrentFirstRequestsAcceptTheDirectoryCreatedByEitherWorker() throws Exception {
        File cache = new File(temporary.getRoot(), "douai-tts");
        CyclicBarrier firstChecks = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> first = creation(cache, firstChecks);
            Callable<Void> second = creation(cache, firstChecks);
            List<Future<Void>> results = workers.invokeAll(List.of(first, second), 10, TimeUnit.SECONDS);
            assertTrue("One worker must create the shared directory", cache.isDirectory());
            for (Future<Void> result : results) result.get(5, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void regularFileAtTheCachePathIsStillRejected() throws Exception {
        File cache = temporary.newFile("douai-tts");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SpeechSynthesis.ensureCacheDirectory(cache));
        assertEquals("无法创建语音缓存", failure.getMessage());
        assertTrue(cache.isFile());
    }

    private static Callable<Void> creation(File cache, CyclicBarrier firstChecks) {
        File observed = new File(cache.getPath()) {
            private final AtomicBoolean firstCheck = new AtomicBoolean(true);

            @Override public boolean isDirectory() {
                boolean directory = super.isDirectory();
                if (firstCheck.compareAndSet(true, false)) {
                    assertFalse("Both workers must first observe the missing directory", directory);
                    try { firstChecks.await(5, TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new AssertionError("Concurrent directory check did not complete", failure); }
                }
                return directory;
            }
        };
        return () -> { SpeechSynthesis.ensureCacheDirectory(observed); return null; };
    }
}
