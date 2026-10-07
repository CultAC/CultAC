package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.StateFacts;

/** Vanilla fluid type/state facts, evaluated by the pinned generator for each wire state. */
public record SimFluidState(String type, int amount, boolean falling, int legacyBlock, boolean source) {
    public static SimFluidState of(StateFacts facts) {
        return new SimFluidState(facts.fluid(), facts.fluidAmount(), facts.fallingFluid(), facts.fluidBlock(), facts.has(StateFacts.FLUID_SOURCE));
    }
    public boolean isEmpty() { return type.equals("minecraft:empty"); }
    public boolean is(String fluidType) { return type.equals(fluidType); }
    public boolean isSource() { return source; }
    public float ownHeight() { return isEmpty() ? 0.0F : amount / 9.0F; }
    public float height(SimFluidState above) { return isEmpty() ? 0.0F : isSame(above) ? 1.0F : ownHeight(); }
    public boolean isSourceOfType(String fluidType) { return is(fluidType) && isSource(); }
    /** WaterFluid/LavaFluid.isSame include the corresponding source and flowing types. */
    public boolean isSame(SimFluidState other) { return family(type).equals(family(other.type)); }
    private static String family(String type) {
        return switch (type) {
            case "minecraft:flowing_water" -> "minecraft:water";
            case "minecraft:flowing_lava" -> "minecraft:lava";
            default -> type;
        };
    }

    public int createLegacyBlock() { return legacyBlock; }
}
