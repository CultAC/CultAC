package ac.cult.cultac.utils.latency;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCustomPayload;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Whether new living entities interpolate linearly for this client: CultAC offered the session's channels before the
 * play login and the client declared one of them with minecraft:register. Both reset when the client reconfigures.
 *
 * <p>An entity's handler is fixed at creation; {@link #declare()} reports when the session's existing entities take
 * the linear handler as well.
 */
public final class ClientInterpolationOptIn {
    /** The channel AntiStutter and Interpolation Fix declare when they switch living entities to linear lerps. */
    public static final String CHANNEL = "antistutter:linear_3";
    /** Interpolation Fix's own channel; CultAC never offers it. */
    public static final String INTERPOLATION_FIX_CHANNEL = "interpolationfix:linear_3";
    /** The channels offered when none are configured. */
    public static final List<String> DEFAULT_CHANNELS = List.of(CHANNEL);
    /** Configured channels beyond this many are left out. */
    public static final int MAX_CHANNELS = 8;

    private static final String CHANNELS_KEY = "linear-interpolation-channels";
    private static final Pattern CHANNEL_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    // Written and read on the send path only.
    private boolean offerSent;
    private boolean offered;
    private boolean declared;
    private boolean ticked;
    private boolean existingEntitiesFollow;
    private @Nullable List<String> sessionChannels;
    private final Set<String> declaredChannels = new HashSet<>();

    /** Whether CultAC offers the session's channels before the play login it is sending this client now. */
    public static boolean offeredAtLogin(CultPlayer player) {
        return CultAPI.INSTANCE.getPlatform() == Platform.VELOCITY
                && CultAPI.INSTANCE.getConfigManager().isLinearInterpolationOptIn()
                && !player.isBedrockMovement()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3);
    }

    /**
     * The channel ids of {@code entries} CultAC offers, in order; {@code warn} gets one line per entry left out. None
     * configured, or none left, gives {@link #DEFAULT_CHANNELS}.
     */
    public static List<String> configuredChannels(@Nullable Collection<?> entries, Consumer<String> warn) {
        if (entries == null) return DEFAULT_CHANNELS;
        Set<String> channels = new LinkedHashSet<>();
        for (Object entry : entries) {
            String channel = entry == null ? "" : entry.toString().strip();
            String reason;
            if (!CHANNEL_ID.matcher(channel).matches()) {
                reason = "is not a namespace:path channel id";
            } else if (channel.startsWith("minecraft:")) {
                reason = "is in the minecraft namespace";
            } else if (channel.equals(ClientboundPackets.BRIDGE_CHANNEL)) {
                reason = "is CultAC's bridge channel";
            } else if (channel.equals(INTERPOLATION_FIX_CHANNEL)) {
                reason = "is never offered";
            } else if (channels.contains(channel)) {
                continue;
            } else if (channels.size() >= MAX_CHANNELS) {
                reason = "comes after the first " + MAX_CHANNELS + " channels";
            } else {
                channels.add(channel);
                continue;
            }
            warn.accept(CHANNELS_KEY + " lists \"" + channel + "\", which " + reason + "; it is left out");
        }
        return channels.isEmpty() ? DEFAULT_CHANNELS : List.copyOf(channels);
    }

    /** CultAC's offer of the session's channels, written just before the play login. */
    public ClientboundCustomPayload offer() {
        return new ClientboundCustomPayload(
                ClientboundPackets.REGISTER_CHANNEL,
                String.join("\0", sessionChannels()).getBytes(StandardCharsets.UTF_8));
    }

    /** CultAC is sending a play login preceded by its offer, or by none. */
    public void loginSent(boolean offered) {
        offerSent = offered;
        sessionChannels();
    }

    /**
     * The configured channels of this play session: the list in effect when its login was sent, or when the client
     * registered a channel in play before that.
     */
    public List<String> sessionChannels() {
        if (sessionChannels == null) {
            sessionChannels = CultAPI.INSTANCE.getConfigManager().getLinearInterpolationChannels();
        }
        return sessionChannels;
    }

    /** The session's channels once a login or a play registration fixed them, else null. */
    public @Nullable List<String> sessionChannelsIfFixed() {
        return sessionChannels;
    }

    /**
     * The client registered or unregistered {@code channel} in play; true when the session's existing entities change
     * handler.
     */
    public boolean channelChanged(String channel, boolean registered) {
        if (!sessionChannels().contains(channel)) return false;
        if (registered) return declaredChannels.add(channel) && declaredChannels.size() == 1 && declare();
        if (declaredChannels.remove(channel) && declaredChannels.isEmpty()) undeclare();
        return false;
    }

    /** The client handled a play login preceded by CultAC's offer, or by none. */
    public void loginHandled(boolean offered) {
        this.offered = offered;
        existingEntitiesFollow = offered && !ticked && !declared;
    }

    /** The client declared linear interpolation; true when the session's existing entities change handler. */
    private boolean declare() {
        declared = true;
        boolean existingFollow = existingEntitiesFollow;
        existingEntitiesFollow = false;
        return existingFollow;
    }

    /** The client withdrew its declaration. */
    private void undeclare() {
        declared = false;
    }

    public void clientTickEnded() {
        ticked = true;
        existingEntitiesFollow = false;
    }

    /** The client left play for configuration; its next play login decides the offer again. */
    public void configurationAcknowledged() {
        offered = false;
        declared = false;
        ticked = false;
        existingEntitiesFollow = false;
        sessionChannels = null;
        declaredChannels.clear();
    }

    /** Whether the play login last sent to this client was preceded by CultAC's offer. */
    public boolean isOfferSent() {
        return offerSent;
    }

    public boolean isOffered() {
        return offered;
    }

    public boolean isDeclared() {
        return declared;
    }

    /** Whether a living entity created now uses vanilla's linear interpolation handler. */
    public boolean linearForNewEntities() {
        return offered && declared;
    }
}
