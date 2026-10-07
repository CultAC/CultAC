package ac.cult.cultac.manager.player;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import org.junit.Test;

public final class SmoketestBlockStateComparisonTest {
    private static final BlockRegistry STATES = DataTables.defaults().registry();

    @Test
    public void initialPlantAgesMatchButShearsCompletionDoesNot() {
        for (var key : new String[] {
            "minecraft:weeping_vines", "minecraft:twisting_vines", "minecraft:kelp", "minecraft:cave_vines"
        }) {
            int initial = STATES.with(STATES.block(key).defaultState(), "age", "0");
            int mature = STATES.with(initial, "age", "25");
            for (int age = 0; age < 25; age++) {
                int randomized = STATES.with(initial, "age", Integer.toString(age));
                assertTrue(SmoketestSnapshotBridge.blockStatesMatch(STATES.debugString(randomized), initial));
                assertFalse(SmoketestSnapshotBridge.blockStatesMatch(STATES.debugString(randomized), mature));
                assertFalse(SmoketestSnapshotBridge.blockStatesMatch(STATES.debugString(mature), randomized));
            }
            assertTrue(SmoketestSnapshotBridge.blockStatesMatch(STATES.debugString(mature), mature));
        }
    }

    @Test
    public void blockTypeAndOtherPropertiesRemainExact() {
        int vine = STATES.block("minecraft:cave_vines").defaultState();
        assertFalse(SmoketestSnapshotBridge.blockStatesMatch(
                STATES.debugString(STATES.with(vine, "berries", "true")), STATES.with(vine, "berries", "false")));
        assertFalse(SmoketestSnapshotBridge.blockStatesMatch(
                STATES.debugString(STATES.block("minecraft:weeping_vines").defaultState()),
                STATES.block("minecraft:twisting_vines").defaultState()));
        assertFalse(SmoketestSnapshotBridge.blockStatesMatch(
                STATES.debugString(STATES.block("minecraft:weeping_vines_plant").defaultState()),
                STATES.block("minecraft:weeping_vines").defaultState()));
        int wheat = STATES.block("minecraft:wheat").defaultState();
        assertFalse(
                SmoketestSnapshotBridge.blockStatesMatch(STATES.debugString(STATES.with(wheat, "age", "3")), wheat));
        assertFalse(SmoketestSnapshotBridge.blockStatesMatch(
                STATES.debugString(STATES.block("minecraft:air").defaultState()), vine));
    }

    @Test
    public void invalidAgesDoNotMatch() {
        int vine = STATES.with(STATES.block("minecraft:weeping_vines").defaultState(), "age", "0");
        for (var invalid : new String[] {"-1", "26", "100000000000000000000"}) {
            assertFalse(SmoketestSnapshotBridge.blockStatesMatch(
                    STATES.debugString(vine).replace("age=0", "age=" + invalid), vine));
        }
    }
}
