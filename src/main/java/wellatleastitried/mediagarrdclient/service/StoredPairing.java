package wellatleastitried.mediagarrdclient.service;

public record StoredPairing(String token, String id, String name) {

    @Override
    public String toString() {
        return "StoredPairing[id=" + id + ", name=" + name + "]";
    }
}
