package ac.cult.cultac.platform.bukkit.player;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.ServerFacts;
import ac.cult.cultac.platform.api.entity.CultEntity;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.cult.cultac.platform.bukkit.entity.BukkitCultEntity;
import ac.cult.cultac.platform.bukkit.sender.BukkitComponentSender;
import ac.cult.cultac.platform.bukkit.utils.convert.BukkitConversionUtils;
import ac.cult.cultac.platform.bukkit.utils.reflection.PaperUtils;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.common.arguments.CommonCultArguments;
import ac.cult.cultac.utils.math.Location;
import ac.cult.cultac.utils.math.Vec3;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BukkitPlatformPlayer extends BukkitCultEntity implements PlatformPlayer {
    /** The attack speed base at and above which a server runs 1.8-style combat. */
    private static final double LEGACY_ATTACK_SPEED = 16;

    @Getter
    private final Player bukkitPlayer;

    private volatile ServerFacts cloudFacts = ServerFacts.NONE;

    private final @Nullable User user;

    public BukkitPlatformPlayer(@NotNull Player bukkitPlayer) {
        super(bukkitPlayer);
        this.bukkitPlayer = bukkitPlayer;
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
    public void clearActiveItem(Hand hand, String itemKey) {
        // A main-thread clear is not ordered with the client's packets and could end a later legitimate use.
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
        return CultACBukkitLoaderPlugin.INSTANCE.getBukkitSenderFactory().map(this.bukkitPlayer);
    }

    @Override
    @NotNull
    public Player getNative() {
        return this.bukkitPlayer;
    }

    /** The facts {@link #readCloudFacts()} last read; NONE before the first read. Any thread. */
    @Override
    public ServerFacts cloudFacts() {
        return cloudFacts;
    }

    /** Reads this player's max no-damage ticks and attack speed; on the server thread. Whether they changed. */
    public boolean readCloudFacts() {
        Map<String, String> settings = new LinkedHashMap<>();
        try {
            settings.put("max-no-damage-ticks", String.valueOf(bukkitPlayer.getMaximumNoDamageTicks()));
            Attribute attackSpeed = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("attack_speed"));
            AttributeInstance instance = attackSpeed == null ? null : bukkitPlayer.getAttribute(attackSpeed);
            if (instance != null) combatSettings(settings, instance.getBaseValue());
        } catch (RuntimeException | LinkageError unreadable) {
        }
        ServerFacts read = ServerFacts.settings(settings);
        if (read.equals(cloudFacts)) return false;
        cloudFacts = read;
        return true;
    }

    /** The settings an attack speed base implies: the base itself, and 1.8-style combat at and above 16. */
    private static void combatSettings(Map<String, String> settings, double attackSpeedBase) {
        settings.put("attack-speed-base", String.valueOf(attackSpeedBase));
        settings.put("combat-mode", attackSpeedBase >= LEGACY_ATTACK_SPEED ? "1.8" : "1.9");
    }
}
