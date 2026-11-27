package wellatleastitried.mediagarrdclient.dto;

public record PairRequest(String pin, String name) {

    @Override
    public String toString() {
        return "PairRequest[name=" + name + "]";
    }
}
