package ac.cult.blocksim.interaction;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.data.AdventurePredicateData;
import ac.cult.blocksim.data.nbt.NbtPredicates;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimLevel;

/** Basic adventure block/tag/state predicates, with NBT limited to retained prediction fields. */
public final class AdventurePredicates implements AdventureRuleMatcher {
    private final DataTables data;
    public AdventurePredicates(DataTables data) { this.data = java.util.Objects.requireNonNull(data); }

    @Override
    public boolean test(SimItemStack stack, String component, SimLevel level, BlockPos pos) {
        for (var predicate : AdventurePredicateData.read(stack.components(), component))
            if (matches(predicate, level, pos)) return true;
        return false;
    }

    /** Required NBT fields absent from the sparse projection do not match. */
    private boolean matches(AdventurePredicateData.Predicate predicate, SimLevel level, BlockPos pos) {
        if (!matchesState(predicate, level, level.stateAt(pos))) return false;
        var nbt = predicate.nbt();
        if (nbt == null) return true;
        var entity = level.blockEntityAt(pos);
        return entity != null && NbtPredicates.compare(nbt, entity.fullMetadataAt(pos), true);
    }

    private boolean matchesState(AdventurePredicateData.Predicate predicate, SimLevel level, int state) {
        var blocks = predicate.fields().get("blocks");
        if (blocks != null && !HolderSets.contains(data, "block", blocks, level.registry().block(state).key())) return false;
        return predicate.properties() == null || matchesProperties(predicate.properties(), level, state);
    }

    /** PropertyMatcher and ExactMatcher/RangedMatcher preserve Property.getValue and Comparable ordering. */
    private boolean matchesProperties(java.util.List<AdventurePredicateData.Property> properties, SimLevel level, int state) {
        var registry = level.registry();
        for (var matcher : properties) {
            String name = matcher.name();
            if (!registry.hasProperty(state, name)) return false;
            String actual = registry.value(state, name);
            if (matcher.exact() != null) {
                String expected = registry.parsedPropertyValue(state, name, matcher.exact());
                if (expected == null || registry.comparePropertyValues(state, name, actual, expected) != 0) return false;
            } else {
                if (matcher.minimum() != null) {
                    String minimum = registry.parsedPropertyValue(state, name, matcher.minimum());
                    if (minimum == null || registry.comparePropertyValues(state, name, actual, minimum) < 0) return false;
                }
                if (matcher.maximum() != null) {
                    String maximum = registry.parsedPropertyValue(state, name, matcher.maximum());
                    if (maximum == null || registry.comparePropertyValues(state, name, actual, maximum) > 0) return false;
                }
            }
        }
        return true;
    }
}
