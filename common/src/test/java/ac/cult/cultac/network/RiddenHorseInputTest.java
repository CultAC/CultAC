package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.events.packets.listeners.PacketPlayerSteer;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A rider steering a horse. Each scenario is one recorded 1.21.11 client's flagged tick: the horse's position, yaw, carried
 * velocity and speed, the input of the tick before (the root ticks before LocalPlayer#rideTick, so it steers this
 * tick), and the move the client sent.
 */
class RiddenHorseInputTest {
    private static final int VEHICLE = 100363468;
    private static final int RIGHT = 8;
    private static final int SPRINT = 64;

    /**
     * Right held, a carried x of 0.0028 under the 0.003 cutoff. It flagged
     * VehicleOffset (-0.005522, 0, 0).
     */
    private static final Scenario CARRIED_X_UNDER_THE_CUTOFF = new Scenario(
            new Vec3(-194.38096210186023D, 68.0D, -32.982461646841855D),
            new Vec3(-194.3864843844727D, 68.0D, -32.71091966165566D),
            new Vec3(0.00284558810627885D, -0.0784000015258789D, 0.12429823576066554D),
            -87.8509521484375F,
            26.275360107421875F,
            0.30070978F,
            RIGHT | SPRINT,
            RIGHT | SPRINT);

    @BeforeEach
    void setUp() throws ClassNotFoundException {
        OfflineCultTestBootstrap.installConfig();
        Class.forName(VerboseCodecs.class.getName());
        Mockito.when(CultAPI.INSTANCE
                        .getConfigManager()
                        .getConfig()
                        .getLongElse(Mockito.anyString(), Mockito.anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    /** LivingEntity#aiStep zeroes the carried axis below 0.003 before travelRidden adds the input. */
    @Test
    void theMovementThresholdPrecedesTheRiddenInput() throws Exception {
        assertExact(run(CARRIED_X_UNDER_THE_CUTOFF));
    }

    private static void assertExact(Outcome outcome) {
        assertTrue(outcome.miss().length() <= 1.0E-5 && !outcome.flagged(), outcome.report());
    }

    private Outcome run(Scenario scenario) throws Exception {
        try (var services = new RecordConsumerServices();
                var f = fixture()) {
            CultPlayer player = f.player;
            seed(player, scenario);
            player.compensatedEntities.addEntity(
                    VEHICLE, EntityTypeIds.HORSE, scenario.start(), scenario.yaw(), 0.0F, 0);
            PacketEntityHorse horse = (PacketEntityHorse) player.compensatedEntities.getEntity(VEHICLE);
            assertNotNull(horse);
            horse.hasSaddle = true;
            horse.isTame = true;
            horse.movementSpeedAttribute = scenario.speed();
            horse.onGround = true;
            send(player, new ClientboundSetPassengers(VEHICLE, List.of(player.entityID)));
            pongThrough(player, player.sendTransactionAndGetId());
            player.checkManager.getSimulationProcessor().seedStartingVelocity(scenario.carried());
            player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);

            // The tick before: its input reaches the root at the next root tick.
            write(f, f.id(ServerboundPackets.PLAYER_INPUT, "player_input").writeByte(scenario.stagedInput()));
            write(f, f.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end"));

            PredictionResult before =
                    player.checkManager.getSimulationProcessor().getLastPrediction();
            write(f, f.id(ServerboundPackets.PLAYER_INPUT, "player_input").writeByte(scenario.nextInput()));
            write(
                    f,
                    f.encode(
                            ServerboundPackets.MOVE_PLAYER,
                            new ServerboundMovePlayer(
                                    0, 0, 0, scenario.yaw(), scenario.pitch(), false, false, false, true)));
            Vec3 target = scenario.target();
            write(
                    f,
                    f.id(ServerboundPackets.MOVE_VEHICLE, "move_vehicle")
                            .writeDouble(target.x)
                            .writeDouble(target.y)
                            .writeDouble(target.z)
                            .writeFloat(scenario.yaw())
                            .writeFloat(scenario.pitch() * 0.5F)
                            .writeBoolean(true));
            write(f, f.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end"));
            PredictionResult result =
                    player.checkManager.getSimulationProcessor().getLastPrediction();
            assertTrue(result != null && result != before, "the vehicle move was not predicted");
            Vec3 move = target.subtract(scenario.start());
            Vec3 closest = result.getAcceptedClosestToTarget();
            Vec3 miss = move.subtract(closest);
            String report = String.format(
                    Locale.ROOT,
                    "closest (%.9f, %.9f, %.9f) move (%.9f, %.9f, %.9f) miss (%.9f, %.9f, %.9f) offset %.9g flags %s",
                    closest.x,
                    closest.y,
                    closest.z,
                    move.x,
                    move.y,
                    move.z,
                    miss.x,
                    miss.y,
                    miss.z,
                    result.getOffset(),
                    result.getFlags());
            return new Outcome(miss, result.hasEffectiveFlags(), report);
        }
    }

    private void send(CultPlayer player, ClientboundSetPassengers packet) {
        var event = new PacketSendEvent<>(
                player.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        player.packetEntityReplication.onSetPassengers(event, player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    /** Answers every ping sent through {@code transaction}, as the client's pongs would. */
    private static void pongThrough(CultPlayer player, int transaction) {
        assertTrue(transaction != -1, "no ping was sent");
        for (int id = player.lastTransactionReceived.get() + 1; id <= transaction; id++) {
            player.lastTransactionReceived.set(id);
            player.latencyUtils.handleNettySyncTransaction(id);
        }
    }

    private static RecordReceiveFixture fixture() {
        var f = new RecordReceiveFixture(ProtocolVersion.V1_21_11);
        f.user.bind(Mockito.mock(PlatformPlayer.class));
        clientVersion(f.player, ClientVersion.V_1_21_11);
        f.registrar.register(batch -> {
            var checks = new CheckManagerListener();
            checks.registerForwardingEarlyReceivePackets(batch, PacketListenerPriority.LOW);
            batch.registerListener(PacketListenerPriority.LOWEST, new PacketPingListener());
            batch.registerReceiveListener(PacketListenerPriority.LOW, new PacketEntityAction());
            batch.registerListener(PacketListenerPriority.LOW, new PacketServerTeleport());
            batch.registerReceiveListener(PacketListenerPriority.LOW, checks);
            checks.registerForwardingSendPackets(batch, PacketListenerPriority.LOW);
            batch.registerReceiveListener(PacketListenerPriority.LOW, new PacketPlayerSteer());
        });
        f.channel.runPendingTasks();
        return f;
    }

    private static void clientVersion(CultPlayer player, ClientVersion version) {
        try {
            Field resolved = CultPlayer.class.getDeclaredField("resolvedClientVersion");
            resolved.setAccessible(true);
            resolved.set(player, version);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void write(RecordReceiveFixture f, ByteBuf frame) {
        f.channel.writeInbound(frame);
        f.channel.runPendingTasks();
        for (Object read; (read = f.channel.readInbound()) != null; ) {
            if (read instanceof ByteBuf buffer) buffer.release();
        }
    }

    /** Loaded chunks with a stone floor under the horse; the rider sits on it. */
    private static void seed(CultPlayer player, Scenario scenario) {
        player.gamemode = GameMode.SURVIVAL;
        player.canFly = false;
        player.isFlying = false;
        Vec3 start = scenario.start();
        CompensatedWorld world = player.compensatedWorld;
        int chunkX = (int) Math.floor(start.x) >> 4;
        int chunkZ = (int) Math.floor(start.z) >> 4;
        for (int cx = chunkX - 1; cx <= chunkX + 1; cx++) {
            for (int cz = chunkZ - 1; cz <= chunkZ + 1; cz++) {
                world.putChunk(cx, cz, new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
            }
        }
        int floor = (int) Math.floor(start.y) - 1;
        for (int x = (int) Math.floor(start.x) - 4; x <= (int) Math.floor(start.x) + 4; x++) {
            for (int z = (int) Math.floor(start.z) - 4; z <= (int) Math.floor(start.z) + 4; z++) {
                world.updateBlock(x, floor, z, BlockIds.STONE.defaultState());
            }
        }
        Vec3 seat = start.add(0.0D, 1.0D, 0.0D);
        player.x = player.lastX = seat.x;
        player.y = player.lastY = seat.y;
        player.z = player.lastZ = seat.z;
        player.xRot = player.lastTickXRot = scenario.yaw();
        player.yRot = player.lastTickYRot = scenario.pitch();
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, seat.x, seat.y, seat.z);
        player.onGround = true;
        player.lastOnGround = true;
        player.lastTransactionReceived.set(player.lastTransactionSent.get());
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
    }

    private record Scenario(
            Vec3 start,
            Vec3 target,
            Vec3 carried,
            float yaw,
            float pitch,
            float speed,
            int stagedInput,
            int nextInput) {}

    private record Outcome(Vec3 miss, boolean flagged, String report) {}
}
