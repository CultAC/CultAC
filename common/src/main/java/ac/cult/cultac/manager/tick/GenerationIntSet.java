package ac.cult.cultac.manager.tick;

import it.unimi.dsi.fastutil.HashCommon;
import java.util.Arrays;

/**
 * An open-addressing int set whose {@link #clear()} is constant time: a slot holds a member only while its
 * stamp equals the current generation. A clear that wraps the generation to zero resets every stamp.
 */
final class GenerationIntSet {
    private static final int INITIAL_CAPACITY = 16;

    private int[] keys = new int[INITIAL_CAPACITY];
    private int[] stamps = new int[INITIAL_CAPACITY];
    private int mask = INITIAL_CAPACITY - 1;
    private int generation = 1;
    private int size;

    boolean isEmpty() {
        return size == 0;
    }

    boolean contains(int key) {
        int[] keys = this.keys;
        int[] stamps = this.stamps;
        int gen = generation;
        int slot = HashCommon.mix(key) & mask;
        while (stamps[slot] == gen) {
            if (keys[slot] == key) return true;
            slot = (slot + 1) & mask;
        }
        return false;
    }

    /** Returns true when {@code key} was not already a member. */
    boolean add(int key) {
        int gen = generation;
        int slot = HashCommon.mix(key) & mask;
        while (stamps[slot] == gen) {
            if (keys[slot] == key) return false;
            slot = (slot + 1) & mask;
        }
        keys[slot] = key;
        stamps[slot] = gen;
        if (++size * 2 > keys.length) grow();
        return true;
    }

    void clear() {
        size = 0;
        if (++generation == 0) {
            Arrays.fill(stamps, 0);
            generation = 1;
        }
    }

    private void grow() {
        int[] oldKeys = keys;
        int[] oldStamps = stamps;
        int gen = generation;
        int capacity = oldKeys.length * 2;
        int[] newKeys = new int[capacity];
        int[] newStamps = new int[capacity];
        int newMask = capacity - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldStamps[i] != gen) continue;
            int slot = HashCommon.mix(oldKeys[i]) & newMask;
            while (newStamps[slot] == gen) slot = (slot + 1) & newMask;
            newKeys[slot] = oldKeys[i];
            newStamps[slot] = gen;
        }
        keys = newKeys;
        stamps = newStamps;
        mask = newMask;
    }
}
