package ac.cult.cultac.platform.api.player;

import ac.cult.cultac.platform.api.entity.CultEntity;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
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

    void kickPlayer(String textReason);

    boolean isSneaking();

    void setSneaking(boolean b);

    boolean hasPermission(String s);

    boolean hasPermission(String s, boolean defaultIfUnset);

    void sendMessage(String message);

    void sendMessage(Component message);

    void updateInventory();

    int getPing();

    int getEntityId();

    /** Platform implementation schedules this operation on its owning thread. */
    void closeInventory();

    /** Authoritative item use; unavailable adapters return NONE, not a client-derived guess. */
    ac.cult.cultac.protocol.value.ItemUseState getItemUseState();

    void clearActiveItem();

    /** Resends authoritative loaded blocks without loading chunks. Bounds are inclusive. */
    void resendBlocks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);

    Vec3 getPosition();

    PlatformInventory getInventory();

    @Nullable
    CultEntity getVehicle();

    GameMode getGameMode();

    void setGameMode(GameMode gameMode);

    void sendPluginMessage(String channelName, byte[] byteArray);

    Sender getSender();

    /*
     * Replaces native player reference in PlatformPlayer implementation with a new object
     * Vanilla MC replaces ServerPlayerEntity references on respawn and dimension change
     */
    default void replaceNativePlayer(Object nativePlayerObject) {}
}
