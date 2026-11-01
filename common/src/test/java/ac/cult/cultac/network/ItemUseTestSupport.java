package ac.cult.cultac.network;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.checks.ItemUseAnalyzer;
import ac.cult.cultac.checks.impl.prediction.checks.NoSlow;
import ac.cult.cultac.events.packets.PacketPlayerAbilities;
import ac.cult.cultac.events.packets.blockplace.PlaceHandler;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerAbilities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHealth;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.ItemUseInventory;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;

/** A 26.3 client on a bound connection, and the packets, pings, uses and walking ticks the item-use tests drive. */
final class ItemUseTestSupport {
    private static final double SPRINT_INPUT_SPEED = 0.13;
    private static final double WALK = 0.98 * 0.1;

    private ItemUseTestSupport() {}

    static RecordReceiveFixture fixture() {
        OfflineCultTestBootstrap.installConfig();
        var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
        fixture.user.bind(mock(PlatformPlayer.class));
        return fixture;
    }

    /** The pings written before and after a packet, as transaction numbers. */
    record Sent(PacketSendEvent<?> event, int preceding, int after) {
        /** Runs the event's after-send tasks and records the pings written since {@code start}. */
        static Sent of(CultPlayer player, PacketSendEvent<?> event, int start) {
            event.tasksAfter().forEach(Runnable::run);
            return new Sent(event, start + 1, player.lastTransactionSent.get());
        }
    }

    /** The client answered the ping written ahead of the packet; the packet itself is still in flight. */
    static void ackPrecedingPing(CultPlayer player, Sent sent) {
        acknowledge(player, sent.preceding());
    }

    static void ackTrailingProof(CultPlayer player, Sent sent) {
        acknowledge(player, sent.after());
    }

    static void acknowledge(CultPlayer player, int transaction) {
        if (player.lastTransactionReceived.get() >= transaction) return;
        player.lastTransactionReceived.set(transaction);
        player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    static <R> PacketSendEvent<R> sendEvent(CultPlayer player, PacketType<R> type, R packet) {
        return new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, type, packet, false);
    }

    static void setOffhand(CultPlayer player, SimItemStack stack) {
        player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, stack);
    }

    static Sent abilities(CultPlayer player, boolean invulnerable, boolean instabuild) {
        var packet = new ClientboundPlayerAbilities(invulnerable, false, false, instabuild, 0.05F, 0.1F);
        var event = sendEvent(player, ClientboundPackets.PLAYER_ABILITIES, packet);
        int start = player.lastTransactionSent.get();
        player.checkManager.getListener(PacketPlayerAbilities.class).onPlayerAbilities(event, player, packet);
        return Sent.of(player, event, start);
    }

    static Sent health(CultPlayer player, int food) {
        return health(player, 20F, food);
    }

    static Sent health(CultPlayer player, float health, int food) {
        var packet = new ClientboundSetHealth(health, food, 5F);
        var event = sendEvent(player, ClientboundPackets.SET_HEALTH, packet);
        int start = player.lastTransactionSent.get();
        new PacketPlayerRespawn().onSetHealth(event, player, packet);
        return Sent.of(player, event, start);
    }

    /** Sends a Login and returns the last transaction written, leaving it unacknowledged. */
    static int login(CultPlayer player, GameMode mode) {
        int overworld = ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:overworld");
        var login = new ClientboundLogin(8, true, new PlayerSpawnInfo(overworld, "minecraft:overworld", mode));
        var event = sendEvent(player, ClientboundPackets.LOGIN, login);
        new PacketPlayerRespawn().onLogin(event, player, login);
        event.tasksAfter().forEach(Runnable::run);
        return player.lastTransactionSent.get();
    }

    /** Sends a Respawn and returns the first transaction written for it, unacknowledged. */
    static int respawn(CultPlayer player, String dimension, GameMode mode) {
        int type = ModelRegistryNamesState.defaults().id("minecraft:dimension_type", dimension);
        var respawn = new ClientboundRespawn(new PlayerSpawnInfo(type, dimension, mode), (byte) 0);
        int post = player.lastTransactionSent.get() + 1;
        new PacketPlayerRespawn().onRespawn(sendEvent(player, ClientboundPackets.RESPAWN, respawn), player, respawn);
        return post;
    }

    static void useOffhand(CultPlayer player, int sequence) {
        use(player, Hand.OFF_HAND, sequence);
    }

    static void use(CultPlayer player, Hand hand, int sequence) {
        player.packetStateData.clientSidePosition = new Vec3(player.x, player.y, player.z);
        player.compensatedWorld.advanceClientPredictionSequence();
        PlaceHandler.handleQueuedUseItem(player, new ServerboundUseItem(hand, sequence, player.xRot, player.yRot));
    }

    /** One unslowed walking tick through the item-use analyzer and NoSlow; returns whether NoSlow flagged it. */
    static boolean noSlowFlags(CultPlayer player) {
        var check = player.checkManager.getListener(NoSlow.class);
        double before = check.violations;
        player.packetStateData.acceptedClientTick++;
        var snapshot = ItemUseInventory.prepare(player);
        var context = mock(SimulationContext.class);
        when(context.getItemUse()).thenReturn(snapshot);
        when(context.getVersion()).thenReturn(ClientVersion.V_26_3);
        when(context.getMaxSprintInputSpeed(any(), any())).thenReturn(SPRINT_INPUT_SPEED);
        var realities = new ArrayList<PredictionResult>();
        var result = new PredictionResult(
                player, new PredVector(Vec3.ZERO), context, new Vec3(0, 0, WALK), null, realities, realities);
        result.setProfilePredictionVector(Vec3.ZERO);
        realities.add(result);
        new ItemUseAnalyzer().handleResult(player, result, null);
        check.onPredictionComplete(new PredictionComplete(result));
        return check.violations > before;
    }

    /** Twenty unslowed walking ticks; returns whether NoSlow flagged any of them. */
    static boolean walkingFlagsNoSlow(CultPlayer player) {
        boolean flagged = false;
        for (int i = 0; i < 20; i++) flagged |= noSlowFlags(player);
        return flagged;
    }
}
