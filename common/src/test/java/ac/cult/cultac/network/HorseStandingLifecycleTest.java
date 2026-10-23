package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
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
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The controlled horse's AbstractHorse#tick tail: standCounter expiry and the allowStandSliding reset on a
 * non-standing tick, run at the client tick end before the rider's staged onPlayerJump.
 */
class HorseStandingLifecycleTest {
    private static final int VEHICLE = 4242;

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

    /**
     * A release stands the horse; a server standing=false tick clears allowStandSliding, so a later server
     * standing=true leaves AbstractHorse#getRiddenInput free to zero grounded input.
     */
    @Test
    void aNonStandingTickClearsStandSliding() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = fixture()) {
            PacketEntityHorse horse = ride(f, EntityTypeIds.HORSE);
            release(horse);
            tickEnd(f);
            assertTrue(horse.isRearing, "onPlayerJump -> standIfPossible stands the horse");
            assertTrue(horse.allowStandSliding, "onPlayerJump allows stand sliding");

            horse.setRearingFromMetadata(false);
            tickEnd(f);
            assertFalse(horse.allowStandSliding, "the non-standing root tail clears allowStandSliding");

            horse.setRearingFromMetadata(true);
            tickEnd(f);
            assertTrue(horse.isRearing);
            assertFalse(horse.allowStandSliding, "a server stand does not restore stand sliding");
        }
    }

    /** SynchedEntityData writes only the flag; the local 20-tick standCounter keeps running and clears it. */
    @Test
    void metadataKeepsTheLocalStandCounter() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = fixture()) {
            PacketEntityHorse horse = ride(f, EntityTypeIds.HORSE);
            release(horse);
            tickEnd(f);
            for (int tick = 1; tick < 20; tick++) {
                if (tick == 5) horse.setRearingFromMetadata(true);
                tickEnd(f);
                assertTrue(horse.isRearing, "still standing after " + tick + " root tails");
            }
            tickEnd(f);
            assertFalse(horse.isRearing, "standCounter expires on the 20th root tail");
        }
    }

    /** Camel#canPerformRearing is false, and Mob#isEffectiveAi is false without AI. */
    @Test
    void ineligibleHorsesDoNotRear() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = fixture()) {
            PacketEntityHorse camel = ride(f, EntityTypeIds.CAMEL);
            release(camel);
            tickEnd(f);
            assertFalse(camel.isRearing, "camels cannot rear");
        }
        try (var services = new RecordConsumerServices();
                var f = fixture()) {
            PacketEntityHorse horse = ride(f, EntityTypeIds.HORSE);
            horse.noAI = true;
            release(horse);
            tickEnd(f);
            assertFalse(horse.isRearing, "a no-AI horse cannot rear");
        }
    }

    private static void release(PacketEntityHorse horse) {
        // What PacketEntityAction stages for START_RIDING_JUMP.
        horse.nextHorseJump = 1.0D;
        horse.nextAllowStandSliding = true;
        horse.armClientRidingJumpStand();
    }

    private static PacketEntityHorse ride(RecordReceiveFixture f, int type) {
        CultPlayer player = f.player;
        player.gamemode = GameMode.SURVIVAL;
        player.lastTransactionReceived.set(player.lastTransactionSent.get());
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.compensatedEntities.addEntity(VEHICLE, type, new Vec3(0.5D, 64.0D, 0.5D), 0.0F, 0.0F, 0);
        PacketEntityHorse horse = (PacketEntityHorse) player.compensatedEntities.getEntity(VEHICLE);
        assertNotNull(horse);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.onGround = true;
        var packet = new ClientboundSetPassengers(VEHICLE, List.of(player.entityID));
        var event = new PacketSendEvent<>(
                player.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        player.packetEntityReplication.onSetPassengers(event, player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        int transaction = player.sendTransactionAndGetId();
        for (int id = player.lastTransactionReceived.get() + 1; id <= transaction; id++) {
            player.lastTransactionReceived.set(id);
            player.latencyUtils.handleNettySyncTransaction(id);
        }
        assertTrue(
                player.compensatedEntities.vehicles.isLocallyTickedVelocityRoot(horse), "the rider controls the horse");
        return horse;
    }

    private static void tickEnd(RecordReceiveFixture f) {
        f.channel.writeInbound(f.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end"));
        f.channel.runPendingTasks();
        for (Object read; (read = f.channel.readInbound()) != null; ) {
            if (read instanceof ByteBuf buffer) buffer.release();
        }
    }

    private static RecordReceiveFixture fixture() {
        var f = new RecordReceiveFixture(ProtocolVersion.V1_21_11);
        f.user.bind(Mockito.mock(PlatformPlayer.class));
        try {
            Field resolved = CultPlayer.class.getDeclaredField("resolvedClientVersion");
            resolved.setAccessible(true);
            resolved.set(f.player, ClientVersion.V_1_21_11);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        f.registrar.register(batch -> {
            var checks = new CheckManagerListener();
            checks.registerForwardingEarlyReceivePackets(batch, PacketListenerPriority.LOW);
            batch.registerListener(PacketListenerPriority.LOWEST, new PacketPingListener());
            batch.registerReceiveListener(PacketListenerPriority.LOW, new PacketEntityAction());
            batch.registerReceiveListener(PacketListenerPriority.LOW, checks);
            checks.registerForwardingSendPackets(batch, PacketListenerPriority.LOW);
        });
        f.channel.runPendingTasks();
        return f;
    }
}
