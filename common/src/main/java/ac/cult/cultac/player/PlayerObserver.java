package ac.cult.cultac.player;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.manager.player.setback.SetbackReason;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.value.GameMode;
import java.util.function.Supplier;

/**
 * Follows one player's engine events from {@link CultPlayer#observer}. Each method runs on the player's packet thread
 * and does nothing unless overridden.
 */
public interface PlayerObserver {
    /** A client tick ended, after every tick-end listener ran for it. */
    default void tickEnded() {}

    /** Server tick {@code serverTick} ended. Runs at the tick end's outbound position, before any ping it writes. */
    default void serverTickEnded(int serverTick) {}

    /** {@code check} flagged; {@code violations} is its violation level. */
    default void flagged(Check check, double violations) {}

    /** As {@link #flagged(Check, double)}, with the flag's verbose, rendered only if read. */
    default void flagged(Check check, double violations, Supplier<String> verbose) {
        flagged(check, violations);
    }

    /**
     * Whether flags of {@code check} should give this observer a verbose that no other consumer reads; asked before any
     * of it is built.
     */
    default boolean recordsVerbose(Check check) {
        return false;
    }

    /**
     * A setback teleport was written to the client for {@code reason}; {@code cause} is the reason it gave, and
     * {@code check} the check a violation setback names, either null.
     */
    default void setbackSent(SetbackReason reason, String cause, Check check) {}

    /** The client is being sent its play {@code login}. */
    default void loginSent(ClientboundLogin packet) {}

    /** The client is being sent a {@code respawn}. */
    default void respawnSent(ClientboundRespawn packet) {}

    /** The client is being sent a game mode change. */
    default void gameModeSent(GameMode mode) {}

    /** The client is being sent {@code start_configuration}: it leaves play. */
    default void configurationStarted() {}

    /** CultAC handled a serverbound packet; {@code cancelled} when it drops it before the server sees it. */
    default void received(ServerboundPacket packet, boolean cancelled) {}

    /** CultAC handled a clientbound packet the server sent that goes on to the client. */
    default void sent(Object packet) {}

    /** The client is being sent the totem of undying's use on its own entity. */
    default void totemPopped() {}
}
