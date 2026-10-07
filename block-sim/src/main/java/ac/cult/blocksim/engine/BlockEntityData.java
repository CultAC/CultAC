package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.nbt.NbtValue;

/** Immutable block-entity prediction inputs; savedData is a sparse NBT projection. */
public record BlockEntityData(String type, Components data, NbtValue.Compound savedData) {
    public BlockEntityData(String type, Components data) { this(type, data, null); }
    public BlockEntityData { java.util.Objects.requireNonNull(type); java.util.Objects.requireNonNull(data); }
    public boolean isSign() { return type.equals("minecraft:sign") || type.equals("minecraft:hanging_sign"); }

    /** Basic adventure NBT matching can inspect retained fields, type and position. */
    public NbtValue.Compound fullMetadataAt(BlockPos pos) {
        if (savedData == null) throw new IllegalStateException("Missing typed client block-entity NBT for " + type + " at " + pos);
        var fields = new java.util.HashMap<>(savedData.values());
        fields.put("id", new NbtValue.Text(type));
        fields.put("x", new NbtValue.Numeric(NbtValue.Kind.INT, pos.x()));
        fields.put("y", new NbtValue.Numeric(NbtValue.Kind.INT, pos.y()));
        fields.put("z", new NbtValue.Numeric(NbtValue.Kind.INT, pos.z()));
        return new NbtValue.Compound(fields);
    }
}
