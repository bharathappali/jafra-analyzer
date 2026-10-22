package io.jafra.analyzer.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableFilesTest {
    @Test
    void concurrentWritesLeaveOneCompleteSidecar(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("chunk.json");
        int writers = 8;
        byte[][] bodies = new byte[writers][];
        for (int i = 0; i < writers; i++) {
            String json = "{\"writer\":" + i + ",\"pad\":\"" + "x".repeat(4096) + "\"}";
            bodies[i] = json.getBytes(StandardCharsets.UTF_8);
        }
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                int id = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    DurableFiles.writeAtomic(target, bodies[id]);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        byte[] actual = Files.readAllBytes(target);
        boolean match = false;
        for (byte[] body : bodies) {
            if (Arrays.equals(actual, body)) {
                match = true;
                break;
            }
        }
        assertTrue(match, "sidecar bytes must equal one writer's full document");
        new ObjectMapper().readTree(actual);
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".tmp")));
        }
    }
}
