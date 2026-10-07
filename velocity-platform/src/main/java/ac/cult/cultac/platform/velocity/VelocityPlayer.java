package ac.cult.cultac.platform.velocity;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.platform.api.entity.CultEntity;
import ac.cult.cultac.platform.api.player.PlatformInventory;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.ItemUseState;
import ac.cult.cultac.utils.math.Location;
import ac.cult.cultac.utils.math.Vec3;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

final class VelocityPlayer implements PlatformPlayer {
    private final Player player;
    private final VelocitySenders senders;
    private volatile CultConnection connection;
    private volatile VelocityWorld world;
    private final PlatformInventory inventory = new PlatformInventory() {
        @Override
        public SimItemStack getStack(int bukkitSlot, int vanillaSlot) {
            var actor = connection == null ? null : connection.player();
            return actor == null
                    ? SimItemStack.EMPTY
                    : actor.getInventory()
                            .inventory
                            .getInventoryStorage()
                            .getItem(vanillaSlot)
                            .copy();
        }

        @Override
        public SimItemStack getMainHand() {
            var actor = connection == null ? null : connection.player();
            return actor == null
                    ? SimItemStack.EMPTY
                    : actor.getInventory().getHeldItem().copy();
        }

        @Override
        public SimItemStack getOffHand() {
            var actor = connection == null ? null : connection.player();
            return actor == null
                    ? SimItemStack.EMPTY
                    : actor.getInventory().getOffHand().copy();
        }
    };

    VelocityPlayer(Player player, VelocitySenders senders) {
        this.player = player;
        this.senders = senders;
    }

    void attach(CultConnection connection) {
        this.connection = connection;
    }

    CultConnection connection() {
        return Objects.requireNonNull(connection, "Player transport is not attached");
    }

    CultPlayer observed() {
        return connection == null ? null : connection.player();
    }

    @Override
    public boolean hasServerAuthority() {
        return false;
    }

    @Override
    public UUID getUniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String getName() {
        return player.getUsername();
    }

    @Override
    public Player getNative() {
        return player;
    }

    @Override
    public boolean isOnline() {
        return player.isActive();
    }

    @Override
    public void kickPlayer(String reason) {
        player.disconnect(LegacyComponentSerializer.legacySection().deserialize(reason));
    }

    @Override
    public void sendMessage(String message) {
        getSender().sendMessage(message);
    }

    @Override
    public void sendMessage(Component message) {
        player.sendMessage(message);
    }

    @Override
    public Sender getSender() {
        return senders.wrap(player);
    }

    @Override
    public int getPing() {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, player.getPing()));
    }

    @Override
    public boolean hasPermission(String node) {
        return getSender().hasPermission(node);
    }

    @Override
    public boolean hasPermission(String node, boolean fallback) {
        return VelocityPermissions.has(player, node, fallback);
    }

    @Override
    public int getEntityId() {
        var state = observed();
        return state == null || state.world == null ? -1 : state.entityID;
    }

    @Override
    public boolean isSneaking() {
        var state = observed();
        return state != null && state.isSneaking;
    }

    @Override
    public boolean isDead() {
        var state = observed();
        return state != null && state.isDead();
    }

    @Override
    public GameMode getGameMode() {
        var state = observed();
        return state == null || state.gamemode == null ? GameMode.SURVIVAL : state.gamemode;
    }

    @Override
    public Vec3 getPosition() {
        var state = observed();
        return state == null ? Vec3.ZERO : new Vec3(state.x, state.y, state.z);
    }

    @Override
    public Location getLocation() {
        var state = observed();
        return state == null
                ? new Location(getWorld(), 0, 0, 0)
                : new Location(getWorld(), state.x, state.y, state.z, state.yRot, state.xRot);
    }

    @Override
    public double distanceSquared(double x, double y, double z) {
        return getPosition().distanceToSqr(x, y, z);
    }

    @Override
    public VelocityWorld getWorld() {
        var state = observed();
        var current = world;
        var server = player.getCurrentServer().orElse(null);
        String dimension = state == null ? null : state.world;
        if (current == null || !current.matches(server, dimension)) {
            world = current = new VelocityWorld(this, server, dimension);
        }
        return current;
    }

    @Override
    public PlatformInventory getInventory() {
        return inventory;
    }

    @Override
    public ItemUseState getItemUseState() {
        // Client intent and compensated metadata cannot prove server-side item use.
        return ItemUseState.NONE;
    }

    @Override
    public CultEntity getVehicle() {
        // Packet vehicle prediction remains in CompensatedEntities. There is no native entity API.
        return null;
    }

    @Override
    public void updateInventory() {
        // No authoritative inventory to resend.
    }

    @Override
    public void closeInventory() {
        // Closing only the client would leave the server's container open.
    }

    @Override
    public void clearActiveItem() {
        // No server-side item-use mutation API.
    }

    @Override
    public void setSneaking(boolean value) {
        // No server-side pose mutation API.
    }

    @Override
    public void setGameMode(GameMode mode) {
        // A client-only game-mode change would desynchronize server permissions and abilities.
    }

    @Override
    public boolean eject() {
        return false;
    }

    @Override
    public void resendBlocks(int x1, int y1, int z1, int x2, int y2, int z2) {
        // Compensated blocks describe the client view, not an authoritative correction.
    }

    @Override
    public CompletableFuture<Boolean> teleportAsync(Location location) {
        // Packet setbacks have their own acknowledgement path; administrative teleports do not.
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public void sendPluginMessage(String channel, byte[] payload) {
        player.sendPluginMessage(MinecraftChannelIdentifier.from(channel), payload.clone());
    }
}
