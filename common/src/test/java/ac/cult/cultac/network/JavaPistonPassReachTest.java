package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPath;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A block-entity pass replays each mover only on the actors it can reach where the earlier movers left them: movers
 * far from every actor change nothing, and a mover the starting box misses still moves an actor shoved into it.
 */
class JavaPistonPassReachTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final long GAME_TIME = 100;

    /** Shoves a box at x 0.2..0.8 east by 0.31: the stone's face advances from x 0 to x 0.5. */
    private static final ClientBlockEntityPass.Piston NEAR = new ClientBlockEntityPass.Piston(
            new BlockPos(0, FLOOR_Y, 0), BlockIds.STONE.defaultState(), Direction.EAST, true, false, 0);

    @Test
    void aPistonReachesExactlyTheBoxesItsReachBoxIntersects() {
        var piston = new ClientBlockEntityPass.Piston(
                new BlockPos(3, -2, 7), BlockIds.STONE.defaultState(), Direction.UP, false, true, 0.5F);
        SimpleCollisionBox reach = piston.reach();
        int checked = 0;
        for (double x = -0.5; x <= 7; x += 0.25)
            for (double y = -5.5; y <= 2; y += 0.25)
                for (double z = 4.5; z <= 12; z += 0.25) {
                    // Quarter steps put the box's faces exactly on the reach's faces too.
                    var box = new SimpleCollisionBox(x - 0.25, y, z - 0.25, x + 0.25, y + 1.75, z + 0.25);
                    boolean expected = box.maxX > reach.minX
                            && box.minX < reach.maxX
                            && box.maxY > reach.minY
                            && box.minY < reach.maxY
                            && box.maxZ > reach.minZ
                            && box.minZ < reach.maxZ;
                    assertEquals(expected, piston.reaches(box), box::toString);
                    checked++;
                }
        assertTrue(checked > 0);
    }

    @Test
    void farPistonsLeaveTheLocalReplayUnchanged() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(NONE);
            var context = f.tick(position, true).getSimulationContext();

            var alone = replay(f, context, new ClientBlockEntityPass(List.of(NEAR), GAME_TIME), position);
            var crowded = replay(f, context, crowdedPass(), position);

            assertTrue(alone.moved(), "the near piston must shove the player for this comparison to mean anything");
            assertEquals(0.5 + 0.31, alone.state().position().x, 1e-9);
            assertSameResult(alone, crowded);
            assertEquals(pathPositions(alone.path()), pathPositions(crowded.path()));
        }
    }

    @Test
    void farPistonsLeaveTheRemoteReplayUnchanged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(-6.5, FLOOR_Y, -6.5), Vec3.ZERO);
            f.addRemotePlayer(1001, new Vec3(0.5, FLOOR_Y, 0.5), GameMode.SURVIVAL);
            f.addRemotePlayer(1002, new Vec3(0.6, FLOOR_Y, 0.35), GameMode.SURVIVAL);
            f.addRemotePlayer(1003, new Vec3(6.5, FLOOR_Y, -6.5), GameMode.SURVIVAL);
            var entities = f.player.compensatedEntities;
            List<JavaBlockEntityPass.EntityStart> starts = List.of(
                    start(entities.getEntity(1001), new Vec3(0.5, FLOOR_Y, 0.5)),
                    start(entities.getEntity(1002), new Vec3(0.6, FLOOR_Y, 0.35)),
                    start(entities.getEntity(1003), new Vec3(6.5, FLOOR_Y, -6.5)));

            var alone = JavaBlockEntityPass.runEntities(
                    f.player, starts, new ClientBlockEntityPass(List.of(NEAR), GAME_TIME));
            var crowded = JavaBlockEntityPass.runEntities(f.player, starts, crowdedPass());

            assertTrue(alone.get(0).moved());
            assertTrue(alone.get(1).moved());
            assertFalse(alone.get(2).moved());
            assertNotEquals(alone.get(0).state().position(), alone.get(1).state().position());
            for (int i = 0; i < starts.size(); i++) assertSameResult(alone.get(i), crowded.get(i));
        }
    }

    @Test
    void aMoverReachesThePlayerWhereEarlierMoversLeftIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(NONE);
            var context = f.tick(position, true).getSimulationContext();
            // The box carries the player from x 0.5 to x 3.5.
            var carry = new ClientBlockEntityPass.ShulkerBox(
                    new SimpleCollisionBox(0, FLOOR_Y, 0, 1, FLOOR_Y + 2, 1), new Vec3(3, 0, 0));
            // Its stone face retreats from x 4 to x 3.5 while moving west, reaching only the carried box (x 3.2..3.8).
            var later = new ClientBlockEntityPass.Piston(
                    new BlockPos(3, FLOOR_Y, 0), BlockIds.STONE.defaultState(), Direction.WEST, true, false, 0);
            assertFalse(later.reaches(new SimpleCollisionBox(0.2, FLOOR_Y, 0.2, 0.8, FLOOR_Y + 1.8, 0.8)));

            var missed = replay(f, context, new ClientBlockEntityPass(List.of(later), GAME_TIME), position);
            assertFalse(missed.moved());
            assertEquals(position, missed.state().position());

            var chained = replay(f, context, new ClientBlockEntityPass(List.of(carry, later), GAME_TIME), position);
            assertEquals(3.5 - 0.31, chained.state().position().x, 1e-9);
            assertEquals(position.z, chained.state().position().z, 1e-12);
        }
    }

    /**
     * {@link #NEAR} amid 240 stone and honey pistons, every one at least 3.5 blocks from where any actor stands, half
     * ticking before it and half after.
     */
    private static ClientBlockEntityPass crowdedPass() {
        List<ClientBlockEntityPass.Mover> far = new ArrayList<>();
        Direction[] directions = Direction.values();
        int i = 0;
        for (int x : new int[] {-8, -7, -6, -5, 5, 6, 7, 8})
            for (int z = 3; z <= 8; z++)
                for (int y = FLOOR_Y; y < FLOOR_Y + 5; y++, i++)
                    far.add(new ClientBlockEntityPass.Piston(
                            new BlockPos(x, y, z),
                            (i & 1) == 0 ? BlockIds.STONE.defaultState() : BlockIds.HONEY_BLOCK.defaultState(),
                            directions[i % directions.length],
                            (i & 2) == 0,
                            false,
                            (i % 3) * 0.25F));
        List<ClientBlockEntityPass.Mover> movers = new ArrayList<>(far.size() + 1);
        movers.addAll(far.subList(0, far.size() / 2));
        movers.add(NEAR);
        movers.addAll(far.subList(far.size() / 2, far.size()));
        return new ClientBlockEntityPass(movers, GAME_TIME);
    }

    /** The local player's replay of {@code pass}, standing still at {@code position}. */
    private static JavaBlockEntityPass.Result replay(
            JavaTickFixture f, SimulationContext context, ClientBlockEntityPass pass, Vec3 position) {
        return JavaBlockEntityPass.run(f.player, context, pass, standing(position), Pose.STANDING, false, position);
    }

    private static JavaBlockEntityPass.EntityStart start(PacketEntity actor, Vec3 position) {
        return new JavaBlockEntityPass.EntityStart(actor, standing(position));
    }

    private static JavaPassState standing(Vec3 position) {
        return new JavaPassState(
                position,
                Vec3.ZERO,
                true,
                new MainSupportingBlockData(new BlockPos(0, FLOOR_Y - 1, 0), true),
                null,
                0,
                ClientPistonDeltas.NONE);
    }

    private static void assertSameResult(JavaBlockEntityPass.Result expected, JavaBlockEntityPass.Result actual) {
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.moved(), actual.moved());
        assertEquals(expected.fixedX(), actual.fixedX());
        assertEquals(expected.fixedY(), actual.fixedY());
        assertEquals(expected.fixedZ(), actual.fixedZ());
        assertEquals(expected.lastMove(), actual.lastMove());
        assertEquals(expected.recorded(), actual.recorded());
        assertEquals(expected.boatLastYd(), actual.boatLastYd());
    }

    /** A path's positions without its mover indexes, which the far movers shift. */
    private static List<Vec3> pathPositions(JavaBlockEntityPath path) {
        return path == null ? List.of() : path.steps().stream().map(JavaBlockEntityPath.Step::position).toList();
    }
}
