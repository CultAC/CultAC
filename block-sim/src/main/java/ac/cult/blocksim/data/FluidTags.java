package ac.cult.blocksim.data;

import ac.cult.blocksim.engine.SimFluidState;
import java.util.Set;

/** Default fluid memberships from the generated client tables. */
public enum FluidTags {
    WATER("water"), LAVA("lava"), ENTITY_FLOATABLE("entity_floatable");

    private final String key;
    private final Set<String> members;

    FluidTags(String path) {
        key = "minecraft:" + path;
        members = DataTables.defaults().tags().getOrDefault("fluid:" + key, Set.of());
    }

    public String key() { return key; }
    public boolean test(SimFluidState state) { return members.contains(state.type()); }
}
