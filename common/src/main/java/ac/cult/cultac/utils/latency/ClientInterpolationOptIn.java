package ac.cult.cultac.utils.latency;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCustomPayload;
import java.nio.charset.StandardCharsets;

/**
 * Whether new living entities interpolate linearly for this client: CultAC offered {@link #CHANNEL} before the
 * play login and the client declared it with minecraft:register. Both reset when the client reconfigures.
 *
 * <p>An entity's handler is fixed at creation; {@link #declare()} reports when the session's existing entities take
 * the linear handler as well.
 */
public final class ClientInterpolationOptIn {
    /** The channel AntiStutter and Interpolation Fix declare when they switch living entities to linear lerps. */
    public static final String CHANNEL = "antistutter:linear_3";
    /** Interpolation Fix's own channel; CultAC never offers it. */
    public static final String INTERPOLATION_FIX_CHANNEL = "interpolationfix:linear_3";

    // Written and read on the send path only.
    private boolean offerSent;
    private boolean offered;
    private boolean declared;
    private boolean ticked;
    private boolean existingEntitiesFollow;

    /** Whether CultAC offers {@link #CHANNEL} before the play login it is sending this client now. */
    public static boolean offeredAtLogin(CultPlayer player) {
        return CultAPI.INSTANCE.getPlatform() == Platform.VELOCITY
                && CultAPI.INSTANCE.getConfigManager().isLinearInterpolationOptIn()
                && !player.isBedrockMovement()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3);
    }

    /** CultAC's offer, written just before the play login. */
    public static ClientboundCustomPayload offer() {
        return new ClientboundCustomPayload(
                ClientboundPackets.REGISTER_CHANNEL, CHANNEL.getBytes(StandardCharsets.UTF_8));
    }

    /** CultAC is sending a play login preceded by its offer, or by none. */
    public void loginSent(boolean offered) {
        offerSent = offered;
    }

    /** The client handled a play login preceded by CultAC's offer, or by none. */
    public void loginHandled(boolean offered) {
        this.offered = offered;
        existingEntitiesFollow = offered && !ticked && !declared;
    }

    /** The client registered {@link #CHANNEL} in play; true when the session's existing entities change handler. */
    public boolean declare() {
        declared = true;
        boolean existingFollow = existingEntitiesFollow;
        existingEntitiesFollow = false;
        return existingFollow;
    }

    /** The client unregistered {@link #CHANNEL} in play. */
    public void undeclare() {
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
