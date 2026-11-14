package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Offsets;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.util.Arrays;

/**
 * One behavior's shapes of offset blocks, kept per (state, offset index) for one registry. A shape is the state's
 * unmoved shape moved by the position's offset, whose x, y and z come from seed bits 0-3, 4-7 and 8-11. Shapes are
 * immutable with final fields, so a racy fill publishes them safely; a lost store is rebuilt. A block holds at most
 * its state count times 256 (XZ) or 4096 (XYZ) shapes; a failed build is never kept.
 */
final class OffsetShapeTable {
    private static final long NONE = 0, XZ = 0xF0FL, XYZ = 0xFFFL;

    /** The state's shape before its offset. */
    @FunctionalInterface
    interface Source {
        VoxelShape build(SimLevel level, int state);
    }

    private record Blocks(BlockRegistry registry, Block[] blocks) {}

    private static final class Block {
        final BlockDefinition definition;
        final long mask;
        final float maxHorizontal;
        final float maxVertical;
        final VoxelShape[] unmoved;
        final VoxelShape[][] states;

        Block(BlockDefinition definition, long mask) {
            this.definition = definition;
            this.mask = mask;
            var bindings = definition.bindings();
            maxHorizontal = mask == NONE ? 0 : Float.parseFloat(bindings.get("getMaxHorizontalOffset"));
            maxVertical = mask == XYZ ? Float.parseFloat(bindings.get("getMaxVerticalOffset")) : 0;
            unmoved = new VoxelShape[definition.stateCount()];
            states = new VoxelShape[definition.stateCount()][];
        }
    }

    private final Source source;
    private Blocks blocks;

    OffsetShapeTable(Source source) { this.source = source; }

    VoxelShape get(SimLevel level, int state, BlockPos pos) {
        var registry = level.registry();
        var definition = registry.block(state);
        var block = block(registry, definition);
        if (block == null) {
            var shape = source.build(level, state);
            var offset = Offsets.offset(definition, pos);
            return shape.move(offset.x(), offset.y(), offset.z());
        }
        long seed = Offsets.seed(pos.x(), 0, pos.z());
        int index = (int) (seed & block.mask);
        if (block.mask == XZ) index = index & 0xF | index >> 4 & 0xF0;
        int local = state - definition.firstState();
        var shapes = block.states[local];
        if (shapes == null) block.states[local] = shapes = new VoxelShape[block.mask == NONE ? 1 : block.mask == XZ ? 256 : 4096];
        var shape = shapes[index];
        if (shape == null) shapes[index] = shape = move(level, state, block, local, seed);
        return shape;
    }

    private VoxelShape move(SimLevel level, int state, Block block, int local, long seed) {
        var unmoved = block.unmoved[local];
        if (unmoved == null) block.unmoved[local] = unmoved = source.build(level, state).shared();
        if (block.mask == NONE) return unmoved.move(0, 0, 0);
        double y = block.mask == XYZ ? Offsets.vertical(seed, block.maxVertical) : 0;
        return unmoved.move(Offsets.horizontal(seed, 0, block.maxHorizontal), y, Offsets.horizontal(seed, 8, block.maxHorizontal));
    }

    private Block block(BlockRegistry registry, BlockDefinition definition) {
        var current = blocks;
        if (current == null || current.registry() != registry) blocks = current = new Blocks(registry, new Block[0]);
        for (var block : current.blocks()) if (block.definition == definition) return block;
        String type = definition.bindings().get("offsetType");
        long mask;
        if ("NONE".equals(type)) mask = NONE;
        else if ("XZ".equals(type)) mask = XZ;
        else if ("XYZ".equals(type)) mask = XYZ;
        else return null;
        var block = new Block(definition, mask);
        var grown = Arrays.copyOf(current.blocks(), current.blocks().length + 1);
        grown[grown.length - 1] = block;
        blocks = new Blocks(registry, grown);
        return block;
    }
}
