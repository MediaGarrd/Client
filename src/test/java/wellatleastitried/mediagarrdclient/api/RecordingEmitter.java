package wellatleastitried.mediagarrdclient.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class RecordingEmitter extends SseEmitter {

    public final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
    public final CountDownLatch completed = new CountDownLatch(1);

    public RecordingEmitter(Long timeout) {
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

    public String next() throws InterruptedException {
        String event = sent.poll(5, TimeUnit.SECONDS);
        assertNotNull(event, "timed out waiting for an event");
        return event;
    }

    public String nextContaining(String marker) throws InterruptedException {
        while (true) {
            String event = next();
            if (event.contains(marker)) {
                return event;
            }
        }
    }
}
