package ac.cult.cultac.utils.data;

/** Policy accounting, independent of cheating flags and timer balances. */
public final class ItemUseDesyncHistory {
    private boolean[] history = new boolean[100];
    private int size;
    private int cursor;
    private int desynced;
    private double percent = 20;

    public void configure(int length, double percent) {
        if (length < 1 || !Double.isFinite(percent) || percent < 0 || percent > 100)
            throw new IllegalArgumentException("Invalid item-use desync policy");
        if (length != history.length) {
            var retained = new boolean[Math.min(size, length)];
            for (int i = 0; i < retained.length; i++)
                retained[i] = history[(cursor - retained.length + i + history.length) % history.length];
            history = new boolean[length];
            clear();
            for (boolean value : retained) add(value);
        }
        this.percent = percent;
    }

    public boolean wouldExceed(boolean bad) {
        int count = desynced + (bad ? 1 : 0) - (size == history.length && history[cursor] ? 1 : 0);
        return count * 100.0 > percent * Math.min(size + 1, history.length);
    }

    public void add(boolean bad) {
        if (size == history.length) {
            if (history[cursor]) desynced--;
        } else size++;
        history[cursor] = bad;
        if (bad) desynced++;
        cursor = (cursor + 1) % history.length;
    }

    public void clear() {
        size = cursor = desynced = 0;
    }

    public int size() {
        return size;
    }

    public int desynced() {
        return desynced;
    }
}
