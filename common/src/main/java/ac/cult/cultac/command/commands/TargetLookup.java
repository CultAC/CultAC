package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.labels.LabelClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Finds the player a cloud command names, in three steps: a UUID typed in full, an online player by name, then Mojang's
 * profile API by name. The first two answer at once on the calling thread; the Mojang call, including its network
 * request, runs on the given executor under one timeout.
 */
final class TargetLookup {
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern DASHED =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern PLAIN = Pattern.compile("[0-9a-fA-F]{32}");

    /** A found player, or why there is none: {@code problem} is the line for staff. */
    record Outcome(
            CloudArguments.@Nullable Target target,
            @Nullable String problem) {}

    private final Function<String, CloudArguments.Target> online;
    private final MojangProfiles mojang;
    private final Executor executor;
    private final Duration timeout;

    /** @param online an online player by name, from memory; null when there is none */
    TargetLookup(
            Function<String, CloudArguments.Target> online,
            MojangProfiles mojang,
            Executor executor,
            Duration timeout) {
        this.online = online;
        this.mojang = mojang;
        this.executor = executor;
        this.timeout = timeout;
    }

    CompletableFuture<Outcome> resolve(String input) {
        UUID typed = uuid(input);
        if (typed != null) return done(new CloudArguments.Target(typed, input));
        CloudArguments.Target player = online.apply(input);
        if (player != null) return done(player);
        if (!NAME.matcher(input).matches()) {
            return CompletableFuture.completedFuture(
                    new Outcome(null, input + " is not a player name (1-16 letters, digits or _), nor a UUID."));
        }
        CloudArguments.Target remembered = mojang.cached(input);
        if (remembered != null) return done(remembered);
        return CompletableFuture.supplyAsync(() -> mojang.lookup(input), executor)
                .thenCompose(call -> call)
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((outcome, error) -> error == null ? outcome : failed(input, error));
    }

    private static CompletableFuture<Outcome> done(CloudArguments.Target target) {
        return CompletableFuture.completedFuture(new Outcome(target, null));
    }

    private static @Nullable UUID uuid(String input) {
        if (DASHED.matcher(input).matches()) return UUID.fromString(input);
        if (PLAIN.matcher(input).matches()) {
            return new UUID(
                    Long.parseUnsignedLong(input.substring(0, 16), 16),
                    Long.parseUnsignedLong(input.substring(16), 16));
        }
        return null;
    }

    private static Outcome failed(String input, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof TimeoutException) {
            return new Outcome(null, "Looking up " + input + " with Mojang timed out; try again, or use their UUID.");
        }
        return new Outcome(null, "Mojang did not answer: " + LabelClient.unanswered(cause) + ".");
    }
}
