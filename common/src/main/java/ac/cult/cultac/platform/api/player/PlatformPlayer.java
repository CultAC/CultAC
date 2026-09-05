package ac.cult.cultac.platform.api.player;

import ac.cult.cultac.platform.api.entity.CultEntity;
import ac.cult.cultac.platform.api.sender.Sender;
import net.kyori.adventure.text.Component;
import net.minecraft.world.phys.Vec3;
import org.bukkit.GameMode;
import org.jetbrains.annotations.Nullable;

public interface PlatformPlayer extends CultEntity, OfflinePlatformPlayer {
    void kickPlayer(String textReason);

    boolean isSneaking();

    void setSneaking(boolean b);

    boolean hasPermission(String s);

    boolean hasPermission(String s, boolean defaultIfUnset);

    void sendMessage(String message);

    void sendMessage(Component message);

    void updateInventory();

    Vec3 getPosition();

    PlatformInventory getInventory();

    @Nullable CultEntity getVehicle();

    GameMode getGameMode();

    void setGameMode(GameMode gameMode);

    boolean isExternalPlayer();

    void sendPluginMessage(String channelName, byte[] byteArray);

    Sender getSender();

    /*
     * Replaces native player reference in PlatformPlayer implementation with a new object
     * Vanilla MC replaces ServerPlayerEntity references on respawn and dimension change
     */
    default void replaceNativePlayer(Object nativePlayerObject) {}
}
