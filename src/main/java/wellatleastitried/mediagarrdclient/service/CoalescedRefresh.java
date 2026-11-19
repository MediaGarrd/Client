package wellatleastitried.mediagarrdclient.service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CoalescedRefresh {

    private static final Logger LOGGER = LoggerFactory.getLogger(CoalescedRefresh.class);

    private final Object lock;
    private final Executor executor;
    private final Runnable work;
    private boolean inFlight;
    private boolean again;

    CoalescedRefresh(Object lock, Executor executor, Runnable work) {
        this.lock = lock;
        this.executor = executor;
        this.work = work;
    }

    void request() {
        synchronized (lock) {
            if (inFlight) {
                again = true;
                return;
            }
            inFlight = true;
        }
        try {
            executor.execute(this::loop);
        } catch (RejectedExecutionException e) {
            synchronized (lock) {
                inFlight = false;
            }
        }
    }

    boolean busy() {
        return inFlight;
    }

    private void loop() {
        boolean repeat;
        do {
            try {
                work.run();
            } catch (RuntimeException e) {
                LOGGER.warn("Dashboard refresh failed: {}", e.getMessage());
            }
            synchronized (lock) {
                repeat = again;
                again = false;
                if (!repeat) {
                    inFlight = false;
                }
            }
        } while (repeat);
    }
}
