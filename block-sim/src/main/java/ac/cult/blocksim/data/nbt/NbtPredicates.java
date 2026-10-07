package ac.cult.blocksim.data.nbt;


public final class NbtPredicates {
    private NbtPredicates() { }
    public static boolean compare(NbtValue expected, NbtValue actual, boolean partialListMatches) {
        if (expected == actual || expected == null) return true;
        if (actual == null || expected.getClass() != actual.getClass()) return false;
        if (expected instanceof NbtValue.Compound left) {
            var right = (NbtValue.Compound)actual;
            if (right.values().size() < left.values().size()) return false;
            for (var entry : left.values().entrySet()) {
                if (!compare(entry.getValue(), right.values().get(entry.getKey()), partialListMatches)) return false;
            }
            return true;
        }
        if (expected instanceof NbtValue.Sequence left && partialListMatches) {
            var right = (NbtValue.Sequence)actual;
            if (left.values().isEmpty()) return right.values().isEmpty();
            if (right.values().size() < left.values().size()) return false;
            for (var wanted : left.values()) {
                boolean found = false;
                for (var value : right.values()) if (compare(wanted, value, true)) { found = true; break; }
                if (!found) return false;
            }
            return true;
        }
        // Native numeric classes differ even if the value is numerically equal.
        // Numeric/primitive-array records retain the kind as part of equality.
        return expected.equals(actual);
    }
}
