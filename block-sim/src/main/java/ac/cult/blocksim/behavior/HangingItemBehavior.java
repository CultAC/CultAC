package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.blocksim.interaction.AdventurePredicates;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;

/** HangingEntityItem's local support checks and consumption; no entity is spawned locally. */
public final class HangingItemBehavior implements ItemBehavior {
    private final ItemBehavior defaults;
    private final AdventurePredicates adventure;
    public HangingItemBehavior(DataTables data, ItemBehavior defaults) {
        this.defaults = defaults; adventure = new AdventurePredicates(data);
    }
    @Override public SimInteraction use(UseContext context) { return defaults.use(context); }
    @Override public SimInteraction useOn(UseContext context) {
        var level = context.level(); var direction = context.clickedFace();
        var target = context.clickedPos().relative(direction);
        String type = context.usedItem().bindings().get("HangingEntityItem.type");
        boolean painting = type.equals("minecraft:painting");
        if (context.player() != null && (painting ? !direction.horizontal() : target.y() < level.minY() || target.y() > level.maxY()))
            return SimInteraction.FAIL;
        if (context.player() != null && !context.player().state().mayBuild()
            && !adventure.test(context.stack(), "minecraft:can_place_on", level, context.clickedPos())) return SimInteraction.FAIL;
        if (painting) {
            boolean fits = level.placeablePaintings().stream().anyMatch(size -> survives(level, target, direction, type, size));
            if (!fits) return SimInteraction.CONSUME;
            var specified = context.stack().components().get("minecraft:painting_variant");
            if (specified != null && !survives(level, target, direction, type, level.paintingSize(specified))) return SimInteraction.CONSUME;
        } else if (!survives(level, target, direction, type, null)) return SimInteraction.CONSUME;
        context.stack().shrink(1);
        return SimInteraction.SUCCESS;
    }
    private boolean survives(SimLevel level, BlockPos target, Direction direction, String type, PaintingSize painting) {
        var box = painting == null ? ac.cult.blocksim.entity.HangingBounds.frame(target, direction, false)
            : ac.cult.blocksim.entity.HangingBounds.painting(target, direction, painting);
        if (BlockCollisions.hasCollision(level, box, new EntityCollisionContext(box.minY(), false, 0, false, false))
            || level.hasBorderCollision(box, box.center())) return false;
        if (painting == null) {
            if (!supports(level, target.relative(direction.opposite()), direction.horizontal())) return false;
        } else {
            var support = box.move(direction.x() * -.5, direction.y() * -.5, direction.z() * -.5);
            for (int bz = (int) Math.floor(support.minZ() + 1.0E-7); bz <= (int) Math.floor(support.maxZ() - 1.0E-7); bz++)
                for (int by = (int) Math.floor(support.minY() + 1.0E-7); by <= (int) Math.floor(support.maxY() - 1.0E-7); by++)
                    for (int bx = (int) Math.floor(support.minX() + 1.0E-7); bx <= (int) Math.floor(support.maxX() - 1.0E-7); bx++)
                        if (!supports(level, new BlockPos(bx, by, bz), true)) return false;
        }
        return !level.hasHangingEntityOverlap(box, direction, type, painting == null);
    }
    private boolean supports(SimLevel level, BlockPos pos, boolean allowDiode) {
        int state = level.stateAt(pos);
        return level.registry().facts(state).has(StateFacts.SOLID) || allowDiode
            && level.registry().block(state).bindings().get("classHierarchy").contains("net.minecraft.world.level.block.DiodeBlock");
    }
}
