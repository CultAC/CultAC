package ac.cult.cultac.runtime;

import ac.cult.cultac.checks.Check;
import ac.cult.reload.api.HostContext;
import ac.cult.reload.host.HttpClients;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimUser;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** This class is private to each generation; offline fixtures have no host context. */
public final class RuntimeEnvironment {
    private static HostContext context;

    private RuntimeEnvironment() {}

    public static void initialize(HostContext host) {
        if (context != null) throw new IllegalStateException("Runtime already bound");
        context = host;
    }

    public static HostContext host() {
        return context;
    }

    public static boolean owns(Object session) {
        return context == null || context.owns(session);
    }

    public static void release(Object session, CompletionStage<Void> cleanup) {
        if (context != null) context.release(session, cleanup);
    }

    public static <T> T track(T resource, Runnable cleanup) {
        return context == null ? resource : context.resources().track(resource, cleanup);
    }

    public static Runnable guard(Runnable task) {
        return context == null ? task : context.resources().guard(task);
    }

    public static Runnable once(Runnable task) {
        return context == null ? task : context.resources().once(task);
    }

    public static HttpClient http(Duration timeout) {
        var client = context == null
                ? HttpClient.newBuilder().connectTimeout(timeout).build()
                : HttpClients.create(timeout);
        return track(client, client::close);
    }

    public static GrimUser outwardUser(GrimUser user) {
        return context == null ? user : (GrimUser) context.wrap(user, user);
    }

    public static AbstractCheck outwardCheck(AbstractCheck check) {
        return context == null
                ? check
                : (AbstractCheck) context.wrap(check, check instanceof Check impl ? impl.getPlayer() : null);
    }

    @SuppressWarnings("unchecked")
    public static Supplier<String> outwardVerbose(Supplier<String> supplier) {
        return context == null ? supplier : (Supplier<String>) context.wrap(supplier);
    }
}
