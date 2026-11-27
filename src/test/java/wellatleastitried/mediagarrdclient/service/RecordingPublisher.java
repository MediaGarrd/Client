package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RecordingPublisher implements ClientEventPublisher {

    final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
    private volatile boolean subscribed;

    @Override
    public void publish(String name, String data) {
        if (subscribed) {
            sent.add(frame(name, data));
        }
    }

    Optional<RecordingPublisher> subscribe(List<ClientEvent> initial) {
        subscribed = true;
        initial.forEach(event -> sent.add(frame(event.name(), event.data())));
        return Optional.of(this);
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

    static String eventName(String frame) {
        for (String line : frame.split("\n")) {
            if (line.startsWith("event:")) {
                return line.substring("event:".length());
            }
        }
        return "";
    }

    static JsonNode data(String frame) {
        for (String line : frame.split("\n")) {
            if (line.startsWith("data:")) {
                return JsonMapper.builder().build().readTree(line.substring("data:".length()));
            }
        }
        throw new AssertionError("no data line in " + frame);
    }

    private static String frame(String name, String data) {
        return "event:" + name + "\ndata:" + data + "\n\n";
    }
}
