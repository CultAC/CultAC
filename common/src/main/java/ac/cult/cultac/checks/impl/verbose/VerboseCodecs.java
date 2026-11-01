package ac.cult.cultac.checks.impl.verbose;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.manager.datastore.SharedVerboseTags;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import java.util.stream.IntStream;
import org.jetbrains.annotations.Nullable;

/**
 * Cult's verbose template tags plus the write-side value encoders that pair
 * with them. Registered once; checks declare templates referencing these tag
 * names and write values through the matching {@code VerboseCodecs.x(...)}
 * encoder.
 */
public final class VerboseCodecs {
    static {
        SharedVerboseTags.registerEnum("face", Direction.values(), false);
        var digging = PlayerAction.values();
        SharedVerboseTags.registerEnum("digging", digging, false);
        SharedVerboseTags.registerEnum("digging_lower", digging, true);
        SharedVerboseTags.registerEnum("clicktype", WindowClickType.values(), false);
        SharedVerboseTags.registerEnum("clicktype_lower", WindowClickType.values(), true);
        SharedVerboseTags.registerEnum("entityaction", PlayerCommandAction.values(), false);
        SharedVerboseTags.registerEnum("hand", Hand.values(), false);
        var blocks = DataTables.defaults().registry().blocks();
        SharedVerboseTags.register(
                "block", blocks.stream().map(BlockDefinition::key).toList(), false, "unknown", BlockIds.AIR.key());
        var items = ModelRegistryData.load(ProtocolVersion.V26_3).registry("minecraft:item");
        SharedVerboseTags.register(
                "item", IntStream.range(0, items.size()).mapToObj(items::name).toList(), false, "", null);
        var entities = EntityTypes.defaults();
        SharedVerboseTags.register(
                "entity",
                IntStream.range(0, entities.types().size())
                        .mapToObj(i -> entities.byId(i).key())
                        .toList(),
                true,
                "unknown",
                "unknown");
        SharedVerboseTags.register("packet", SharedVerboseTags.Primitive.PACKET);
        SharedVerboseTags.register("offset", SharedVerboseTags.Primitive.OFFSET);
        SharedVerboseTags.register("stdnum", SharedVerboseTags.Primitive.NUMBER);
    }

    private VerboseCodecs() {}

    /**
     * Force tag registration. Call before templates parse and before history
     * rows render; class initialization performs the actual work once.
     */
    public static void ensureRegistered() {
        // Static initializer has run by the time this returns.
    }

    /** Keep stored action IDs aligned with the server's existing verbose decoder. */
    public static int digging(@Nullable PlayerAction action) {
        return action == null ? 0 : action.wireId(ProtocolVersion.V26_3) + 1;
    }
}
