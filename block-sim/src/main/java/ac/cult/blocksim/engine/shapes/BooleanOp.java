package ac.cult.blocksim.engine.shapes;

/** The finite BooleanOp truth tables whose empty/empty cell is empty. */
public enum BooleanOp {
    FALSE(0), ONLY_SECOND(2), ONLY_FIRST(4), NOT_SAME(6), AND(8), SECOND(10), FIRST(12), OR(14);
    private final int table;
    BooleanOp(int table) { this.table = table; }
    public boolean apply(boolean first, boolean second) { return (table & (1 << ((first ? 2 : 0) | (second ? 1 : 0)))) != 0; }
}
