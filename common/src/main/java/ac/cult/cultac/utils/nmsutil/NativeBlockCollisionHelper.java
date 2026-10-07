package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.EntityCollisionContext;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.ComplexCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.NoCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.List;

public final class NativeBlockCollisionHelper {
    private NativeBlockCollisionHelper() {}

    public static CollisionBox getCollisionBox(CultPlayer player, int state, int x, int y, int z) {
        return getCollisionBox(player, state, x, y, z, player == null ? Double.NaN : player.y);
    }

    public static CollisionBox getCollisionBox(CultPlayer player, int state, int x, int y, int z, double entityBottom) {
        return getCollisionBox(player, state, x, y, z, entityBottom, JavaCollisionState.current(player));
    }

    public static CollisionBox getCollisionBox(
            CultPlayer player, int state, int x, int y, int z, double entityBottom, JavaCollisionState actor) {
        if (player == null
                || player.compensatedWorld == null
                || state < 0
                || DataTables.defaults().registry().facts(state).has(StateFacts.AIR)) {
            return NoCollisionBox.INSTANCE;
        }

        if (BlockIds.is(state, BlockIds.MOVING_PISTON) && player.compensatedWorld.pistons.usesLegacyCollision()) {
            // VoxelShape discards degenerate boxes, but 1.8's AABB collision loop
            // can clip movement crossing a zero-thickness piston face.
            return player.compensatedWorld.pistons.getLegacyMovingPistonCollisionBox(new BlockPos(x, y, z));
        }

        return fromShape(getCollisionShape(player, state, x, y, z, entityBottom, actor), x, y, z);
    }

    public static CollisionBox getSelectionBox(CultPlayer player, int state, int x, int y, int z) {
        if (player == null
                || player.compensatedWorld == null
                || state < 0
                || DataTables.defaults().registry().facts(state).has(StateFacts.AIR)) {
            return NoCollisionBox.INSTANCE;
        }

        return fromShape(getSelectionShape(player, state, x, y, z), x, y, z);
    }

    public static VoxelShape getCollisionShape(CultPlayer player, int state, int x, int y, int z) {
        return getCollisionShape(player, state, x, y, z, player == null ? Double.NaN : player.y);
    }

    public static VoxelShape getCollisionShape(CultPlayer player, int state, int x, int y, int z, double entityBottom) {
        return getCollisionShape(player, state, x, y, z, entityBottom, JavaCollisionState.current(player));
    }

    public static VoxelShape getCollisionShape(
            CultPlayer player, int state, int x, int y, int z, double entityBottom, JavaCollisionState actor) {
        if (player == null
                || player.compensatedWorld == null
                || state < 0
                || DataTables.defaults().registry().facts(state).has(StateFacts.AIR)) {
            return Shapes.empty();
        }

        if (BlockIds.is(state, BlockIds.POWDER_SNOW) && actor != null) {
            return actor.powderSnowShape(y, entityBottom);
        }

        if (BlockIds.is(state, BlockIds.MOVING_PISTON)) {
            return player.compensatedWorld.pistons.getMovingPistonCollisionShape(new BlockPos(x, y, z));
        }

        return player.compensatedWorld
                .geometry()
                .collision(state, new BlockPos(x, y, z), entityContext(player, entityBottom));
    }

    public static VoxelShape getSelectionShape(CultPlayer player, int state, int x, int y, int z) {
        if (player == null
                || player.compensatedWorld == null
                || state < 0
                || DataTables.defaults().registry().facts(state).has(StateFacts.AIR)) {
            return Shapes.empty();
        }

        return player.compensatedWorld
                .geometry()
                .outline(state, new BlockPos(x, y, z), entityContext(player, player.y));
    }

    /** VoxelShape.toAabbs uses this same ordered callback, without needing native box objects. */
    public static List<SimpleCollisionBox> toBoxes(VoxelShape shape) {
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        for (var box : shape.boxes())
            boxes.add(
                    SimpleCollisionBox.between(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
        return boxes;
    }

    public static CollisionBox fromShape(VoxelShape shape, int x, int y, int z) {
        if (shape == null || shape.isEmpty()) {
            return NoCollisionBox.INSTANCE;
        }

        List<SimpleCollisionBox> boxes = toBoxes(shape);
        if (boxes.isEmpty()) {
            return NoCollisionBox.INSTANCE;
        }

        if (boxes.size() == 1) {
            return boxes.get(0).move(x, y, z);
        }

        SimpleCollisionBox[] collisionBoxes = new SimpleCollisionBox[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            collisionBoxes[i] = boxes.get(i).move(x, y, z);
        }
        return new ComplexCollisionBox(collisionBoxes);
    }

    public static EntityCollisionContext entityContext(CultPlayer player, double entityBottom) {
        if (player == null) return EntityCollisionContext.emptyContext();
        String key = heldItem(player);
        return new EntityCollisionContext(entityBottom, player.isSneaking, 0, false, false, key, false, false, false);
    }

    private static String heldItem(CultPlayer player) {
        try {
            var held = player.getInventory().getHeldItem();
            return held == null || held.isEmpty() ? "minecraft:air" : held.itemKey();
        } catch (RuntimeException ignored) {
            return "minecraft:air";
        }
    }
}
