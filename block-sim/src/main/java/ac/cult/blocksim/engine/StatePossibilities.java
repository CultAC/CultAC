package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.BlockRegistry;
import java.util.Map;
import java.util.UUID;

/** Only GrowingPlantHeadBlock's source-proven initial age 0..24 can be undeclared by the client input. */
public record StatePossibilities(int state, UUID randomPlantAge) {
    public static StatePossibilities exact(int state) { return new StatePossibilities(state, null); }
    public boolean exact() { return randomPlantAge == null; }

    /** Keep the same random sample when a client branch changes another property. */
    public StatePossibilities carryTo(BlockRegistry registry, int nextState) {
        if (exact() || !registry.sameBlock(state, nextState) || !registry.hasProperty(nextState, "age")
            || !registry.value(state, "age").equals(registry.value(nextState, "age"))) return exact(nextState);
        return new StatePossibilities(nextState, randomPlantAge);
    }

    /** Repeated appearances of one sample must have the same value across the entire action. */
    public boolean matches(BlockRegistry registry, int observed, Map<UUID, Integer> randomSamples) {
        if (exact()) return state == observed;
        if (!registry.sameBlock(state, observed) || !registry.hasProperty(observed, "age")) return false;
        int age = Integer.parseInt(registry.value(observed, "age"));
        if (age < 0 || age >= 25 || registry.with(state, "age", "0") != registry.with(observed, "age", "0")) return false;
        Integer previous = randomSamples.putIfAbsent(randomPlantAge, age);
        return previous == null || previous == age;
    }
}
