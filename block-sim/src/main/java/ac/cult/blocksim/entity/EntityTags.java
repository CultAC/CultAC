package ac.cult.blocksim.entity;

import ac.cult.blocksim.data.DataTables;

/** Default model memberships for the entity tags used by movement geometry. */
public enum EntityTags {
    POWDER_SNOW_WALKABLE_MOBS("powder_snow_walkable_mobs"),
    NOT_AFFECTED_BY_GEYSERS("not_affected_by_geysers");

    private static final int[] MEMBERSHIP = membership();
    private final String key;

    EntityTags(String key) { this.key = "entity_type:minecraft:" + key; }

    public boolean test(int type) { return (MEMBERSHIP[type] & (1 << ordinal())) != 0; }

    private static int[] membership() {
        var types = EntityTypes.defaults().types();
        int[] result = new int[types.size()];
        var defaults = DataTables.defaults().tags();
        for (var tag : values()) {
            var members = defaults.getOrDefault(tag.key, java.util.Set.of());
            for (var type : types)
                if (members.contains(type.key())) result[type.id()] |= 1 << tag.ordinal();
        }
        return result;
    }
}
