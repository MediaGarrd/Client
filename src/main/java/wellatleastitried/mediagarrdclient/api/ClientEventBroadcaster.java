package wellatleastitried.mediagarrdclient.api;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import wellatleastitried.mediagarrdclient.service.ClientEvent;
import wellatleastitried.mediagarrdclient.service.ClientEventPublisher;
import wellatleastitried.mediagarrdclient.service.SessionService;

@Service
public class ClientEventBroadcaster implements ClientEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientEventBroadcaster.class);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(20);
    private static final Duration STREAM_LIFETIME = Duration.ofSeconds(270);
    private static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(6);
    private static final int MAX_SUBSCRIBERS = 32;
    private static final int QUEUE_CAPACITY = 64;
    private static final long RETRY_MILLIS = 5000;
    private static final String PING_DATA = "{}";

    private static final ClientEvent PING = new ClientEvent("ping", PING_DATA);
    private static final ClientEvent CLOSE = new ClientEvent("close", "");

    private final class Subscriber {

        private final SseEmitter emitter;
        private final String sessionKey;
        private final BlockingQueue<ClientEvent> queue = new LinkedBlockingQueue<>(queueCapacity);
        private volatile boolean closed;

        private Subscriber(SseEmitter emitter, String sessionKey) {
            this.emitter = emitter;
            this.sessionKey = sessionKey;
        }

        private boolean offer(ClientEvent message) {
            return !closed && queue.offer(message);
        }

        private void close() {
            closed = true;
            queue.offer(CLOSE);
        }

        private void run() {
            boolean failed = false;
            boolean first = true;
            long deadline = System.nanoTime() + streamLifetime.toNanos();
            try {
                while (!closed) {
                    long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remainingMillis <= 0) {
                        break;
                    }
                    ClientEvent polled = queue.poll(Math.min(pingInterval.toMillis(), remainingMillis), TimeUnit.MILLISECONDS);
                    if (closed || polled == CLOSE) {
                        break;
                    }
                    if (polled == null && (System.nanoTime() >= deadline || sessionKey != null && !sessionActive.test(sessionKey))) {
                        break;
                    }
                    ClientEvent message = polled == null ? PING : polled;
                    SseEmitter.SseEventBuilder event = SseEmitter.event();
                    if (first) {
                        event.reconnectTime(RETRY_MILLIS);
                        first = false;
                    }
                    emitter.send(event.name(message.name()).data(message.data()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                failed = true;
                LOGGER.debug("Browser event stream ended: {}", e.getMessage());
            } finally {
                detach(this);
                if (!failed) {
                    try {
                        emitter.complete();
                    } catch (RuntimeException e) {
                        LOGGER.debug("Completing browser event stream failed: {}", e.getMessage());
                    }
                }
            }
        }
    }

    private final Duration pingInterval;
    private final int maxSubscribers;
    private final int queueCapacity;
    private final Duration streamLifetime;
    private final long emitterTimeoutMillis;
    private final ThreadFactory threadFactory;
    private final LongFunction<SseEmitter> emitterFactory;
    private final Predicate<String> sessionActive;
    private final Object lock = new Object();
    private final List<Subscriber> subscribers = new ArrayList<>();
    private boolean shutdown;

    @Autowired
    public ClientEventBroadcaster(SessionService sessions) {
        this(PING_INTERVAL, MAX_SUBSCRIBERS, QUEUE_CAPACITY, STREAM_LIFETIME, EMITTER_TIMEOUT, writerThreads(), SseEmitter::new, sessions::isActive);
        sessions.addEndedListener(this::endSession);
    }

    ClientEventBroadcaster(
        Duration pingInterval,
        int maxSubscribers,
        int queueCapacity,
        Duration streamLifetime,
        Duration emitterTimeout,
        ThreadFactory threadFactory,
        LongFunction<SseEmitter> emitterFactory
    ) {
        this(pingInterval, maxSubscribers, queueCapacity, streamLifetime, emitterTimeout, threadFactory, emitterFactory, key -> true);
    }

    ClientEventBroadcaster(
        Duration pingInterval,
        int maxSubscribers,
        int queueCapacity,
        Duration streamLifetime,
        Duration emitterTimeout,
        ThreadFactory threadFactory,
        LongFunction<SseEmitter> emitterFactory,
        Predicate<String> sessionActive
    ) {
        this.sessionActive = sessionActive;
        this.pingInterval = pingInterval;
        this.maxSubscribers = maxSubscribers;
        this.queueCapacity = queueCapacity;
        this.streamLifetime = streamLifetime;
        this.emitterTimeoutMillis = emitterTimeout.toMillis();
        this.threadFactory = threadFactory;
        this.emitterFactory = emitterFactory;
    }

    private static ThreadFactory writerThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> Thread.ofPlatform().daemon().name("client-events-writer-" + counter.incrementAndGet()).unstarted(runnable);
    }

    public Optional<SseEmitter> subscribe(List<ClientEvent> initial) {
        return subscribe(null, initial);
    }

    public Optional<SseEmitter> subscribe(String sessionKey, List<ClientEvent> initial) {
        SseEmitter emitter = emitterFactory.apply(emitterTimeoutMillis);
        Subscriber subscriber = new Subscriber(emitter, sessionKey);
        Thread writer = threadFactory.newThread(subscriber::run);
        synchronized (lock) {
            if (shutdown || subscribers.size() >= maxSubscribers) {
                return Optional.empty();
            }
            subscribers.add(subscriber);
            initial.forEach(subscriber::offer);
        }
        emitter.onCompletion(() -> detach(subscriber));
        emitter.onTimeout(() -> detach(subscriber));
        emitter.onError(error -> detach(subscriber));
        writer.start();
        return Optional.of(emitter);
    }

    @Override
    public void publish(String name, String data) {
        synchronized (lock) {
            broadcast(new ClientEvent(name, data));
        }
    }

    void endSession(String sessionKey) {
        synchronized (lock) {
            subscribers.removeIf(subscriber -> {
                boolean ends = sessionKey.equals(subscriber.sessionKey);
                if (ends) {
                    subscriber.close();
                }
                return ends;
            });
        }
    }

    public int subscriberCount() {
        synchronized (lock) {
            return subscribers.size();
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (lock) {
            shutdown = true;
            subscribers.forEach(Subscriber::close);
            subscribers.clear();
        }
    }

    private void broadcast(ClientEvent message) {
        Iterator<Subscriber> iterator = subscribers.iterator();
        while (iterator.hasNext()) {
            Subscriber subscriber = iterator.next();
            if (!subscriber.offer(message)) {
                LOGGER.warn("Dropping a browser event subscriber that is not keeping up");
                subscriber.close();
                iterator.remove();
            }
        }
    }

    private void detach(Subscriber subscriber) {
        synchronized (lock) {
            subscribers.remove(subscriber);
        }
        subscriber.close();
    }
}
