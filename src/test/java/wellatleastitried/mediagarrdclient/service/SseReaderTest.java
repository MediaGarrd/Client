package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SseReaderTest {

    private static List<String> read(String stream, AtomicInteger lines) throws IOException {
        List<String> events = new ArrayList<>();
        SseReader.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), lines::incrementAndGet, (name, data) -> events.add(name + "=" + data));
        return events;
    }

    @Test
    void reportsWhetherAnyEventWasDispatched() throws IOException {
        SseReader.Handler ignore = (name, data) -> {
        };

        assertTrue(SseReader.read(new ByteArrayInputStream("data: a\n\n".getBytes(StandardCharsets.UTF_8)), () -> {
        }, ignore));
        assertFalse(SseReader.read(new ByteArrayInputStream(": ping\n\ndata: unfinished".getBytes(StandardCharsets.UTF_8)), () -> {
        }, ignore));
    }

    @Test
    void parsesNamedAndUnnamedEventsAndMultiLineData() throws IOException {
        List<String> events = read("event: state\ndata: {\"running\":true}\n\ndata: a\ndata: b\n\n", new AtomicInteger());

        assertEquals(List.of("state={\"running\":true}", "message=a\nb"), events);
    }

    @Test
    void ignoresCommentsUnknownFieldsAndEventsWithoutData() throws IOException {
        List<String> events = read(": ping\nretry: 5000\nevent: lonely\n\nevent: state\ndata:x\n\n", new AtomicInteger());

        assertEquals(List.of("state=x"), events);
    }

    @Test
    void dropsAnEventThatIsNotTerminatedByABlankLine() throws IOException {
        assertEquals(List.of(), read("event: state\ndata: 1\n", new AtomicInteger()));
    }

    @Test
    void refusesALineLongerThanTheCap() {
        assertThrows(IOException.class, () -> SseReader.read(new ByteArrayInputStream(("data: " + "x".repeat(100) + "\n\n").getBytes(StandardCharsets.UTF_8)),
            () -> { }, (name, data) -> { }, 50, 1000));
    }

    @Test
    void refusesAnEventLargerThanTheCapEvenWhenEveryLineIsSmall() {
        String stream = "data: " + "x".repeat(30) + "\ndata: " + "y".repeat(30) + "\n\n";

        assertThrows(IOException.class, () -> SseReader.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
            () -> { }, (name, data) -> { }, 50, 40));
    }

    @Test
    void acceptsLinesAndEventsWithinTheCapsAndAllLineEndings() throws IOException {
        List<String> events = new ArrayList<>();

        SseReader.read(new ByteArrayInputStream("event: a\r\ndata: 1\r\r\ndata: 2\n\n".getBytes(StandardCharsets.UTF_8)),
            () -> { }, (name, data) -> events.add(name + "=" + data), 50, 50);

        assertEquals(List.of("a=1", "message=2"), events);
    }

    @Test
    void aLargeLegitimateEventPassesTheDefaultCaps() throws IOException {
        List<String> events = new ArrayList<>();

        SseReader.read(new ByteArrayInputStream(("event: backups-changed\ndata: " + "x".repeat(200_000) + "\n\n").getBytes(StandardCharsets.UTF_8)),
            () -> { }, (name, data) -> events.add(name));

        assertEquals(List.of("backups-changed"), events);
    }

    @Test
    void reportsActivityForEveryLine() throws IOException {
        AtomicInteger lines = new AtomicInteger();

        read(": a\nevent: x\ndata: 1\n\n", lines);

        assertEquals(4, lines.get());
    }
}
