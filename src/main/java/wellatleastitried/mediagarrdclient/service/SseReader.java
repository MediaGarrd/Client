package wellatleastitried.mediagarrdclient.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class SseReader {

    static final int MAX_LINE_CHARS = 1024 * 1024;
    static final int MAX_EVENT_CHARS = 1024 * 1024;

    interface Handler {

        void onEvent(String name, String data);
    }

    private SseReader() {
    }

    static boolean read(InputStream input, Runnable onActivity, Handler handler) throws IOException {
        return read(input, onActivity, handler, MAX_LINE_CHARS, MAX_EVENT_CHARS);
    }

    static boolean read(InputStream input, Runnable onActivity, Handler handler, int maxLine, int maxEvent) throws IOException {
        boolean dispatched = false;
        String name = null;
        StringBuilder data = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = readLine(reader, maxLine)) != null) {
                onActivity.run();
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        dispatched = true;
                        handler.onEvent(name == null ? "message" : name, data.toString());
                    }
                    name = null;
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    continue;
                } else {
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    if (field.equals("event")) {
                        name = value;
                    } else if (field.equals("data")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        if (data.length() + value.length() > maxEvent) {
                            throw new IOException("The event is larger than " + maxEvent + " characters");
                        }
                        data.append(value);
                    }
                }
            }
        }
        return dispatched;
    }

    private static String readLine(BufferedReader reader, int maxLine) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                return line.toString();
            }
            if (c == '\r') {
                reader.mark(1);
                if (reader.read() != '\n') {
                    reader.reset();
                }
                return line.toString();
            }
            if (line.length() >= maxLine) {
                throw new IOException("The line is longer than " + maxLine + " characters");
            }
            line.append((char) c);
        }
        return line.length() == 0 ? null : line.toString();
    }
}
