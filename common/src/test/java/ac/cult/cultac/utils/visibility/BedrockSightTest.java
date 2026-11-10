package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BedrockSightTest {
    private static final BlockPos BLOCK = new BlockPos(0, 65, 6);
    private static final SimpleCollisionBox CELL = new SimpleCollisionBox(0, 65, 6, 1, 66, 7);
    private static final BedrockEye.Heights STANDING =
            new BedrockEye.Heights(List.of(new BedrockEye(1.62, 1.62)), true);

    /** Full blocks at the cells added to it, and how many cells were looked up. */
    private static final class World implements BedrockSight.Cores {
        private final Set<BlockPos> blocks = new HashSet<>();
        int lookups;

        World wall(int z) {
            for (int x = -4; x <= 4; x++) for (int y = 62; y <= 70; y++) blocks.add(new BlockPos(x, y, z));
            return this;
        }

        World block(int x, int y, int z) {
            blocks.add(new BlockPos(x, y, z));
            return this;
        }

        World without(int x, int y, int z) {
            blocks.remove(new BlockPos(x, y, z));
            return this;
        }

        @Override
        public SimpleCollisionBox at(int x, int y, int z) {
            lookups++;
            return blocks.contains(new BlockPos(x, y, z)) ? new SimpleCollisionBox(x, y, z, x + 1, y + 1, z + 1) : null;
        }
    }

    private static BedrockSight.Leg walk(double fromX, double toX) {
        return new BedrockSight.Leg(new Vec3(fromX, 64, 0.5), new Vec3(toX, 64, 0.5), STANDING);
    }

    @Test
    void aBlockBehindAWallIsHiddenAndOneInTheOpenIsNot() {
        assertTrue(BedrockSight.hidden(new World().wall(3), List.of(walk(0.3, 0.6)), CELL, BLOCK));
        assertFalse(BedrockSight.hidden(new World().wall(9), List.of(walk(0.3, 0.6)), CELL, BLOCK));
        // Without any block in the way there is nothing to judge.
        assertFalse(BedrockSight.hidden(new World(), List.of(walk(0.3, 0.6)), CELL, BLOCK));
    }

    @Test
    void theTargetsOwnBlockNeverBlocks() {
        World world = new World().block(0, 65, 6);
        SimpleCollisionBox inside = new SimpleCollisionBox(0.4, 65.4, 6.4, 0.6, 65.6, 6.6);
        assertFalse(BedrockSight.hidden(world, List.of(walk(0.3, 0.6)), inside, BLOCK));
        assertTrue(BedrockSight.hidden(world, List.of(walk(0.3, 0.6)), inside, null));
    }

    @Test
    void aHoleSeenFromALaterLegKeepsTheBlockSeen() {
        // The hole is in line with the block only from the far end of the second leg.
        World world = new World().wall(3).without(1, 65, 3);
        assertTrue(BedrockSight.hidden(world, List.of(walk(-1.5, -1.2)), CELL, BLOCK));
        assertFalse(BedrockSight.hidden(world, List.of(walk(-1.5, -1.2), walk(-1.2, 2.5)), CELL, BLOCK));
    }

    @Test
    void anEyeThatTouchesABlockIsNotJudged() {
        World world = new World().wall(3);
        assertTrue(BedrockSight.hidden(world, List.of(walk(0.3, 0.6)), CELL, BLOCK));
        // The eye ends its walk on the face of a block beside it.
        world.block(1, 65, 0);
        assertFalse(BedrockSight.hidden(world, List.of(walk(0.3, 1.0)), CELL, BLOCK));
        assertTrue(BedrockSight.hidden(world, List.of(walk(0.3, 0.9)), CELL, BLOCK));
    }

    @Test
    void unknownHeightsAreJudgedAtEveryHeightBetweenThem() {
        // A wall with its lowest row open: only a low eye sees under it.
        World world = new World().wall(3);
        for (int x = -4; x <= 4; x++) world.without(x, 64, 3);
        SimpleCollisionBox low = new SimpleCollisionBox(0, 64, 6, 1, 65, 7);
        BlockPos lowBlock = new BlockPos(0, 64, 6);
        var crouched = new BedrockEye.Heights(List.of(new BedrockEye(0.4, 0.4), new BedrockEye(1.62, 1.62)), false);
        var leg = new BedrockSight.Leg(new Vec3(0.3, 64, 0.5), new Vec3(0.6, 64, 0.5), crouched);
        assertFalse(BedrockSight.hidden(world, List.of(leg), low, lowBlock));
        // A block high behind the wall is hidden from every height.
        assertTrue(BedrockSight.hidden(
                world, List.of(leg), new SimpleCollisionBox(0, 68, 6, 1, 69, 7), new BlockPos(0, 68, 6)));
        // The eye that rises with the camera is judged along its own path.
        var rising = new BedrockEye.Heights(List.of(new BedrockEye(1.27, 1.62)), true);
        assertTrue(BedrockSight.hidden(
                world,
                List.of(new BedrockSight.Leg(new Vec3(0.3, 64, 0.5), new Vec3(0.6, 64, 0.5), rising)),
                CELL,
                BLOCK));
    }

    @Test
    void aPathOrATargetThatCannotBeJudgedIsNotHidden() {
        World world = new World().wall(3);
        assertFalse(BedrockSight.hidden(world, List.of(), CELL, BLOCK));
        assertFalse(BedrockSight.hidden(world, List.of(walk(0.3, 9.3)), CELL, BLOCK));
        assertFalse(BedrockSight.hidden(world, List.of(walk(Double.NaN, 0.6)), CELL, BLOCK));
        assertFalse(BedrockSight.hidden(
                world, List.of(walk(0.3, 0.6)), new SimpleCollisionBox(0, 65, 30, 1, 66, 31), new BlockPos(0, 65, 30)));
    }

    @Test
    void onlyBlocksBetweenTheEyesAndTheTargetAreLookedUp() {
        World world = new World().wall(3);
        SimpleCollisionBox far = new SimpleCollisionBox(7, 70, 8, 8, 71, 9);
        BedrockSight.hidden(world, List.of(walk(0.3, 0.6)), far, new BlockPos(7, 70, 8));
        // The box around the walk and the target holds 8 * 6 * 9 cells.
        assertTrue(world.lookups > 0 && world.lookups < 120, world.lookups + " lookups");
    }
}
