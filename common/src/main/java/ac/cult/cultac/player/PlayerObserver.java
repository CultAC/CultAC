package ac.cult.cultac.player;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.value.GameMode;

/**
 * Follows one player's engine events from {@link CultPlayer#observer}. Each method runs on the player's packet thread
 * and does nothing unless overridden.
 */
public interface PlayerObserver {
    /** A client tick ended, after every tick-end listener ran for it. */
    default void tickEnded() {}

    /** {@code check} flagged; {@code violations} is its violation level. */
    default void flagged(Check check, double violations) {}

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
