package wellatleastitried.mediagarrdclient.service;

import java.util.LinkedHashMap;
import java.util.Map;

import wellatleastitried.mediagarrdclient.dto.PairingState;

final class PairingStatus {

    private final ClientStateService stateService;
    private final DashboardEvents events;
    private boolean rejected;

    PairingStatus(ClientStateService stateService, DashboardEvents events) {
        this.stateService = stateService;
        this.events = events;
    }

    PairingState of(String activeServer) {
        if (activeServer == null) {
            return PairingState.NONE;
        }
        if (!stateService.hasPairing(activeServer)) {
            return PairingState.REQUIRED;
        }
        return rejected ? PairingState.REJECTED : PairingState.PAIRED;
    }

    void clear() {
        rejected = false;
    }

    void reject(String activeServer) {
        if (rejected) {
            return;
        }
        rejected = true;
        publish(activeServer);
    }

    boolean observe(boolean tokenRejected, String activeServer) {
        if (tokenRejected == rejected) {
            return false;
        }
        boolean cleared = rejected && !tokenRejected;
        rejected = tokenRejected;
        publish(activeServer);
        return cleared;
    }

    private void publish(String activeServer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", of(activeServer));
        events.publish("pairing", payload);
    }
}
