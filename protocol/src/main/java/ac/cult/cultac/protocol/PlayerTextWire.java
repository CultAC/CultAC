package ac.cult.cultac.protocol;

import ac.cult.cultac.protocol.wire.NbtSkipper;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The reads and cuts every player text walk shares: network NBT, strings and counts, each value sent empty or absent,
 * and the cuts of one walk applied to its input.
 */
public final class PlayerTextWire {
    /** Vanilla's limit on NBT nesting: no NBT a client accepts is too deep to walk. */
    public static final int MAX_NBT_DEPTH = 512;
    /** The values and cuts one packet's walk may take; past it, the packet does not read. */
    public static final int MAX_WORK = 65536;

    private static final byte[] EMPTY_TEXT = {8, 0, 0};
    private static final byte[] EMPTY_COMPOUND = {10, 0};
    private static final byte[] ABSENT = {0};
    private static final byte[] EMPTY_STRING = {0};

    private PlayerTextWire() {}

    /** A text component in network NBT, sent as an empty text. */
    public static void text(ByteBuf input, PlayerTextCodec.Cuts cuts) {
        int start = input.readerIndex();
        nbt(input);
        cuts.cut(start, input.readerIndex(), EMPTY_TEXT);
    }

    /** Data in network NBT, sent as an empty compound. */
    public static void compound(ByteBuf input, PlayerTextCodec.Cuts cuts) {
        int start = input.readerIndex();
        nbt(input);
        cuts.cut(start, input.readerIndex(), EMPTY_COMPOUND);
    }

    /** Network NBT that may be absent, sent absent. */
    public static void absent(ByteBuf input, PlayerTextCodec.Cuts cuts) {
        int start = input.readerIndex();
        int type = input.readUnsignedByte();
        if (type == 0) return;
        NbtSkipper.skipPayload(input, type, 0, MAX_NBT_DEPTH);
        cuts.cut(start, input.readerIndex(), ABSENT);
    }

    /** A string, sent empty. */
    public static void emptyString(ByteBuf input, PlayerTextCodec.Cuts cuts) {
        int start = input.readerIndex();
        string(input);
        cuts.cut(start, input.readerIndex(), EMPTY_STRING);
    }

    /** One network NBT value that must be present. */
    public static void nbt(ByteBuf input) {
        int type = input.readUnsignedByte();
        if (type == 0) throw new MalformedPacketException("Required NBT is absent");
        NbtSkipper.skipPayload(input, type, 0, MAX_NBT_DEPTH);
    }

    /** Network NBT that may be absent. */
    public static void optionalNbt(ByteBuf input) {
        NbtSkipper.skip(input, MAX_NBT_DEPTH);
    }

    public static void string(ByteBuf input) {
        input.skipBytes(Wire.readLength(input, input.readableBytes()));
    }

    /** A count of values that each take at least a byte, each charged to the walk. */
    public static int count(ByteBuf input, PlayerTextCodec.Cuts cuts) {
        int count = Wire.readLength(input, input.readableBytes());
        cuts.work(count);
        return count;
    }

    /** {@code spent} after {@code units} more, or a throw past {@link #MAX_WORK}. */
    public static int charge(int spent, int units) {
        int total = spent + units;
        if (units < 0 || total > MAX_WORK) {
            throw new PacketResourceLimitException("Player text walk exceeds its work budget");
        }
        return total;
    }

    /** The cuts of one walk, none overlapping, applied to the input they were found in. */
    public static final class Edits {
        private record Edit(int start, int end, byte[] replacement) {}

        private final List<Edit> edits = new ArrayList<>(4);

        public void add(int start, int end, byte[] replacement) {
            edits.add(new Edit(start, end, replacement));
        }

        public boolean isEmpty() {
            return edits.isEmpty();
        }

        /** The input's bytes from {@code start} to {@code end}, with every cut made. */
        public byte[] apply(ByteBuf input, int start, int end) {
            edits.sort(Comparator.comparingInt(Edit::start));
            ByteArrayOutputStream out = new ByteArrayOutputStream(end - start);
            int at = start;
            for (Edit edit : edits) {
                copy(input, at, edit.start(), out);
                out.writeBytes(edit.replacement());
                at = edit.end();
            }
            copy(input, at, end, out);
            return out.toByteArray();
        }

        private static void copy(ByteBuf input, int start, int end, ByteArrayOutputStream out) {
            try {
                input.getBytes(start, out, end - start);
            } catch (IOException impossible) {
                throw new UncheckedIOException(impossible);
            }
        }
    }
}
