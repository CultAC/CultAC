package ac.cult.cultac.checks.impl.verbose;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.grim.grimac.api.storage.verbose.VerboseSchema;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cult's verbose template tags plus the write-side value encoders that pair
 * with them. Registered once; checks declare templates referencing these tag
 * names and write values through the matching {@code VerboseCodecs.x(...)}
 * encoder.
 */
public final class VerboseCodecs {
    /** {@code {packet}} sentinel for "no packet". */
    public static final int PACKET_NONE = Integer.MIN_VALUE;
    /** {@code {packet}} sentinel for transaction/pong. */
    public static final int PACKET_TRANSACTION = Integer.MIN_VALUE + 1;

    static {
        VerboseTags.registerEnum("face", Direction.values());
        var version = ProtocolVersion.V26_3;
        var digging = java.util.Arrays.stream(PlayerAction.values())
                .filter(action ->
                        (action != PlayerAction.CHANGE_DESTROY_DIRECTION || version.atLeast(ProtocolVersion.V26_3))
                                && (action != PlayerAction.STAB || version.atLeast(ProtocolVersion.V1_21_11)))
                .toArray(PlayerAction[]::new);
        VerboseTags.registerEnum("digging", digging);
        VerboseTags.registerEnumLower("digging_lower", digging);
        VerboseTags.registerEnum("clicktype", ac.cult.cultac.utils.inventory.inventory.WindowClickType.values());
        VerboseTags.registerEnumLower(
                "clicktype_lower", ac.cult.cultac.utils.inventory.inventory.WindowClickType.values());
        VerboseTags.registerEnum("entityaction", PlayerCommandAction.values());
        VerboseTags.registerEnum("hand", Hand.values());
        VerboseTags.register(
                "block", List.of(VerboseSchema.TypeTag.ZZ), (in, ctx, out, fmt) -> out.append(blockName(in.rzz())));
        VerboseTags.register(
                "item", List.of(VerboseSchema.TypeTag.ZZ), (in, ctx, out, fmt) -> out.append(itemTypeName(in.rzz())));
        VerboseTags.register(
                "packet", List.of(VerboseSchema.TypeTag.STR), (in, ctx, out, fmt) -> out.append(in.rstr()));
        VerboseTags.register(
                "entity",
                List.of(VerboseSchema.TypeTag.VI),
                (in, ctx, out, fmt) -> out.append(entityTypeName(in.rvi())));
        VerboseTags.register(
                "offset",
                List.of(VerboseSchema.TypeTag.F64),
                (in, ctx, out, fmt) -> out.append(humanFormattedOffset(in.rf64())));
        VerboseTags.register(
                "stdnum",
                List.of(VerboseSchema.TypeTag.F64),
                (in, ctx, out, fmt) -> out.append(formatNumberStandard(in.rf64())));
    }

    private VerboseCodecs() {}

    /**
     * Force tag registration. Call before templates parse and before history
     * rows render; class initialization performs the actual work once.
     */
    public static void ensureRegistered() {
        // Static initializer has run by the time this returns.
    }

    /** Null-safe enum encoding for the {@code registerEnum} family of tags. */
    public static int enumId(@Nullable Enum<?> value) {
        return VerboseTags.enumId(value);
    }

    /** Keep stored action IDs aligned with the server's existing verbose decoder. */
    public static int digging(@Nullable PlayerAction action) {
        return action == null ? 0 : action.wireId(ProtocolVersion.V26_3) + 1;
    }

    /**
     * Encoder for {@code {block}}: the protocol-defined per-version block id,
     * so any product following the MC protocol decodes the same name.
     * {@code -1} when the block has no id in the player's version
     * (server-only states render as {@code unknown}).
     */
    public static int block(@NotNull BlockDefinition type, @NotNull ClientVersion version) {
        return DataTables.defaults().registry().blockIndex(type.defaultState());
    }

    /** Encoder for {@code {item}}: the server's built-in registry item id. */
    public static int item(@NotNull ac.cult.blocksim.data.ItemDefinition type, @NotNull ClientVersion version) {
        return type.id();
    }

    /** Encoder for {@code {entity}}: the shared model entity-type id. */
    public static int entity(int type, @NotNull ClientVersion version) {
        return type;
    }

    private static @NotNull String blockName(int id) {
        if (id < 0) return "unknown";
        var blocks = DataTables.defaults().registry().blocks();
        return id < blocks.size() ? blocks.get(id).key() : BlockIds.AIR.key();
    }

    private static @NotNull String itemTypeName(int id) {
        if (id < 0) return "";
        var items = ac.cult.cultac.protocol.data.ModelRegistryData.load(ProtocolVersion.V26_3)
                .registry("minecraft:item");
        return id >= items.size() ? "unknown(" + id + ")" : items.name(id);
    }

    private static @NotNull String entityTypeName(int entityId) {
        var types = ac.cult.blocksim.entity.EntityTypes.defaults();
        return entityId < 0 || entityId >= types.types().size()
                ? "unknown"
                : types.byId(entityId).key();
    }

    private static @NotNull String humanFormattedOffset(double offset) {
        String humanFormattedOffset;
        if (offset < 0.001) { // 1.129E-3
            humanFormattedOffset = String.format("%.4E", offset);
            // Squeeze out an extra digit here by E-03 to E-3
            humanFormattedOffset = humanFormattedOffset.replace("E-0", "E-");
        } else {
            // 0.00112945678 -> .001129
            humanFormattedOffset = String.format("%6f", offset);
            // I like the leading zero, but removing it lets us add another digit to the end
            humanFormattedOffset = humanFormattedOffset.replace("0.", ".");
        }
        return humanFormattedOffset;
    }

    private static @NotNull String formatNumberStandard(double value) {
        double abs = Math.abs(value);
        if (abs < 1e-7) return "0";
        String formatted;
        if (abs < 0.001) {
            formatted = String.format("%.4E", value);
            return formatted.replace("E-0", "E-");
        }
        formatted = String.format("%6f", value);
        return formatted.replace("0.", ".");
    }
}
