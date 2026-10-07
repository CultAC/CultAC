package ac.cult.blocksim;

import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;
import java.util.List;
import java.util.Map;

/** Complete action effects; the adapter applies them at the existing prediction boundary. */
public record SimResult(SimInteraction interaction, boolean accepted, List<BlockWrite> writes,
                        Map<BlockPos, StatePossibilities> retained, Map<BlockPos, BlockEntityData> blockEntities,
                        SimPlayer player, SimCooldowns cooldowns, BreakSession.State breaking,
                        List<BreakSession.Packet> breakActions, Decline decline) {
    public SimResult {
        writes = List.copyOf(writes); retained = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(retained));
        // A null entity value denotes removal.
        blockEntities = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(blockEntities));
        breakActions = List.copyOf(breakActions);
    }
}
