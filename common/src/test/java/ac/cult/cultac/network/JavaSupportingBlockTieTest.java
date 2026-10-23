package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * CollisionGetter#findSupportingBlock picks the supporting block whose center is closest to the client's position and,
 * among equal distances, the greatest by BlockPos#compareTo: the highest Y, then the highest Z, then the highest X.
 * LivingEntity#travelInAir then reads the ground friction below that block.
 */
class JavaSupportingBlockTieTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    /**
     * A fence top and a bottom slab top meet at the client's feet, both centers 1.125 away. The slab is a layer higher,
     * so it supports the client and the ice under it gives the friction.
     */
    @Test
    void aTieBetweenAFenceAndAHigherSlabKeepsTheSlab() throws Exception {
        assertTrue(fenceAndSlab(walk(0.98F)), "ice friction");
        assertFalse(fenceAndSlab(walk(0.6F)), "fence friction");
    }

    /**
     * The client stands on the corner where four floor cells meet, all centers equally far. The ice is at the lower
     * Z, so the stone at the higher Z supports the client.
     */
    @Test
    void aTieOnOneLayerKeepsTheHigherZ() throws Exception {
        assertTrue(corner(walk(0.6F)), "stone friction");
        assertFalse(corner(walk(0.98F)), "ice friction");
    }

    private static boolean fenceAndSlab(double forward) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y - 1, 1), state("minecraft:oak_fence"));
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), state("minecraft:ice"));
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), state("minecraft:stone_slab"));
            return walksForward(f, new Vec3(1.25, FLOOR_Y + 0.5, 1.25), forward);
        }
    }

    private static boolean corner(double forward) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y - 1, 0), state("minecraft:ice"));
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y - 1, 1), state("minecraft:air"));
            return walksForward(f, new Vec3(1.0, FLOOR_Y, 1.0), forward);
        }
    }

    private static boolean walksForward(JavaTickFixture f, Vec3 position, double forward) throws Exception {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        // A client standing still: LivingEntity#travelInAir's gravity, (0 - 0.08) * 0.98F.
        f.place(position, new Vec3(0.0, (0.0 - 0.08) * (double) 0.98F, 0.0));
        f.player.onGround = true;
        f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        f.player.xRot = 0.0F;
        f.player.yRot = 0.0F;
        f.input(NONE);
        PredictionResult still = f.tick(position, true);
        assertTrue(exact(still), describe(still));
        f.input(FORWARD);
        return exact(f.tick(new Vec3(position.x, position.y, position.z + forward), true));
    }

    /** LivingEntity#getFrictionInfluencedSpeed for a walking client, times the 0.98F input scale. */
    private static double walk(float blockFriction) {
        float speed = 0.1F * (0.21600002F / (blockFriction * blockFriction * blockFriction));
        return (double) 0.98F * (double) speed;
    }

    private static int state(String block) {
        return BLOCKS.block(block).defaultState();
    }
}
