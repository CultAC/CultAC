package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 26.2 client sprinting off a stair's step with shift pressed. Player#maybeBackOffFromEdge holds it over the stair's
 * lower half on the ground tick and, through Player#isAboveGround's fall-distance clause, on the two falling ticks
 * after it, while deltaMovement keeps the input the edge took; it lands on the lower half and walks on toward the next
 * stair. The moves are a real client's, translated onto the fixture: the upper floor is FLOOR_Y, the stair at z = 0.
 */
class JavaSneakEdgeAirborneTest {
    private static final ServerboundPlayerInput SPRINT_SNEAK =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final float YAW = -183.6553F;
    private static final double POST_TOP = FLOOR_Y + 1.5;

    @Test
    void theVelocityTheEdgeHidesWhileFallingIsCarried() throws Exception {
        try (var f = new JavaTickFixture()) {
            stairs(f);
            // LivingEntity#travelInAir's ground friction, 0.6F * 0.91F, and gravity, applied to the previous move.
            double friction = 0.6F * 0.91F;
            f.place(
                    recorded(12033.64914660181, 123.0, 12003.203528879536),
                    new Vec3(
                            -0.015746520406537456 * friction, -0.08D * (double) 0.98F, -0.2933180369454931 * friction));
            f.input(SPRINT);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
            assertExact(f, 46.82401F, 12033.632431977067, 123.0, 12002.916236044503, true);

            f.input(SPRINT_SNEAK);
            assertExact(f, 46.92259F, 12033.615188767293, 122.92159999847412, 12002.732232970095, false);
            assertExact(f, 47.054028F, 12033.60528695227, 122.76636799395752, 12002.719538803727, false);
            assertExact(f, 47.218327F, 12033.595789278947, 122.53584062504456, 12002.713858638335, false);
            assertExact(f, 47.316906F, 12033.586659374581, 122.5, 12002.564561214014, true);
            assertExact(f, 47.382626F, 12033.575916054091, 122.42159999847412, 12002.390558200395, false);
        }
    }

    /**
     * A shift-holding player falls past a cobblestone wall post toward a wall above it and lands on the post. On the
     * fourth falling tick the post's top is 0.536 below, within maxUpStep but not within maxUpStep - fallDistance
     * (0.6 - 0.464), so Player#isAboveGround is false and Player#maybeBackOffFromEdge leaves the delta whole; the wall
     * stops it, and Entity#move zeroes z. With fallDistance ignored the edge would back z off to 0, which the wall stops
     * at the same place, and carry the delta's z into the landing tick.
     */
    @Test
    void aFallTooFarForTheEdgeIsNotBackedOff() throws Exception {
        try (var f = new JavaTickFixture()) {
            postUnderWall(f);
            f.player.xRot = 0.0F;
            f.player.yRot = 0.0F;
            // LocalPlayer#modifyInput's 0.98F and sneaking 0.3F, moved by Player#getFlyingSpeed's 0.02F at yaw 0.
            double input = (double) (0.98F * 0.3F) * (double) 0.02F;
            double[] dz = new double[4];
            // The fourth delta, 0.0469, is one 0.05 back-off step that clears the post's 0.0375 under the box: z to 0.
            double startZ = 0.035;
            double vz = startZ;
            for (int tick = 0; tick < dz.length; tick++) {
                dz[tick] = vz + input;
                vz = dz[tick] * (double) 0.91F;
            }
            // The third tick ends with the box's south face on the wall's north face, 5/16 into its block.
            double flushZ = 1.0 + 5.0 / 16.0 - (double) (0.6F / 2.0F);
            double z = flushZ - dz[2] - dz[1] - dz[0];
            // The tick before left the ground: LivingEntity#travelInAir's gravity and vertical drag.
            double vy = -0.08D * (double) 0.98F;
            Vec3 position = new Vec3(0.5, POST_TOP + 1.0, z);
            f.place(position, new Vec3(0.0, vy, startZ));
            f.input(SNEAK);

            for (int tick = 0; tick < 3; tick++) {
                position = position.add(0.0, vy, dz[tick]);
                vy = (vy - 0.08D) * (double) 0.98F;
                assertExactTick(f, position, false);
            }
            position = position.add(0.0, vy, 0.0);
            assertExactTick(f, position, false);

            PredictionResult landing = f.tick(new Vec3(0.5, POST_TOP, position.z + input), true);
            assertTrue(exact(landing), describe(landing));
            assertEquals(0.0, landing.getInitialStartingVel().z);
        }
    }

    /**
     * A lone cobblestone wall post on the floor, its top 1.5 up and its south face 3/4 into its block, under an
     * east-west wall whose bottom is 1.5 above the post's top and whose north face is 5/16 into the next block south.
     */
    private static void postUnderWall(JavaTickFixture f) {
        var registry = DataTables.defaults().registry();
        int wall = registry.block("minecraft:cobblestone_wall").defaultState();
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), registry.with(wall, "up", "true"));
        int eastWest = registry.with(registry.with(registry.with(wall, "up", "false"), "east", "low"), "west", "low");
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 3, 1), eastWest);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void assertExactTick(JavaTickFixture f, Vec3 position, boolean onGround) {
        PredictionResult result = f.tick(position, onGround);
        assertTrue(exact(result), describe(result));
    }

    /**
     * The recorded stairs: a south-facing outer stair whose step tops the upper floor, its lower half 0.5 below, and a
     * drop past it to a south-facing stair one block down.
     */
    private static void stairs(JavaTickFixture f) {
        var registry = DataTables.defaults().registry();
        int stairs = registry.with(registry.block("minecraft:stone_stairs").defaultState(), "facing", "south");
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= -1; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.AIR.defaultState());
            }
        }
        f.player.compensatedWorld.updateBlock(
                new BlockPos(0, FLOOR_Y - 1, 0), registry.with(stairs, "shape", "outer_left"));
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 2, -1), stairs);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void assertExact(JavaTickFixture f, float pitch, double x, double y, double z, boolean onGround) {
        f.player.xRot = YAW;
        f.player.yRot = pitch;
        PredictionResult result = f.tick(recorded(x, y, z), onGround);
        assertTrue(exact(result), describe(result));
    }

    /** A recorded position moved onto the fixture: its upper floor y = 123 becomes FLOOR_Y, its stair z = 12003 z = 0. */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 12033.0, y - 123.0 + FLOOR_Y, z - 12003.0);
    }
}
