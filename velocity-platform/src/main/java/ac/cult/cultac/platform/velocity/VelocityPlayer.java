package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.bridge.protocol.BridgeMessage.ClearActiveItem;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CloseInventory;
import ac.cult.cultac.bridge.protocol.BridgeMessage.SetGameMode;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Teleport;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.platform.api.entity.CultEntity;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.platform.velocity.bridge.BackendWorld;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.platform.velocity.bridge.VelocityBridge;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundContainerClose;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
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
import org.jetbrains.annotations.Nullable;

final class VelocityPlayer implements PlatformPlayer {
    private static final long TELEPORT_TIMEOUT_MILLIS = 5000;

    private final Player player;
    private final UUID id;
    private final VelocitySenders senders;
    private volatile CultConnection connection;
    private volatile VelocityWorld world;

    VelocityPlayer(Player player, VelocitySenders senders) {
        this.player = player;
        this.id = player.getUniqueId();
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
    public boolean hasCapability(ServerCapability capability) {
        return bridge(capability) != null;
    }

    /** The player's READY bridge session when it serves {@code capability}. */
    @Nullable
    BridgeSession bridge(ServerCapability capability) {
        var session = VelocityBridge.sessions().current(id);
        return session != null && session.isReady() && session.has(capability) ? session : null;
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
    public void closeInventory() {
        var session = bridge(ServerCapability.CLOSE_INVENTORY);
        if (session != null) {
            session.send(new CloseInventory());
            return;
        }
        CultConnection current = connection;
        if (current == null) return;
        current.execute(() -> {
            CultPlayer target = current.player();
            if (target == null || current.phase(PacketDirection.SERVERBOUND) != ConnectionPhase.PLAY) return;
            int window = target.getInventory().serverWindowId();
            // ServerPlayer#closeContainer closes the client screen and the server's container. The proxy
            // does both as the client would: the close the client sees, then the close the server handles.
            target.user.write(new ClientboundContainerClose(window));
            VelocityServerbound.inject(
                    current, ServerboundPackets.CONTAINER_CLOSE, new ServerboundContainerClose(window));
        });
    }

    @Override
    public void clearActiveItem(Hand hand, String itemKey) {
        var session = bridge(ServerCapability.CLEAR_ACTIVE_ITEM);
        if (session == null) return;
        int wire = hand == Hand.OFF_HAND ? ClearActiveItem.OFF_HAND : ClearActiveItem.MAIN_HAND;
        session.send(new ClearActiveItem(wire, itemKey));
    }

    @Override
    public void setGameMode(GameMode mode) {
        var session = bridge(ServerCapability.GAMEMODE);
        if (session == null) return;
        int wire = switch (mode) {
            case SURVIVAL -> SetGameMode.SURVIVAL;
            case CREATIVE -> SetGameMode.CREATIVE;
            case ADVENTURE -> SetGameMode.ADVENTURE;
            case SPECTATOR -> SetGameMode.SPECTATOR;
        };
        session.send(new SetGameMode(wire));
    }

    @Override
    public boolean eject() {
        return false;
    }

    @Override
    public CompletableFuture<Boolean> teleportAsync(Location location) {
        var session = bridge(ServerCapability.TELEPORT);
        if (session == null) return CompletableFuture.completedFuture(false);
        var current = player.getCurrentServer().orElse(null);
        UUID world = location.getWorld() instanceof VelocityWorld target
                ? target.uidOn(current)
                : location.getWorld() instanceof BackendWorld backend ? backend.uidOn(current) : null;
        if (world == null) return CompletableFuture.completedFuture(false);
        return session.request(
                requestId -> new Teleport(
                        requestId,
                        world,
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch()),
                TELEPORT_TIMEOUT_MILLIS);
    }

    @Override
    public void sendPluginMessage(String channel, byte[] payload) {
        player.sendPluginMessage(MinecraftChannelIdentifier.from(channel), payload.clone());
    }
}
