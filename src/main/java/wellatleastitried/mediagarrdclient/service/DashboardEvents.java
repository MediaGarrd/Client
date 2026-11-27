package wellatleastitried.mediagarrdclient.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class DashboardEvents {

    private final ClientEventPublisher publisher;
    private final ObjectMapper mapper;

    DashboardEvents(ClientEventPublisher publisher, ObjectMapper mapper) {
        this.publisher = publisher;
        this.mapper = mapper;
    }

    void publish(String name, Object payload) {
        publisher.publish(name, json(payload));
    }

    void publishRaw(String name, String data) {
        publisher.publish(name, data);
    }

    String json(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}
