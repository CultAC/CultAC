package ac.cult.blocksim.data;

import java.util.List;

/** Generated empty-context facts; dynamic bits require a behavior implementation. */
public record StateFacts(float destroyTime, int flags, String fluid, int fluidAmount,
                         boolean fallingFluid, int fluidBlock, int sturdyBits, int dynamicBits,
                         List<Box> collision, List<Box> outline, List<Box> support, List<Box> interaction,
                         float friction, float speedFactor, float jumpFactor, PushReaction pushReaction) {
    public enum PushReaction { PUSH_PULL, PUSH, POPPED, IMMOVEABLE, IGNORE_ENTITY }
    public static final int AIR = 1, LIQUID = 2, REPLACEABLE = 4, SOLID = 8, FLUID_REPLACEABLE = 16,
        CONDUCTOR = 32, SIGNAL_SOURCE = 64, CORRECT_TOOL = 128, FLUID_SOURCE = 256, SOLID_RENDER = 512,
        BLOCK_ENTITY = 1024, FULL_COLLISION = 2048, PROPAGATES_SKYLIGHT = 4096,
        LARGE_COLLISION = 8192, SUFFOCATING = 16384;
    public static final int DYNAMIC_ATTRIBUTES = 1, DYNAMIC_COLLISION = 2, DYNAMIC_OUTLINE = 4,
        DYNAMIC_SUPPORT = 8, DYNAMIC_INTERACTION = 16, DYNAMIC_STURDY = 32;

    public StateFacts {
        collision = List.copyOf(collision);
        outline = List.copyOf(outline);
        support = List.copyOf(support);
        interaction = List.copyOf(interaction);
    }
    public boolean has(int mask) { return (flags & mask) != 0; }
}
