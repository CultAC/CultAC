package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.ClientFluidHeights;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * LiquidBlock#getCollisionShape gives a lava source Strider#getLiquidCollisionShape, the bottom half of the block, for
 * a strider whose bottom is above it and with no lava over it.
 */
class JavaStriderLiquidCollisionTest {
    private static final int STRIDER = 100;
    private static final float RIDDEN_SPEED = (float) ((double) 0.175F * 0.55F);

    /** A strider on a lava pool rests on the sources' 0.5 tops instead of sinking under gravity. */
    @Test
    void aStriderWalksOnTheSurfaceOfALavaPool() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.LAVA.defaultState());
            }
            Vec3 position = new Vec3(0.5, FLOOR_Y + 0.5D, 0.5);
            var strider = mount(f, position);
            strider.commitFluidHeights(ClientFluidHeights.capture(
                    f.player,
                    GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, 0.9F, 1.7F)));
            strider.onGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            Vec3 velocity = new Vec3(0, -0.0784000015258789D, 0);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
            for (int tick = 0; tick < 3; tick++) {
                // Gravity is stopped at the source's top; at zero yaw the ground input moves +Z.
                Vec3 movement = new Vec3(0, 0, velocity.z + RIDDEN_SPEED);
                position = position.add(movement);
                var result = f.rideTick(position, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                // Strider#floatStrider: above the column with air over it, the strider stays grounded, unscaled.
                velocity = new Vec3(0, -0.08D * 0.98F, movement.z * (0.6F * 0.91F));
                var carried = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
                assertEquals(1, carried.size());
                Vec3 actual = carried.iterator().next();
                assertEquals(velocity.y, actual.y, 1.0E-12, "tick " + tick);
                assertEquals(velocity.z, actual.z, 1.0E-12, "tick " + tick);
            }
        }
    }

    enum Neighbour {
        /** A source with air over it: a 0.5-high wall to a strider that started above it. */
        SOURCE(true),
        /** LiquidBlock gives a flowing block no shape. */
        FLOWING(false),
        /** EntityCollisionContext#canStandOnFluid rejects a source with lava over it. */
        SOURCE_UNDER_LAVA(false);

        final boolean blocks;

        Neighbour(boolean blocks) {
            this.blocks = blocks;
        }
    }

    /**
     * flag-1 2026-10-30: a strider sinking below a source's top beside it is stopped at the source's face. The
     * collision context's bottom is the strider's position before the move, above the column.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Neighbour.class)
    void aSinkingStriderCollidesWithTheTopOfAnAdjacentLavaSource(Neighbour neighbour) throws Exception {
        try (var f = new JavaTickFixture()) {
            int lava = neighbour == Neighbour.FLOWING
                    ? DataTables.defaults().registry().with(BlockIds.LAVA.defaultState(), "level", "2")
                    : BlockIds.LAVA.defaultState();
            for (int x = -2; x <= 3; x++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, 0), lava);
                if (neighbour == Neighbour.SOURCE_UNDER_LAVA)
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y + 1, 0), BlockIds.LAVA.defaultState());
            }
            // Over the air column at z=-1, 0.01 short of the lava; no fluid touches the body at the start.
            Vec3 start = new Vec3(0.5, FLOOR_Y + 0.55D, -0.46D);
            var strider = mount(f, start);
            strider.noAI = true;
            strider.onGround = false;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
            Vec3 velocity = new Vec3(0, -0.1D, 0.05D);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);

            // Entity#collide moves Y first, below the source's top, then Z against its face.
            double attemptedZ = velocity.z + RIDDEN_SPEED * 0.1F;
            double maxZ = start.z + (double) (0.9F / 2.0F);
            double z = neighbour.blocks ? 0.0D - maxZ : attemptedZ;
            var result = f.rideTick(start.add(0, velocity.y, z), false);
            assertTrue(exact(result), describe(result));
        }
    }

    private static PacketEntityStrider mount(JavaTickFixture f, Vec3 position) throws Exception {
        f.place(position, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(STRIDER, EntityTypeIds.STRIDER, position, 0, 0, 0);
        var strider = (PacketEntityStrider) f.player.compensatedEntities.getEntity(STRIDER);
        strider.hasSaddle = true;
        f.player
                .getInventory()
                .inventory
                .setHeldItem(ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
        f.setPassengers(STRIDER, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        return strider;
    }
}
