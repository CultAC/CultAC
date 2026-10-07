package ac.cult.cultac.network.packet;

import java.util.BitSet;
import java.util.List;

/** An owned light snapshot; no frame, native codec or mutable caller storage is retained. */
public record LightValues(
        BitSet skyYMask,
        BitSet blockYMask,
        BitSet emptySkyYMask,
        BitSet emptyBlockYMask,
        List<byte[]> skyUpdates,
        List<byte[]> blockUpdates) {
    public LightValues {
        skyYMask = copy(skyYMask);
        blockYMask = copy(blockYMask);
        emptySkyYMask = copy(emptySkyYMask);
        emptyBlockYMask = copy(emptyBlockYMask);
        skyUpdates = copy(skyUpdates);
        blockUpdates = copy(blockUpdates);
    }

    private static BitSet copy(BitSet bits) {
        return (BitSet) bits.clone();
    }

    private static List<byte[]> copy(List<byte[]> arrays) {
        return arrays.stream().map(byte[]::clone).toList();
    }

    @Override
    public BitSet skyYMask() {
        return copy(skyYMask);
    }

    @Override
    public BitSet blockYMask() {
        return copy(blockYMask);
    }

    @Override
    public BitSet emptySkyYMask() {
        return copy(emptySkyYMask);
    }

    @Override
    public BitSet emptyBlockYMask() {
        return copy(emptyBlockYMask);
    }

    @Override
    public List<byte[]> skyUpdates() {
        return copy(skyUpdates);
    }

    @Override
    public List<byte[]> blockUpdates() {
        return copy(blockUpdates);
    }
}
