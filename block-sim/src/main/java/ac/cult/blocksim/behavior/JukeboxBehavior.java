package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;

public final class JukeboxBehavior extends BlockBehavior {
    @Override public void setPlacedBy(SimLevel level, int state, BlockPos pos, SimPlayer player, SimItemStack stack) {
        var tag = stack.components().get("minecraft:block_entity_data");
        if (tag != null && tag.getAsJsonObject().has("RecordItem")) level.setBlock(pos, level.registry().with(state, "has_record", "true"), 2);
    }
    @Override public SimInteraction useWithoutItem(int state, UseContext context) {
        var entity = context.level().blockEntityAt(context.clickedPos());
        // JukeboxBlockEntity.popOutTheItem has a server-side guard.
        return bool(context.level(), state, "has_record") && entity != null && entity.type().equals("minecraft:jukebox")
            ? SimInteraction.SUCCESS : SimInteraction.PASS;
    }
    @Override public SimInteraction useItemOn(int state, UseContext context) {
        return !bool(context.level(), state, "has_record") && context.player().hand(context.hand()).components().has("minecraft:jukebox_playable")
            ? SimInteraction.SUCCESS : SimInteraction.TRY_WITH_EMPTY_HAND;
    }
    @Override public int ownSignal(SimLevel level, int state, BlockPos pos) {
        var entity = level.blockEntityAt(pos);
        var playing = entity == null ? null : entity.data().get("song_playing");
        return entity != null && entity.type().equals("minecraft:jukebox") && playing != null && playing.getAsBoolean() ? 15 : 0;
    }
}
