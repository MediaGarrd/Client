package wellatleastitried.mediagarrdclient.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import wellatleastitried.mediagarrdclient.dto.LinkState;

final class LinkStateMachine {

    private final DashboardEvents events;
    private final Clock clock;
    private LinkState link = LinkState.DOWN;
    private Boolean running;

    LinkStateMachine(DashboardEvents events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    LinkState link() {
        return link;
    }

    Boolean running() {
        return running;
    }

    boolean reachable() {
        return link == LinkState.LIVE || link == LinkState.DEGRADED;
    }

    void reset(boolean serverSelected) {
        link = serverSelected ? LinkState.CONNECTING : LinkState.DOWN;
        running = null;
    }

    void setRunning(boolean now) {
        running = now;
    }

    boolean setLink(LinkState next, String activeServer) {
        if (link == next) {
            return false;
        }
        link = next;
        if (!reachable()) {
            running = null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("server", activeServer == null ? "" : activeServer);
        payload.put("state", next);
        events.publish("link", payload);
        return true;
    }

    boolean setPolledRunning(boolean polled) {
        if (Boolean.valueOf(polled).equals(running)) {
            return false;
        }
        boolean runFinished = Boolean.TRUE.equals(running) && !polled;
        running = polled;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("running", polled);
        payload.put("at", clock.instant().toString());
        events.publish("state", payload);
        return runFinished;
    }
}
