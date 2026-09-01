package ac.grim.grimac.platform.bukkit.player;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.network.protocol.player.User;
import ac.grim.grimac.platform.api.entity.GrimEntity;
import ac.grim.grimac.platform.api.player.PlatformInventory;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin;
import ac.grim.grimac.platform.bukkit.entity.BukkitGrimEntity;
import ac.grim.grimac.platform.bukkit.sender.BukkitComponentSender;
import ac.grim.grimac.platform.bukkit.utils.convert.BukkitConversionUtils;
import ac.grim.grimac.platform.bukkit.utils.reflection.PaperUtils;
import ac.grim.grimac.utils.anticheat.MultiLibUtil;
import ac.grim.grimac.utils.common.arguments.CommonGrimArguments;
import ac.grim.grimac.utils.math.Location;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.minecraft.world.phys.Vec3;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BukkitPlatformPlayer extends BukkitGrimEntity implements PlatformPlayer {
    @Getter
    private final Player bukkitPlayer;
    @Getter
    private final PlatformInventory inventory;

    private final @Nullable User user;

    public BukkitPlatformPlayer(@NotNull Player bukkitPlayer) {
        super(bukkitPlayer);
        this.bukkitPlayer = bukkitPlayer;
        this.inventory = new BukkitPlatformInventory(bukkitPlayer);
        if (CommonGrimArguments.USE_CHAT_FAST_BYPASS.value()) {
            this.user = GrimAPI.INSTANCE.getPlayerDataManager().getUser(bukkitPlayer);
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
        return this.bukkitPlayer.hasPermission(new Permission(s, defaultIfUnset ? PermissionDefault.TRUE : PermissionDefault.FALSE));
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
        if (CommonGrimArguments.USE_CHAT_FAST_BYPASS.value() && user != null) {
            user.sendMessage(Component.text(message));
        } else {
            bukkitPlayer.sendMessage(message);
        }
    }

    @Override
    public void sendMessage(Component message) {
        if (CommonGrimArguments.USE_CHAT_FAST_BYPASS.value() && user != null) {
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
    public Vec3 getPosition() {
        if (CAN_USE_DIRECT_GETTERS) {
            return new Vec3(this.bukkitPlayer.getX(), this.bukkitPlayer.getY(), this.bukkitPlayer.getZ());
        } else {
            org.bukkit.Location location = this.bukkitPlayer.getLocation();
            return new Vec3(location.getX(), location.getY(), location.getZ());
        }
    }

    @Override
    public @Nullable GrimEntity getVehicle() {
        return bukkitPlayer.getVehicle() == null ? null : new BukkitGrimEntity(bukkitPlayer.getVehicle());
    }

    @Override
    public GameMode getGameMode() {
        return bukkitPlayer.getGameMode();
    }

    @Override
    public void setGameMode(GameMode gameMode) {
        bukkitPlayer.setGameMode(gameMode);
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
    public boolean isExternalPlayer() {
        return MultiLibUtil.isExternalPlayer(this.bukkitPlayer);
    }

    @Override
    public void sendPluginMessage(String channelName, byte[] byteArray) {
        this.bukkitPlayer.sendPluginMessage(GrimACBukkitLoaderPlugin.LOADER, channelName, byteArray);
    }

    @Override
    public Sender getSender() {
        return GrimACBukkitLoaderPlugin.LOADER.getBukkitSenderFactory().map(this.bukkitPlayer);
    }

    @Override
    @NotNull
    public Player getNative() {
        return this.bukkitPlayer;
    }
}
