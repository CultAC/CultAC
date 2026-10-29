package ac.cult.cultac.cloud.labels;

import java.net.http.HttpClient;

/** Label clients for tests outside this package. */
public final class LabelClients {
    private LabelClients() {}

    /** A client whose calls never answer, like a cloud that is slow to. */
    public static LabelClient silent() {
        return new LabelClient("http://127.0.0.1:1", "key", HttpClient.newHttpClient(), call -> {});
    }
}
