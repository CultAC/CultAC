package ac.cult.cultac.protocol;

import io.netty.buffer.ByteBuf;

/**
 * Finds what players write in the values a packet carries, at one client protocol: an item stack's name, lore, book
 * and opaque data, which go with their components, and the text inside the components a stack keeps; entity data's
 * text and command strings; the stacks inside particles.
 *
 * <p>Each method reads one value from the input's reader index to its end and reports what to cut as input positions;
 * it throws when the value does not read. Nothing it reports overlaps.
 */
public interface PlayerTextCodec {
    /** Where a value's cuts go. */
    interface Cuts {
        /** The input's bytes from {@code start} to {@code end} are sent as {@code replacement}. */
        void cut(int start, int end, byte[] replacement);

        /**
         * A profile's one {@code textures} property value cut to its skin alone; null when it names no skin to keep,
         * and the profile goes.
         */
        String skin(String textures);

        /** Charges {@code units} values walked to the packet's budget; throws when it runs out. */
        void work(int units);
    }

    /** A stack as a packet carries it: its count first, and nothing more when the count is not positive. */
    void stack(ByteBuf input, Cuts cuts);

    /** A stack a client sends from the creative inventory: from 1.21.5 on, each component's data is length-prefixed. */
    void creativeStack(ByteBuf input, Cuts cuts);

    /** Entity data: entries up to and including the {@code 0xFF} that ends them. */
    void entityData(ByteBuf input, Cuts cuts);

    /** A particle: its type, then its options. */
    void particle(ByteBuf input, Cuts cuts);
}
