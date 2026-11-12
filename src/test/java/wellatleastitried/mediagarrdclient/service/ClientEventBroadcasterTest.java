package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Timeout(30)
class ClientEventBroadcasterTest {

    private static final Duration LONG_PING = Duration.ofMinutes(10);

    static class RecordingEmitter extends SseEmitter {

        final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
        final CountDownLatch completed = new CountDownLatch(1);

        RecordingEmitter(Long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            StringBuilder text = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                text.append(part.getData());
            }
            sent.add(text.toString());
        }

        @Override
        public void complete() {
            completed.countDown();
            super.complete();
        }

        String next() throws InterruptedException {
            String event = sent.poll(5, TimeUnit.SECONDS);
            assertNotNull(event, "timed out waiting for an event");
            return event;
        }

        String nextContaining(String marker) throws InterruptedException {
            while (true) {
                String event = next();
                if (event.contains(marker)) {
                    return event;
                }
            }
        }
    }

    static class FailingEmitter extends RecordingEmitter {

        FailingEmitter(Long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("client went away");
        }
    }

    static class StuckEmitter extends RecordingEmitter {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        StuckEmitter(Long timeout) {
            super(timeout);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final List<Thread> writers = new CopyOnWriteArrayList<>();
    private final ThreadFactory threadFactory = runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        writers.add(thread);
        return thread;
    };
    private final List<ClientEventBroadcaster> broadcasters = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        broadcasters.forEach(ClientEventBroadcaster::shutdown);
        for (Thread writer : writers) {
            writer.join(5000);
        }
    }

    private ClientEventBroadcaster broadcaster(Duration ping, int max, int queue, LongFunction<SseEmitter> factory) {
        return broadcaster(ping, max, queue, Duration.ofMinutes(10), factory);
    }

    private ClientEventBroadcaster broadcaster(Duration ping, int max, int queue, Duration lifetime, LongFunction<SseEmitter> factory) {
        ClientEventBroadcaster broadcaster = new ClientEventBroadcaster(ping, max, queue, lifetime, Duration.ofMinutes(5), threadFactory, factory);
        broadcasters.add(broadcaster);
        return broadcaster;
    }

    private ClientEventBroadcaster broadcaster() {
        return broadcaster(LONG_PING, 32, 64, RecordingEmitter::new);
    }

    private static final List<ClientEvent> SNAPSHOT = List.of(new ClientEvent("snapshot", "{\"link\":\"DOWN\"}"));

    private static RecordingEmitter subscribe(ClientEventBroadcaster broadcaster) {
        return (RecordingEmitter) broadcaster.subscribe(SNAPSHOT).orElseThrow();
    }

    @Test
    void sendsTheInitialEventsFirstAndTellsTheBrowserHowLongToWaitBeforeReconnecting() throws Exception {
        ClientEventBroadcaster broadcaster = broadcaster();

        RecordingEmitter emitter = subscribe(broadcaster);

        assertEquals("retry:5000\nevent:snapshot\ndata:{\"link\":\"DOWN\"}\n\n", emitter.next());
        assertNull(emitter.sent.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void theReconnectHintIsSentOnlyOnce() throws Exception {
        ClientEventBroadcaster broadcaster = broadcaster();
        RecordingEmitter emitter = subscribe(broadcaster);
        emitter.next();

        broadcaster.publish("run-started", "{\"running\":true}");

        assertEquals("event:run-started\ndata:{\"running\":true}\n\n", emitter.next());
    }

    @Test
    void publishesAreOrderedAfterTheInitialEventsForALateSubscriber() throws Exception {
        ClientEventBroadcaster broadcaster = broadcaster();
        broadcaster.publish("link", "{\"state\":\"LIVE\"}");
        RecordingEmitter emitter = subscribe(broadcaster);
        broadcaster.publish("state", "{\"running\":true}");

        assertTrue(emitter.next().contains("event:snapshot"));
        assertTrue(emitter.next().contains("event:state"));
        assertNull(emitter.sent.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void fansEventsOutToEverySubscriberInOrder() throws Exception {
        ClientEventBroadcaster broadcaster = broadcaster();
        RecordingEmitter first = subscribe(broadcaster);
        RecordingEmitter second = subscribe(broadcaster);
        first.next();
        second.next();

        broadcaster.publish("run-started", "{\"running\":true}");
        broadcaster.publish("run-finished", "{\"running\":false,\"status\":\"FAILED\"}");

        for (RecordingEmitter emitter : List.of(first, second)) {
            assertEquals("event:run-started\ndata:{\"running\":true}\n\n", emitter.next());
            assertEquals("event:run-finished\ndata:{\"running\":false,\"status\":\"FAILED\"}\n\n", emitter.next());
        }
    }

    @Test
    void sendsNamedPingsWhenIdle() throws Exception {
        ClientEventBroadcaster broadcaster = broadcaster(Duration.ofMillis(20), 32, 64, RecordingEmitter::new);
        RecordingEmitter emitter = subscribe(broadcaster);

        emitter.nextContaining("event:ping\ndata:{}\n\n");
    }

    @Test
    void rejectsSubscribersBeyondTheCap() {
        ClientEventBroadcaster broadcaster = broadcaster(LONG_PING, 2, 64, RecordingEmitter::new);
        subscribe(broadcaster);
        subscribe(broadcaster);

        assertTrue(broadcaster.subscribe(SNAPSHOT).isEmpty());
        assertEquals(2, broadcaster.subscriberCount());
    }

    @Test
    void forgetsASubscriberWhoseWriteFailsAndFreesItsSlot() throws Exception {
        ClientEventBroadcaster broadcaster = broadcaster(LONG_PING, 1, 64, FailingEmitter::new);

        broadcaster.subscribe(SNAPSHOT);
        writers.get(0).join(5000);

        assertEquals(0, broadcaster.subscriberCount());
        assertTrue(broadcaster.subscribe(SNAPSHOT).isPresent());
    }

    @Test
    void shutdownCompletesEverySubscriber() throws Exception {
        ClientEventBroadcaster broadcaster = broadcaster();
        RecordingEmitter emitter = subscribe(broadcaster);
        emitter.next();

        broadcaster.shutdown();

        assertTrue(emitter.completed.await(5, TimeUnit.SECONDS));
        assertEquals(0, broadcaster.subscriberCount());
        assertTrue(broadcaster.subscribe(SNAPSHOT).isEmpty());
    }

    @Test
    void publishingNeverBlocksOnAStuckSubscriberAndOnlyItsWriterCompletesIt() throws Exception {
        ClientEventBroadcaster broadcaster = broadcaster(LONG_PING, 4, 1, StuckEmitter::new);
        StuckEmitter stuck = (StuckEmitter) broadcaster.subscribe(SNAPSHOT).orElseThrow();
        assertTrue(stuck.entered.await(5, TimeUnit.SECONDS));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 0; i < 5; i++) {
                broadcaster.publish("run-started", "{\"running\":true}");
            }
            broadcaster.publish("link", "{}");
        });

        assertEquals(0, broadcaster.subscriberCount());
        assertFalse(stuck.completed.await(200, TimeUnit.MILLISECONDS));
        stuck.release.countDown();
        assertTrue(stuck.completed.await(5, TimeUnit.SECONDS));
    }

    @Test
    void theWriterEndsAStreamItselfOnceItsLifetimeIsUp() throws Exception {
        ClientEventBroadcaster broadcaster = broadcaster(LONG_PING, 4, 64, Duration.ofMillis(100), RecordingEmitter::new);

        RecordingEmitter emitter = subscribe(broadcaster);

        assertTrue(emitter.completed.await(5, TimeUnit.SECONDS));
        assertEquals(0, broadcaster.subscriberCount());
    }
}
