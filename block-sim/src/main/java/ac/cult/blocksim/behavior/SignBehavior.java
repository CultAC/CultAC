package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.UseContext;
import ac.cult.blocksim.interaction.SimInteraction;

public class SignBehavior extends BlockBehavior {
    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        var entity = context.level().blockEntityAt(context.clickedPos());
        return entity != null && entity.isSign() ? SimInteraction.CONSUME : SimInteraction.PASS;
    }

    @Override
    public SimInteraction useItemOn(int state, UseContext context) {
        var entity = context.level().blockEntityAt(context.clickedPos());
        if (entity == null || !entity.isSign()) return SimInteraction.PASS;
        if (canChain(state, context) && !canExecuteCommands(state, context, entity)) return SimInteraction.PASS;
        boolean applicator = !context.stack().isEmpty() && context.stack().definition().bindings().get("classInterfaces").contains("net.minecraft.world.item.SignApplicator") && context.player().state().mayBuild();
        var waxed = entity.data().get("is_waxed");
        return !applicator && (waxed == null || !waxed.getAsBoolean()) ? SimInteraction.CONSUME : SimInteraction.SUCCESS;
    }
    private static boolean canChain(int state, UseContext context) {
        if (context.stack().isEmpty() || !context.stack().definition().bindings().get("classHierarchy").contains("net.minecraft.world.item.HangingSignItem")) return false;
        return isFamily(context.level(), state, "CeilingHangingSignBlock") ? context.clickedFace() == Direction.DOWN
            : isFamily(context.level(), state, "WallHangingSignBlock") && !context.clickedFace().axisName().equals(facing(context.level(), state).axisName());
    }
    private static boolean canExecuteCommands(int state, UseContext context, BlockEntityData entity) {
        var waxed = entity.data().get("is_waxed");
        if (waxed == null || !waxed.getAsBoolean()) return false;
        float signYaw = context.level().registry().hasProperty(state, "rotation") ? number(context.level(), state, "rotation") * 22.5F
            : Rotation16.fromDirection(facing(context.level(), state)) * 22.5F;
        var player = context.player().state().position(); var pos = context.clickedPos();
        // Client bytecode folds the float conversion factor before multiplying.
        float playerYaw = (float)(VanillaMath.atan2(player.z() - (pos.z() + 0.5), player.x() - (pos.x() + 0.5)) * (180.0F / (float)Math.PI)) - 90.0F;
        String field = VanillaMath.degreesDifferenceAbs(signYaw, playerYaw) <= 90.0F ? "front_has_commands" : "back_has_commands";
        var commands = entity.data().get(field);
        return commands != null && commands.getAsBoolean();
    }
}
