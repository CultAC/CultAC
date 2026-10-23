package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.CloudService;
import ac.cult.cultac.cloud.findings.FindingsLog;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.jetbrains.annotations.Nullable;

/** What the cloud commands share: their permissions, players by name or UUID, and check names. */
final class CloudArguments {
    static final String VIEW = "cult.cloud.view";
    static final String LABEL = "cult.cloud.label";
    /** Offers Clean in the label menu; the service still takes a clean label only from an admin. */
    static final String LABEL_CLEAN = "cult.cloud.label.clean";

    static final String NOT_CONFIGURED = "Cloud is not configured on this server.";
    static final String CRACKED = "Labeling is disabled on offline-mode servers.";
    static final int MAX_SUGGESTIONS = 100;

    record Target(UUID id, String name) {}

    private CloudArguments() {}

    private static final TargetLookup LOOKUP = new TargetLookup(
            CloudArguments::online,
            MojangProfiles.live(),
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("cult-player-lookup-", 0).factory()),
            TargetLookup.TIMEOUT);

    /** The findings log; null, after telling {@code sender}, while the upload is not configured. */
    static @Nullable FindingsLog findings(Sender sender) {
        FindingsLog log = CloudService.findings();
        if (log == null) sender.sendMessage(Component.text(NOT_CONFIGURED, NamedTextColor.RED));
        return log;
    }

    /**
     * Whether players can be named here; when they cannot, tells {@code sender} why. An offline-mode server with no
     * online-mode proxy in front names players by offline UUIDs that match no real account.
     */
    static boolean labelingAllowed(Sender sender) {
        return labelingAllowed(
                sender, () -> CultAPI.INSTANCE.getPlatformServer().isOnlineMode());
    }

    static boolean labelingAllowed(Sender sender, BooleanSupplier onlineMode) {
        boolean authenticated = onlineMode.getAsBoolean();
        if (!authenticated) sender.sendMessage(Component.text(CRACKED, NamedTextColor.RED));
        return authenticated;
    }

    /**
     * Runs {@code then} with the player {@code input} names, or tells {@code sender} why there is none. A UUID or an
     * online player is answered inline; any other name goes to Mojang off the calling thread, and {@code then} runs
     * when that completes. A lookup that fails, or a {@code then} that throws, is told to {@code sender} too.
     */
    static void resolve(Sender sender, String input, Consumer<Target> then) {
        LOOKUP.resolve(input).whenComplete((outcome, error) -> {
            try {
                if (error != null) {
                    sender.sendMessage(Component.text(
                            "Could not look up " + input + ": "
                                    + error.getClass().getSimpleName() + ".",
                            NamedTextColor.RED));
                } else if (outcome.target() == null) {
                    sender.sendMessage(Component.text(outcome.problem(), NamedTextColor.RED));
                } else {
                    then.accept(outcome.target());
                }
            } catch (RuntimeException failed) {
                sender.sendMessage(Component.text(
                        "The command failed: " + failed.getClass().getSimpleName() + ".", NamedTextColor.RED));
            }
        });
    }

    private static @Nullable Target online(String name) {
        for (CultPlayer player : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            if (name.equalsIgnoreCase(player.getName())) return new Target(player.getUniqueId(), player.getName());
        }
        return null;
    }

    static SuggestionProvider<Sender> players() {
        return startingWith(() -> {
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (CultPlayer player : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) {
                if (player.getName() != null) names.add(player.getName());
            }
            return names;
        });
    }

    static SuggestionProvider<Sender> checks() {
        return startingWith(() -> {
            FindingsLog log = CloudService.findings();
            return log == null ? Set.of() : log.checks();
        });
    }

    static SuggestionProvider<Sender> of(Supplier<List<String>> values) {
        return startingWith(values::get);
    }

    private static SuggestionProvider<Sender> startingWith(Supplier<Collection<String>> values) {
        return SuggestionProvider.blocking((context, input) -> matching(values.get(), input.peekString()).stream()
                .map(Suggestion::suggestion)
                .toList());
    }

    /** The values starting with {@code partial}, ignoring case: at most {@link #MAX_SUGGESTIONS} of them. */
    static List<String> matching(Collection<String> values, String partial) {
        String lower = partial.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .limit(MAX_SUGGESTIONS)
                .toList();
    }
}
