package wellatleastitried.mediagarrdclient.service;

import java.util.Optional;

@FunctionalInterface
public interface ServerTokens {

    Optional<String> tokenFor(String serverUrl);
}
