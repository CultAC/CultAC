package ac.cult.cultac.platform.bukkit.player;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.entity.CultEntity;
import ac.cult.cultac.platform.api.player.PlatformInventory;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.cult.cultac.platform.bukkit.entity.BukkitCultEntity;
import ac.cult.cultac.platform.bukkit.sender.BukkitComponentSender;
import ac.cult.cultac.platform.bukkit.utils.convert.BukkitConversionUtils;
import ac.cult.cultac.platform.bukkit.utils.reflection.PaperUtils;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.common.arguments.CommonCultArguments;
import ac.cult.cultac.utils.math.Location;
import ac.cult.cultac.utils.math.Vec3;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BukkitPlatformPlayer extends BukkitCultEntity implements PlatformPlayer {
    @Getter
    private final Player bukkitPlayer;

    @Getter
    private final PlatformInventory inventory;

    private final @Nullable User user;

    public BukkitPlatformPlayer(@NotNull Player bukkitPlayer) {
        super(bukkitPlayer);
        this.bukkitPlayer = bukkitPlayer;
        this.inventory = new BukkitPlatformInventory(bukkitPlayer);
        if (CommonCultArguments.USE_CHAT_FAST_BYPASS.value()) {
            this.user = CultAPI.INSTANCE.getNetworkManager().getUser(bukkitPlayer.getUniqueId(), bukkitPlayer);
        } else {
            this.user = null;
        }
    }

    @Override
    public void kickPlayer(String textReason) {
        bukkitPlayer.kickPlayer(textReason);
    }

    @Override
    public boolean hasPermission(String s) {
        return bukkitPlayer.hasPermission(s);
    }

    @Override
    public boolean hasPermission(String s, boolean defaultIfUnset) {
        return this.bukkitPlayer.hasPermission(
                new Permission(s, defaultIfUnset ? PermissionDefault.TRUE : PermissionDefault.FALSE));
    }

    @Override
    public boolean isSneaking() {
        return bukkitPlayer.isSneaking();
    }

    @Override
    public void setSneaking(boolean isSneaking) {
        bukkitPlayer.setSneaking(isSneaking);
    }

    @Override
    public void sendMessage(String message) {
        if (CommonCultArguments.USE_CHAT_FAST_BYPASS.value() && user != null) {
            user.sendMessage(Component.text(message));
        } else {
            bukkitPlayer.sendMessage(message);
        }
    }

    @Override
    public void sendMessage(Component message) {
        if (CommonCultArguments.USE_CHAT_FAST_BYPASS.value() && user != null) {
            user.sendMessage(message);
        } else {
            BukkitComponentSender.sendMessage(bukkitPlayer, message);
        }
    }

    @Override
    public boolean isOnline() {
        return bukkitPlayer.isOnline();
    }

    @Override
    public String getName() {
        return bukkitPlayer.getName();
    }

    @Override
    public void updateInventory() {
        bukkitPlayer.updateInventory();
    }

    @Override
    public int getPing() {
        return bukkitPlayer.getPing();
    }

    @Override
    public int getEntityId() {
        return bukkitPlayer.getEntityId();
    }

    @Override
    public void closeInventory() {
        ac.cult.cultac.network.protocol.util.FoliaCompatUtil.runTaskForEntity(
                bukkitPlayer, CultACBukkitLoaderPlugin.LOADER, bukkitPlayer::closeInventory, null, 0);
    }

    @Override
    public ac.cult.cultac.protocol.value.ItemUseState getItemUseState() {
        return BukkitItemUseState.read(bukkitPlayer);
    }

    @Override
    public void clearActiveItem() {
        bukkitPlayer.clearActiveItem();
    }

    @Override
    public void resendBlocks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        BukkitBlockResync.resend(bukkitPlayer, minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public Vec3 getPosition() {
        if (CAN_USE_DIRECT_GETTERS) {
            return new Vec3(this.bukkitPlayer.getX(), this.bukkitPlayer.getY(), this.bukkitPlayer.getZ());
        } else {
            org.bukkit.Location location = this.bukkitPlayer.getLocation();
            return new Vec3(location.getX(), location.getY(), location.getZ());
        }
    }

    @Override
    public @Nullable CultEntity getVehicle() {
        return bukkitPlayer.getVehicle() == null ? null : new BukkitCultEntity(bukkitPlayer.getVehicle());
    }

    @Override
    public GameMode getGameMode() {
        return GameMode.valueOf(bukkitPlayer.getGameMode().name());
    }

    @Override
    public void setGameMode(GameMode gameMode) {
        bukkitPlayer.setGameMode(org.bukkit.GameMode.valueOf(gameMode.name()));
    }

    public World getBukkitWorld() {
        return bukkitPlayer.getWorld();
    }

    @Override
    public UUID getUniqueId() {
        return bukkitPlayer.getUniqueId();
    }

    @Override
    public boolean eject() {
        return bukkitPlayer.eject();
    }

    @Override
    public CompletableFuture<Boolean> teleportAsync(Location location) {
        org.bukkit.Location bLoc = BukkitConversionUtils.toBukkitLocation(location);
        return PaperUtils.teleportAsync(this.bukkitPlayer, bLoc);
    }

    @Override
    public void sendPluginMessage(String channelName, byte[] byteArray) {
        this.bukkitPlayer.sendPluginMessage(CultACBukkitLoaderPlugin.LOADER, channelName, byteArray);
    }

    @Override
    public Sender getSender() {
        return CultACBukkitLoaderPlugin.LOADER.getBukkitSenderFactory().map(this.bukkitPlayer);
    }

    @Override
    @NotNull
    public Player getNative() {
        return this.bukkitPlayer;
    }
}
