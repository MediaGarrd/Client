package wellatleastitried.mediagarrdclient.service;

import java.util.Optional;
import java.util.function.LongConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import wellatleastitried.mediagarrdclient.dto.PairingCodeDto;
import wellatleastitried.mediagarrdclient.dto.PairingResultDto;
import wellatleastitried.mediagarrdclient.dto.UnpairResultDto;

@Service
public class ClientPairingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientPairingService.class);
    static final String DEFAULT_NAME = "MediaGarrd Client";
    static final int MAX_CREDENTIAL_LENGTH = 64;
    static final int MAX_NAME_LENGTH = 64;
    static final String UNREVOKED_WARNING = "The server could not be told to forget this client, so it may still list it as paired. Remove it with DELETE /api/v1/clients/{id} on the server.";
    static final String NOT_PAIRED_WARNING = "This client was not paired with the server.";
    static final String NO_ID_WARNING = "This client has no id on the server, so it could not be removed there. Remove it with DELETE /api/v1/clients/{id} on the server.";
    static final String ALREADY_PAIRED_MESSAGE = "This client is already paired with this server. Unpair it first.";
    static final String DEFERRED_CONFIRMATION_NOTE = "Paired. The server could not be reached to confirm it yet; it is confirmed automatically on the next connection. If it is not, you will be asked to pair again, and the same PIN, or a new pairing code, will work.";
    static final long[] CONFIRM_RETRY_DELAYS_MILLIS = {250, 500, 1000};

    private final ClientStateService stateService;
    private final ServerApiService serverApiService;
    private final LongConsumer pause;

    @Autowired
    public ClientPairingService(ClientStateService stateService, ServerApiService serverApiService) {
        this(stateService, serverApiService, ClientPairingService::sleep);
    }

    ClientPairingService(ClientStateService stateService, ServerApiService serverApiService, LongConsumer pause) {
        this.stateService = stateService;
        this.serverApiService = serverApiService;
        this.pause = pause;
    }

    public synchronized PairingResultDto pair(String credential, String name) {
        if (credential == null || credential.isBlank()) {
            throw new InvalidRequestException("Enter the PIN or pairing code");
        }
        if (credential.trim().length() > MAX_CREDENTIAL_LENGTH) {
            throw new InvalidRequestException("The PIN or pairing code must be at most " + MAX_CREDENTIAL_LENGTH + " characters");
        }
        if (name != null && name.trim().length() > MAX_NAME_LENGTH) {
            throw new InvalidRequestException("The name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        String server = stateService.requireActiveServer();
        refuseWhileAlreadyPaired(server);
        StoredPairing pairing = serverApiService.pair(server, credential.trim(), name == null || name.isBlank() ? DEFAULT_NAME : name.trim());
        stateService.storePairing(server, pairing);
        try {
            confirm(server, pairing);
        } catch (InvalidRequestException | ConfirmationRefusedException e) {
            stateService.forgetPairingIf(server, pairing.token());
            throw e;
        } catch (RuntimeException e) {
            LOGGER.warn("Could not confirm the pairing with {}, keeping it for the next connection to confirm: {}", server, e.getMessage());
            return new PairingResultDto(true, pairing.name(), DEFERRED_CONFIRMATION_NOTE);
        }
        return new PairingResultDto(true, pairing.name(), null);
    }

    private void refuseWhileAlreadyPaired(String server) {
        Optional<StoredPairing> stored = stateService.pairingFor(server);
        if (stored.isEmpty()) {
            return;
        }
        try {
            serverApiService.confirmPairing(server, stored.get().token());
        } catch (InvalidRequestException e) {
            return;
        }
        throw new ConflictException(ALREADY_PAIRED_MESSAGE);
    }

    public PairingCodeDto createCode() {
        return serverApiService.createPairingCode(stateService.requireActiveServer());
    }

    public synchronized UnpairResultDto unpair() {
        String server = stateService.requireActiveServer();
        StoredPairing pairing = stateService.pairingFor(server).orElse(null);
        String warning = null;
        if (pairing != null && hasId(pairing)) {
            try {
                serverApiService.revokeClient(server, pairing.id());
            } catch (RuntimeException e) {
                LOGGER.warn("Could not revoke this client on {}, forgetting the token locally anyway: {}", server, e.getMessage());
                warning = UNREVOKED_WARNING;
            }
        } else {
            warning = pairing == null ? NOT_PAIRED_WARNING : NO_ID_WARNING;
        }
        stateService.forgetPairing(server);
        return new UnpairResultDto(warning == null, warning);
    }

    private void confirm(String server, StoredPairing pairing) {
        for (int attempt = 0; ; attempt++) {
            try {
                serverApiService.confirmPairing(server, pairing.token());
                return;
            } catch (ResourceAccessException | ConfirmationUnavailableException e) {
                if (attempt >= CONFIRM_RETRY_DELAYS_MILLIS.length) {
                    throw e;
                }
                LOGGER.warn("Could not confirm the pairing with {}, retrying: {}", server, e.getMessage());
                pause.accept(CONFIRM_RETRY_DELAYS_MILLIS[attempt]);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamResponseException("Pairing was interrupted before the server confirmed it");
        }
    }

    private static boolean hasId(StoredPairing pairing) {
        return pairing.id() != null && !pairing.id().isBlank();
    }
}
