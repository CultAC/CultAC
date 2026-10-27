package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRavager;
import ac.cult.cultac.utils.latency.ClientRavagerRoars;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

class JavaRavagerRoarTest {
    private static final int RAVAGER = 100;
    private static final Vec3 START = new Vec3(0.5, 64, 0.5);
    private static final Vec3 PUSH = new Vec3(0, 0.2, -2);

    private static PacketEntityRavager ravager(JavaTickFixture f) {
        f.player.compensatedEntities.addEntity(RAVAGER, EntityTypeIds.RAVAGER, START.add(0, 0, 2), 0, 0, 0);
        return assertInstanceOf(PacketEntityRavager.class, f.player.compensatedEntities.getEntity(RAVAGER));
    }

    private static int event(JavaTickFixture f, int id, int status) {
        var packet = new ClientboundEntityEvent(id, (byte) status);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private static void clean(PredictionResult result) {
        assertFalse(result.hasEffectiveFlags(), JavaTickFixture.describe(result));
        assertEquals(0, result.getOffset(), 1e-10, JavaTickFixture.describe(result));
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_6", "V_1_21_11", "V_26_3"})
    void provenRoarPushesBeforeTravelExactlyOnce(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(START, Vec3.ZERO);
            ravager(f);
            f.pongThrough(event(f, RAVAGER, 69));
            clean(f.tick(START.add(PUSH), false));
            assertTrue(f.player
                    .compensatedEntities
                    .ravagerRoars
                    .pending(f.player.compensatedEntities.getSelf())
                    .isEmpty());
            var carry = assertInstanceOf(
                    JavaPredictionCarry.class,
                    f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry());
            Vec3 next = new Vec3(0, (0.2 - 0.08) * (double) 0.98F, -2 * (double) (0.6F * 0.91F));
            assertTrue(
                    carry.states().stream().anyMatch(state -> state.velocity().distanceTo(next) <= 1e-12),
                    carry.toString());
            clean(f.tick(START.add(PUSH).add(next), false));
        }
    }

    @Test
    void ignoringAProvenRoarIsNotAnAllowedCandidate() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(START, Vec3.ZERO);
            ravager(f);
            f.pongThrough(event(f, RAVAGER, 69));
            var result = f.tick(START, true);
            assertTrue(result.hasEffectiveFlags(), JavaTickFixture.describe(result));
        }
    }

    @Test
    void receiptAndEntityTypeDecideWhetherThePacketPushes() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(START, new Vec3(0, -0.08 * (double) 0.98F, 0));
            ravager(f);
            int proof = event(f, RAVAGER, 69);
            clean(f.tick(START, true));
            f.pongThrough(proof);
            clean(f.tick(START.add(0, 0.2 - 0.08 * (double) 0.98F, -2), false));
        }
        try (var f = new JavaTickFixture()) {
            f.place(START, new Vec3(0, -0.08 * (double) 0.98F, 0));
            f.player.compensatedEntities.addEntity(RAVAGER, EntityTypeIds.COW, START.add(0, 0, 2), 0, 0, 0);
            f.pongThrough(event(f, RAVAGER, 69));
            clean(f.tick(START, true));
        }
    }

    @Test
    void packetVelocityOverwritesAnEarlierRoarAndALaterRoarAddsToIt() throws Exception {
        for (boolean roarFirst : new boolean[] {true, false}) {
            try (var f = new JavaTickFixture()) {
                f.place(START, Vec3.ZERO);
                ravager(f);
                Vec3 motion = new Vec3(0.125, 0.25, 0);
                if (roarFirst) event(f, RAVAGER, 69);
                f.velocity(motion);
                if (!roarFirst) event(f, RAVAGER, 69);
                f.pongThrough(f.player.lastTransactionSent.get());
                clean(f.tick(START.add(roarFirst ? motion : motion.add(PUSH)), false));
            }
        }
    }

    @Test
    void teleportDeltaFlagsPreserveOnlyTheInheritedRoarAxes() throws Exception {
        for (boolean relative : new boolean[] {true, false}) {
            try (var f = new JavaTickFixture()) {
                Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
                f.place(START, Vec3.ZERO);
                ravager(f);
                f.pongThrough(event(f, RAVAGER, 69));
                var destination = new Vec3(0.5, 80, 0.5);
                f.playerPosition(
                        destination,
                        Vec3.ZERO,
                        relative ? Set.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z) : Set.of());
                clean(f.tick(relative ? destination.add(PUSH) : destination, false));
            }
        }
    }

    @Test
    void severalRoarsBeforeOneTickAccumulateAndRemovalDoesNotUndoThePush() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(START, Vec3.ZERO);
            ravager(f);
            event(f, RAVAGER, 69);
            f.pongThrough(event(f, RAVAGER, 69));
            f.player.compensatedEntities.removeEntity(RAVAGER);
            clean(f.tick(START.add(PUSH).add(PUSH), false));
        }
    }

    @Test
    void packetEventReadsThePositionBeforeTheNextInterpolationStep() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(START, Vec3.ZERO);
            var ravager = ravager(f);
            // Packet position-sync sets a three-tick interpolation target; the following roar in the same
            // packet phase reads the old position, not the future commonTick position toward that target.
            ravager.onBundledPositionSyncTransaction(0.5, 64, 5.5, f.player);
            f.pongThrough(event(f, RAVAGER, 69));
            clean(f.tick(START.add(PUSH), false));
        }
    }

    @Test
    void onlyALocallyControlledLivingMountReceivesTheVehicleImpulse() throws Exception {
        for (int type : new int[] {EntityTypeIds.HORSE, EntityTypeIds.COW, EntityTypeIds.OAK_BOAT}) {
            try (var f = new JavaTickFixture()) {
                f.place(START, Vec3.ZERO);
                ravager(f);
                var entities = f.player.compensatedEntities;
                entities.addEntity(101, type, START, 0, 0, 0);
                var vehicle = entities.getEntity(101);
                if (vehicle instanceof PacketEntityHorse horse) horse.hasSaddle = true;
                entities.getSelf().mount(vehicle);
                f.pongThrough(event(f, RAVAGER, 69));
                assertEquals(
                        type == EntityTypeIds.HORSE ? 1 : 0,
                        entities.ravagerRoars.pending(vehicle).size());
                assertTrue(entities.ravagerRoars.pending(entities.getSelf()).isEmpty());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_5"})
    void legacyCountdownUsesTheRavagersSideOfThePlayerTick(ClientVersion version) throws Exception {
        for (boolean early : new boolean[] {true, false}) {
            try (var f = new JavaTickFixture()) {
                JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
                f.place(START, new Vec3(0, -0.08 * (double) 0.98F, 0));
                var ravager = ravager(f);
                if (early) f.player.compensatedEntities.getSelf().setClientTickOrder(ravager.getClientTickOrder() + 1);
                f.pongThrough(event(f, RAVAGER, 39));
                // Event 69 is unknown to these clients; it cannot cause an early or duplicate push.
                f.pongThrough(event(f, RAVAGER, 69));
                for (int tick = 1; tick < 50; tick++) clean(f.tick(START, true));
                Vec3 destination = START.add(0, 0.2 - 0.08 * (double) 0.98F, -2);
                if (!early) clean(f.tick(START, true));
                clean(f.tick(destination, false));
            }
        }
    }

    @Test
    void frozenAndUntickedChunksDoNotAdvanceTheLegacyCountdown() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_5);
            f.place(START, new Vec3(0, -0.08 * (double) 0.98F, 0));
            var ravager = ravager(f);
            f.player.compensatedEntities.appendSelfToClientTickOrder();
            f.pongThrough(event(f, RAVAGER, 39));
            f.player.packetStateData.serverTicksFrozen = true;
            for (int tick = 0; tick < 5; tick++) clean(f.tick(START, true));
            f.player.packetStateData.serverTicksFrozen = false;
            var box = ravager.getClientMovementCollisionBox();
            ravager.setPositionRaw(box.copy().offset(0, 0, 160));
            for (int tick = 0; tick < 5; tick++) clean(f.tick(START, true));
            ravager.setPositionRaw(box);
            // Re-entering a ticking chunk appends the ravager to EntityTickList, now after the player.
            assertTrue(ravager.getClientTickOrder()
                    > f.player.compensatedEntities.getSelf().getClientTickOrder());
            for (int tick = 1; tick < 50; tick++) clean(f.tick(START, true));
            clean(f.tick(START, true));
            clean(f.tick(START.add(0, 0.2 - 0.08 * (double) 0.98F, -2), false));
        }
    }

    @Test
    void rangeIsAnInflatedBoxAndThePushClampsSquaredDistance() {
        var source = new SimpleCollisionBox(-1, 0, -1, 1, 2, 1);
        var target = new SimpleCollisionBox(4, 0, 4, 4.6, 1.8, 4.6);
        Vec3 at = new Vec3(4.3, 0, 4.3);
        assertEquals(
                List.of(new Vec3(4.3 / (4.3 * 4.3 * 2) * 4, 0.2, 4.3 / (4.3 * 4.3 * 2) * 4)),
                ClientRavagerRoars.impulses(List.of(source), target, at));
        assertEquals(
                List.of(Vec3.ZERO),
                ClientRavagerRoars.impulses(List.of(source), target.copy().offset(0, 6, 0), at.add(0, 6, 0)));
        var near = new SimpleCollisionBox(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
        assertEquals(
                List.of(new Vec3(40, 0.2, 0)),
                ClientRavagerRoars.impulses(List.of(source), near, new Vec3(0.01, 0, 0)));
        assertEquals(List.of(new Vec3(0, 0.2, 0)), ClientRavagerRoars.impulses(List.of(source), near, Vec3.ZERO));
    }
}
