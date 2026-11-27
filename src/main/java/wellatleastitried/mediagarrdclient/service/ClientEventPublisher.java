package wellatleastitried.mediagarrdclient.service;

public interface ClientEventPublisher {

    void publish(String name, String data);
}
