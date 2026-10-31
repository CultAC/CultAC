package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JavaProtocol773RemotePistonTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();

    /** A controlled boat reaches the zombie's one 1.21.10 collision face and completes prediction. */
    @Test
    void aControlledBoatCollidesWithTheAssumed12110PistonPosition() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity actor = pushedActor(f);
            double x = positions(actor).getFirst();
            for (int i = 0; i < 3; i++) {
                f.tick(new Vec3(4.5, FLOOR_Y, 4.5), true);
                assertPosition(actor, x);
            }
            double face = x + (double) (0.6F / 2);
            Vec3 start = new Vec3(2.7, FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(101, EntityTypeIds.OAK_BOAT, start, 0, 0, 0);
            f.player.compensatedEntities.getEntity(101).onGround = true;
            f.setPassengers(101, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.rideTick(start, true);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(-2, 0, 0));
            var result = f.rideTick(new Vec3(face + 1.375 / 2, FLOOR_Y, 0.5), true);
            JavaTickFixture.assertVehicleExact(result, 0);
            var collision = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertEquals(1, collision.entities().size());
            assertEquals(face, collision.entities().getFirst().maxX, 1e-12);
            assertTrue(collision.collidedX());
        }
    }

    /** Protocol 773 assumes 1.21.10: the unscaled piston position persists until interpolation moves it. */
    @Test
    void theAssumed12110PistonPositionSurvivesIdleTicksAndFollowsInterpolation() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity actor = pushedActor(f);
            Vec3 park = new Vec3(4.5, FLOOR_Y, 4.5);
            double first = 0.5 + (double) (0.6F / 2) + 0.01;
            double newPosition = first + 0.5;
            assertPosition(actor, newPosition);
            for (int i = 0; i < 3; i++) {
                f.tick(park, true);
                assertPosition(actor, newPosition);
            }
            actor.onBundledPositionSyncTransaction(3.5, FLOOR_Y, 0.5, f.player);
            actor.onMovement(f.player);
            assertPosition(actor, newPosition + (3.5 - newPosition) / 3);
            actor.onMovement(f.player);
            actor.onMovement(f.player);
            for (double x : positions(actor)) assertEquals(3.5, x, 1e-12);
            assertEquals(1, actor.possibleMovementCollisionBoxCandidateCount(), "converged paths leave one state");
            assertEquals(new Vec3(3.5, FLOOR_Y, 0.5), actor.desyncClientPos);
        }
    }

    /** PositionMoveRotation.of composes relative packets from the one assumed client position. */
    @Test
    void relativeTeleportsBeforeTheirProofPreserveThe12110PistonAnchor() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity actor = pushedActor(f);
            var starts = positions(actor);
            for (double dx : new double[] {1, 2}) {
                var packet = new ClientboundTeleportEntity(
                        100, new Vec3d(dx, FLOOR_Y, 0.5), Vec3d.ZERO, 0, 0, Set.of(Relative.X), true);
                var event = new PacketSendEvent<>(
                        f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
                f.player.packetEntityReplication.onTeleportEntity(event, f.player, packet);
                event.tasksAfter().forEach(Runnable::run);
            }
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(starts.getFirst() + 3, actor.newPacketLocation.targetCenterX(), 1e-12);
            assertFalse(actor.hasPistonPatchStates());
        }
    }

    /** Motion and ground packets update the assumed client state without changing its position. */
    @Test
    void normalPacketMotionAndGroundReachThe12110PistonState() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity actor = pushedActor(f);
            var before = positions(actor);
            Vec3 velocity = new Vec3(0.125, 0.25, -0.125);
            var motion = new ClientboundEntityMotion(100, new Vec3d(velocity.x, velocity.y, velocity.z));
            var motionEvent = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_MOTION, motion, false);
            f.player.packetEntityReplication.onSetEntityMotion(motionEvent, f.player, motion);
            motionEvent.tasksAfter().forEach(Runnable::run);
            var ground = new ClientboundMoveEntity(
                    100, new EntityDelta.Linear((short) 0, (short) 0, (short) 0), 0, 0, true, false, false);
            var groundEvent = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, ground, false);
            f.player.packetEntityReplication.onMoveEntity(groundEvent, f.player, ground);
            groundEvent.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(velocity, actor.deltaMovement);
            assertTrue(actor.onGround);
            assertFalse(actor.hasPistonPatchStates());
            assertEquals(before, positions(actor));
        }
    }

    private static PacketEntity pushedActor(JavaTickFixture f) throws Exception {
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_9);
        Vec3 park = new Vec3(4.5, FLOOR_Y, 4.5);
        f.place(park, new Vec3(0, -0.0784000015258789, 0));
        f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
        BlockPos base = new BlockPos(-1, FLOOR_Y, 0);
        int piston = BLOCKS.with(BLOCKS.block("minecraft:piston").defaultState(), "facing", "east");
        f.player.compensatedWorld.updateBlock(base, piston);
        f.player.compensatedWorld.updateBlock(
                new BlockPos(1, FLOOR_Y, 0),
                BLOCKS.block("minecraft:sweet_berry_bush").defaultState());
        for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.packetEntityReplication.addEntity(
                100,
                EntityTypeIds.ZOMBIE,
                new Vec3(0.7, FLOOR_Y, 0.5),
                0,
                0,
                0,
                f.player.lastTransactionReceived.get());
        PacketEntity actor = f.player.compensatedEntities.getEntity(100);
        f.tick(park, true);
        f.player.compensatedWorld.pistons.handleBlockEvent(base, BLOCKS.blockIndex(piston), 0, 5, 0);
        f.tick(park, true);
        double first = 0.5 + (double) (0.6F / 2) + 0.01;
        assertEquals(first, actor.clientPhysicalPosition.x, 1e-12);
        f.tick(park, true);
        return actor;
    }

    private static List<Double> positions(PacketEntity entity) {
        return entity.getPossibleMovementCollisionBoxCandidates().stream()
                .map(box -> (box.minX + box.maxX) / 2)
                .distinct()
                .sorted()
                .toList();
    }

    private static void assertPosition(PacketEntity entity, double x) {
        var positions = positions(entity);
        assertEquals(1, positions.size(), positions.toString());
        assertEquals(x, positions.getFirst(), 1e-12);
        assertFalse(entity.hasPistonPatchStates());
    }
}
