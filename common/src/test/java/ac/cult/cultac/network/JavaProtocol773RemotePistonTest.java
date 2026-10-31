package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPositionWorlds;
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
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JavaProtocol773RemotePistonTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();

    /** AbstractBoat#canVehicleCollide clips against either retained zombie position, one box per move. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void aControlledBoatCollidesWithEitherRetainedPistonPosition(int path) throws Exception {
        boatCollision(path, false);
    }

    @Test
    void aControlledBoatCannotInventAStopBetweenTheRetainedPistonPositions() throws Exception {
        boatCollision(0, true);
    }

    private static void boatCollision(int path, boolean between) throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity actor = pushedActor(f);
            var before = positions(actor);
            Vec3 park = new Vec3(4.5, FLOOR_Y, 4.5);
            for (int i = 0; i < 3; i++) {
                f.tick(park, true);
                assertEquals(before, positions(actor));
            }
            double face = before.get(path) + (double) (0.6F / 2);
            Vec3 start = new Vec3(2.7, FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(101, EntityTypeIds.OAK_BOAT, start, 0, 0, 0);
            f.player.compensatedEntities.getEntity(101).onGround = true;
            f.setPassengers(101, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.rideTick(start, true);
            assertEquals(before, positions(actor));
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(-2, 0, 0));
            double targetFace = between ? face + (before.get(1) - before.get(0)) / 2 : face;
            var result = f.rideTick(new Vec3(targetFace + 1.375 / 2, FLOOR_Y, 0.5), true);
            if (between) {
                assertEquals((before.get(1) - before.get(0)) / 2, result.getOffset(), 1e-12);
                assertTrue(result.getFlags().stream()
                        .anyMatch(flag -> flag.getCheck().getCheckName().equals("VehicleOffset")));
                return;
            }
            JavaTickFixture.assertVehicleExact(result, 0);
            var collision = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertEquals(1, collision.entities().size(), "one position of the zombie clips the whole move");
            assertEquals(face, collision.entities().getFirst().maxX, 1e-12);
            assertTrue(collision.collidedX());
        }
    }

    /** A position outside EntityGetter's query contributes no shape, even when another valid position intersects. */
    @Test
    void aCollisionWorldMayLeaveTheZombieOutsideTheQuery() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity actor = pushedActor(f);
            var before = positions(actor);
            double halfWidth = (double) (0.6F / 2);
            var query = new SimpleCollisionBox(
                    before.get(0) + halfWidth + 0.02, FLOOR_Y, 0.4,
                    before.get(1) + halfWidth - 0.02, FLOOR_Y + 0.1, 0.6);
            Vec3 at = new Vec3(2.7, FLOOR_Y, 0.5);
            f.player.compensatedEntities.addEntity(101, EntityTypeIds.OAK_BOAT, at, 0, 0, 0);
            var boat = f.player.compensatedEntities.getEntity(101);
            var worlds = EntityPositionWorlds.forActor(f.player, boat, at, query);
            assertEquals(2, worlds.size());
            assertEquals(1, worlds.stream().filter(List::isEmpty).count());
            assertEquals(before.get(1) + halfWidth, worlds.stream()
                    .filter(world -> !world.isEmpty()).findFirst().orElseThrow().getFirst().maxX, 1e-12);
            actor.onBundledPositionSyncTransaction(3.5, FLOOR_Y, 0.5, f.player);
            for (int i = 0; i < 3; i++) actor.onMovement(f.player);
            assertTrue(EntityPositionWorlds.noEntities(EntityPositionWorlds.forActor(f.player, boat, at, query)));
            var converged = EntityPositionWorlds.forActor(
                    f.player, boat, at, new SimpleCollisionBox(3, FLOOR_Y, 0, 4, FLOOR_Y + 1, 1));
            assertEquals(1, converged.size(), "equivalent positions produce one collision world");
            assertEquals(1, converged.getFirst().size());
        }
    }

    /** Remote Entity#move also consumes berry speed; both clients keep the resulting physical position at rest. */
    @Test
    void bothRemotePistonPositionsSurviveIdleTicksAndThenFollowTheirOwnInterpolation() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity actor = pushedActor(f);
            Vec3 park = new Vec3(4.5, FLOOR_Y, 4.5);
            double first = 0.5 + (double) (0.6F / 2) + 0.01;
            double oldPosition = first + 0.5 * 0.8F;
            double newPosition = first + 0.5;
            assertPositions(actor, oldPosition, newPosition);
            for (int i = 0; i < 3; i++) {
                f.tick(park, true);
                assertPositions(actor, oldPosition, newPosition);
            }
            actor.onBundledPositionSyncTransaction(3.5, FLOOR_Y, 0.5, f.player);
            actor.onMovement(f.player);
            assertPositions(actor, oldPosition + (3.5 - oldPosition) / 3, newPosition + (3.5 - newPosition) / 3);
            actor.onMovement(f.player);
            actor.onMovement(f.player);
            for (double x : positions(actor)) assertEquals(3.5, x, 1e-12);
            assertEquals(1, actor.possibleMovementCollisionBoxCandidateCount(), "converged paths leave one state");
            assertEquals(new Vec3(3.5, FLOOR_Y, 0.5), actor.desyncClientPos);
        }
    }

    /** PositionMoveRotation.of reads each path's live position; two packets before a proof compose per path. */
    @Test
    void relativeTeleportsBeforeTheirProofPreserveBothPistonAnchors() throws Exception {
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
            var targets = new ArrayList<Double>();
            assertTrue(actor.runPistonPatchStates(() -> targets.add(actor.newPacketLocation.targetCenterX())));
            targets.sort(Double::compare);
            assertEquals(List.of(starts.get(0) + 3, starts.get(1) + 3), targets);
        }
    }

    /** ClientPacketListener's motion and ground writes affect both source paths without changing their positions. */
    @Test
    void normalPacketMotionAndGroundReachBothPistonPaths() throws Exception {
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
            assertTrue(actor.runPistonPatchStates(() -> {
                assertEquals(velocity, actor.deltaMovement);
                assertTrue(actor.onGround);
            }));
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

    private static void assertPositions(PacketEntity entity, double oldPosition, double newPosition) {
        var positions = positions(entity);
        assertEquals(2, positions.size(), positions.toString());
        assertEquals(oldPosition, positions.get(0), 1e-12);
        assertEquals(newPosition, positions.get(1), 1e-12);
    }
}
