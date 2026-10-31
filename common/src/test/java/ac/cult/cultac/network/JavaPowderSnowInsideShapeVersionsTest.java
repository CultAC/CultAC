package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaPowderSnowInsideShapeVersionsTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    /**
     * A fall ending between PowderSnowBlock's 0.9 FALLING_COLLISION_SHAPE and the top of the block. Through 1.21.4
     * PowderSnowBlock has no getEntityInsideCollisionShape override, so BlockBehaviour's Shapes.block() makes
     * Entity#checkInsideBlocks run entityInside: makeStuckInBlock resets the fall distance, and the following move
     * sinks through the now-empty collision shape. From 1.21.5 the inside shape is the entity's collision shape; the
     * falling shape does not intersect the move, so the following move lands on it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aFallEndingAboveTheFallingShapeUsesTheReleasesInsideShape(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.POWDER_SNOW.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 3.86, 0.5);
            double velocity = -0.2;
            f.place(position, new Vec3(0, velocity, 0));
            f.input(NONE);
            // Seven free-fall moves end at Y=64.9487, more than 2.5 blocks below the start.
            for (int tick = 0; tick < 7; tick++) {
                position = position.add(0, velocity, 0);
                var result = f.tick(position, false);
                assertTrue(exact(result), release + " fall tick " + tick + " " + describe(result));
                velocity = (velocity - 0.08) * 0.98F;
            }
            boolean blockInsideShape = version.isOlderThan(ClientVersion.V_1_21_5);
            double landing = blockInsideShape ? FLOOR_Y : FLOOR_Y + (double) 0.9F;
            var result = f.tick(new Vec3(0.5, landing, 0.5), true);
            assertTrue(exact(result), release + " landing " + describe(result));
        }
    }
}
