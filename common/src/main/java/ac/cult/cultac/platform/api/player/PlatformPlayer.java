package ac.cult.cultac.platform.api.player;

import ac.cult.cultac.bridge.protocol.BridgeMessage.ClientMods;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.platform.api.ServerFacts;
import ac.cult.cultac.platform.api.entity.CultEntity;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.ItemUseState;
import ac.cult.cultac.utils.math.Vec3;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

public interface PlatformPlayer extends CultEntity, OfflinePlatformPlayer {
    /**
     * Whether this adapter can read authoritative server state and perform server mutations.
     * Proxy adapters only expose client-visible state. Their server mutation methods are no-ops,
     * and eject/teleport report failure. Callers must not infer server agreement from proxy reads.
     */
    default boolean hasServerAuthority() {
        return true;
    }

    /** Whether this adapter can serve {@code capability} for the player's current server. */
    default boolean hasCapability(ServerCapability capability) {
        return hasServerAuthority();
    }

    void kickPlayer(String textReason);

    boolean isSneaking();

    boolean hasPermission(String s);

    boolean hasPermission(String s, boolean defaultIfUnset);

    void sendMessage(String message);

    void sendMessage(Component message);

    int getPing();

    int getEntityId();

    /** Platform implementation schedules this operation on its owning thread. */
    void closeInventory();

    /** Authoritative item use; unavailable adapters return NONE, not a client-derived guess. */
    ItemUseState getItemUseState();

    /**
     * Ends the server's active use of an item of type {@code itemKey} in {@code hand}, ordered after the packets that
     * started it and before any later use. A platform that cannot order it with the player's packets does nothing, and
     * so does the server when the player is not using such an item in that hand.
     */
    void clearActiveItem(Hand hand, String itemKey);

    Vec3 getPosition();

    @Nullable
    CultEntity getVehicle();

    GameMode getGameMode();

    void setGameMode(GameMode gameMode);

    void sendPluginMessage(String channelName, byte[] byteArray);

    /** This player's settings for its capture's JOIN, such as its attack speed; read on the event loop. */
    default ServerFacts cloudFacts() {
        return ServerFacts.NONE;
    }

    /**
     * Asks the player's current server for the Lunar Client mods its Apollo reports. Completes null when no server
     * serves {@link ServerCapability#APOLLO_MODS} or it does not answer.
     */
    default CompletableFuture<@Nullable ClientMods> requestClientMods() {
        return CompletableFuture.completedFuture(null);
    }
}
