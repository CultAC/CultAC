package ac.cult.cultac.cloud.diag;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.platform.api.PlatformPlugin;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.ServerFacts;
import ac.cult.cultac.platform.api.manager.PlatformPluginManager;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostics on a player's Java predictions: a watch hangs on their {@link SuperDebug}, which copies each prediction
 * into the watch's ring, and a cut is answered from that ring. A Bedrock player has no Java prediction to keep. Every
 * method runs on the player's event loop; the only per-player state is {@link SuperDebug#diag}.
 */
public final class PredictionDiagnostics {
    public static final PredictionDiagnostics INSTANCE = new PredictionDiagnostics();
    /** Plugins that rewrite movement, teleports or packets, in the order the diag header lists them. */
    static final List<String> HEADER_PLUGINS = List.of(
            "ViaVersion",
            "ViaBackwards",
            "ViaRewind",
            "GrimAC",
            "ProtocolLib",
            "packetevents",
            "Vulcan",
            "Spartan",
            "Matrix",
            "Themis",
            "NoCheatPlus",
            "Polar",
            "LightAC");

    private static final AtomicBoolean readingServerFields = new AtomicBoolean();
    /** The diag header's server and plugins fields, read a tick after the upload loads; null until then or if unreadable. */
    private static volatile String serverFields;

    private PredictionDiagnostics() {}

    /**
     * The service watches {@code player} for {@code leaseSeconds} from the call, or stops at 0, on the socket of
     * {@code generation}; {@code frameSeq} reads the seq of the frame a prediction belongs to.
     */
    public void watch(
            CultPlayer player, Diagnostics diagnostics, long generation, int leaseSeconds, LongSupplier frameSeq) {
        if (player.isBedrockMovement()) {
            diagnostics.refuse(player.getUniqueId(), "bedrock");
            return;
        }
        SuperDebug debug = player.checkManager.getListener(SuperDebug.class);
        if (debug == null) {
            diagnostics.refuse(player.getUniqueId(), "busy");
            return;
        }
        DiagWatch watch = diagnostics.watch(
                player.getUniqueId(), generation, leaseSeconds, frameSeq, debug::history, debug::unwatch);
        if (watch == null || watch == debug.watch()) return;
        debug.watch(watch);
        if (watch.ended()) debug.unwatch(watch);
    }

    /** The service asks for a range of {@code player}'s prediction history; a Bedrock player's is refused. */
    public void cut(CultPlayer player, Diagnostics diagnostics, Cut cut) {
        if (player.isBedrockMovement()) diagnostics.refuse(player.getUniqueId(), "bedrock");
        String header;
        try {
            header = header(player);
        } catch (RuntimeException unreadable) {
            header = "";
        }
        diagnostics.cut(player.getUniqueId(), cut, header);
    }

    /** The checks that flagged the frame of seq {@code frameSeq}, in flag order. */
    public void tickEnd(CultPlayer player, long frameSeq, List<String> checks) {
        if (checks.isEmpty()) return;
        SuperDebug debug = player.checkManager.getListener(SuperDebug.class);
        if (debug == null || !(debug.watch() instanceof DiagWatch watch)) return;
        try {
            watch.tickEnd(frameSeq, checks);
        } catch (RuntimeException failure) {
            watch.failed(failure);
        }
    }

    /** {@code player}'s capture ended. */
    public void ended(CultPlayer player) {
        SuperDebug debug = player.checkManager.getListener(SuperDebug.class);
        if (debug != null && debug.watch() instanceof DiagWatch watch) watch.owner.left(watch.player());
    }

    private static String header(CultPlayer player) {
        return header(
                CultAPI.INSTANCE.getExternalAPI().getGrimVersion(),
                player.getClientVersion().getReleaseName(),
                player.getBrand(),
                player.getTransactionPing(),
                serverFields);
    }

    static String header(String agent, String client, String brand, int ping, @Nullable String serverFields) {
        return "agent " + agent + ", client " + client + ", brand " + brand + ", ping " + ping + " ms"
                + (serverFields == null ? "" : ", " + serverFields);
    }

    /** Reads the server and plugin fields once, a tick after it is first called, when every plugin has enabled. */
    public static void readServerFields() {
        if (!readingServerFields.compareAndSet(false, true)) return;
        CultAPI.INSTANCE
                .getScheduler()
                .getGlobalRegionScheduler()
                .runDelayed(
                        CultAPI.INSTANCE.getGrimPlugin(),
                        () -> {
                            try {
                                serverFields = serverFields(
                                        CultAPI.INSTANCE.getPlatformServer(), CultAPI.INSTANCE.getPluginManager());
                            } catch (RuntimeException | LinkageError unreadable) {
                                LogUtil.warn("Could not read the server software and plugins for diagnostics");
                            }
                        },
                        1);
    }

    /**
     * {@code server <software>, plugins <name> <version>, ...} over the enabled {@link #HEADER_PLUGINS}, or
     * {@code plugins none}; commas in the software or a version become semicolons, so the fields split on {@code ", "}.
     */
    static String serverFields(PlatformServer server, PlatformPluginManager plugins) {
        StringBuilder fields = new StringBuilder("server ").append(headerText(software(server)));
        fields.append(", plugins ");
        int listed = 0;
        PlatformPlugin[] installed = plugins.getPlugins();
        for (String wanted : HEADER_PLUGINS) {
            for (PlatformPlugin plugin : installed) {
                if (!plugin.isEnabled() || !wanted.equalsIgnoreCase(plugin.getName())) continue;
                if (listed++ > 0) fields.append(", ");
                fields.append(plugin.getName()).append(' ').append(headerText(plugin.getVersion()));
                break;
            }
        }
        if (listed == 0) fields.append("none");
        return fields.toString();
    }

    private static @Nullable String software(PlatformServer server) {
        ServerFacts facts = server.cloudFacts();
        return facts != null && facts.server() != null ? facts.server() : server.getPlatformImplementationString();
    }

    private static String headerText(@Nullable String text) {
        if (text == null || text.isBlank()) return "unknown";
        return text.replace(',', ';').replace('\r', ' ').replace('\n', ' ').trim();
    }
}
