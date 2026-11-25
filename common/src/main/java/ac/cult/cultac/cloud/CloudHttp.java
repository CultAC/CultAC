package ac.cult.cultac.cloud;

import ac.cult.cultac.cloud.transport.CloudConnection.CappedBody;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** The cloud's HTTP API client and the virtual threads its calls run on, built on first use. */
public final class CloudHttp {
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** How long one call to the API may take. */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    public static final Executor CALLS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("cult-cloud-http-", 0).factory());
    public static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .executor(CALLS)
            .build();

    private CloudHttp() {}

    /** A JSON call to {@code base + path}, made with {@code apiKey}. */
    public static HttpRequest.Builder request(String base, String path, String apiKey) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }

    /** Sends {@code request} through {@code http} from {@code executor}, reading at most {@code maxBody} bytes back. */
    public static CompletableFuture<HttpResponse<String>> send(
            HttpClient http, Executor executor, HttpRequest.Builder request, int maxBody) {
        return CompletableFuture.supplyAsync(
                        () -> http.sendAsync(request.build(), info -> new CappedBody(maxBody)), executor)
                .thenCompose(Function.identity());
    }
}
