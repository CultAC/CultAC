package ac.cult.cultac.cloud;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** The cloud's HTTP API client and the virtual threads its calls run on, built on first use. */
public final class CloudHttp {
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Executor CALLS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("cult-cloud-http-", 0).factory());
    public static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .executor(CALLS)
            .build();

    private CloudHttp() {}
}
