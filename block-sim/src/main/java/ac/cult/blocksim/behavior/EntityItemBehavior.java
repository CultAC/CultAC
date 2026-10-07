package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.BlockRaycast;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;

/** Source-proven client branches; spawning the server entity is outside local action prediction. */
public final class EntityItemBehavior implements ItemBehavior {
    public enum Kind { SPAWN_EGG, ARMOR_STAND, END_CRYSTAL }
    private final ItemBehavior defaults;
    private final Kind kind;
    private final EntityTypes types;
    public EntityItemBehavior(ItemBehavior defaults, Kind kind, EntityTypes types) {
        this.defaults = defaults; this.kind = kind; this.types = types;
    }
    private boolean eggCanSpawn(UseContext context) {
        var data = context.stack().components().get("minecraft:entity_data");
        if (data == null) return false;
        var type = types.byKey(data.getAsJsonObject().get("id").getAsString());
        return type != null && context.level().canSpawn(type);
    }
    @Override public SimInteraction useOn(UseContext context) {
        if (kind == Kind.SPAWN_EGG) return eggCanSpawn(context) ? SimInteraction.SUCCESS : SimInteraction.FAIL;
        var level = context.level();
        if (kind == Kind.ARMOR_STAND) {
            if (context.clickedFace() == Direction.DOWN) return SimInteraction.FAIL;
            var target = new PlacementContext(context).clickedPos();
            var box = types.byKey("minecraft:armor_stand").boxAt(new Vec3(target.x() + .5, target.y(), target.z() + .5));
            if (!level.noCollision(box) || level.hasEntityIn(box)) return SimInteraction.FAIL;
        } else {
            String support = level.registry().block(level.stateAt(context.clickedPos())).key();
            if (!support.equals("minecraft:obsidian") && !support.equals("minecraft:bedrock")) return SimInteraction.FAIL;
            var target = context.clickedPos().relative(Direction.UP);
            if (!level.registry().facts(level.stateAt(target)).has(ac.cult.blocksim.data.StateFacts.AIR)
                || level.hasEntityIn(new Box(target.x(), target.y(), target.z(), target.x() + 1, target.y() + 2, target.z() + 1)))
                return SimInteraction.FAIL;
        }
        context.stack().shrink(1);
        return SimInteraction.SUCCESS;
    }
    @Override public SimInteraction use(UseContext context) {
        if (kind != Kind.SPAWN_EGG) return defaults.use(context);
        var pick = BlockRaycast.playerPick(context.level(), context.player(), BlockRaycast.Fluid.SOURCE_ONLY);
        if (pick.miss()) return SimInteraction.PASS;
        return eggCanSpawn(context) ? SimInteraction.SUCCESS : SimInteraction.FAIL;
    }
}
