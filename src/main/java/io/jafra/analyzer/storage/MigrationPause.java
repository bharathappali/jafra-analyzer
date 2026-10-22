package io.jafra.analyzer.storage;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Test hook for a migration that should stall until released. Production leaves the latches unset.
 */
public final class MigrationPause {
    private static volatile CountDownLatch entered;
    private static volatile CountDownLatch release;

    private MigrationPause() {}

    public static void arm(CountDownLatch entered, CountDownLatch release) {
        MigrationPause.entered = entered;
        MigrationPause.release = release;
    }

    public static void clear() {
        entered = null;
        release = null;
    }

    public static void await(String phase) throws IOException {
        CountDownLatch started = entered;
        CountDownLatch gate = release;
        if (started == null && gate == null) {
            return;
        }
        if (started != null) {
            started.countDown();
        }
        if (gate == null) {
            return;
        }
        try {
            gate.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while migration is paused at " + phase, error);
        }
    }
}
