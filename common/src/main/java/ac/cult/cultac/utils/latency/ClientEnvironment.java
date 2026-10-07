package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.engine.ClientClocks;
import ac.cult.blocksim.environment.BooleanEnvironment;
import ac.cult.blocksim.environment.EnvironmentData;

/** Prediction samples owned environment values at tick boundaries. */
public final class ClientEnvironment {
    private BooleanEnvironment environment;

    public void configure(EnvironmentData metadata, ClientClocks clocks) {
        environment = metadata.create(clocks);
    }

    public void tick() {
        if (environment != null) environment.tick();
    }

    public BooleanEnvironment.Values at(int biomeId) {
        if (environment == null) throw new IllegalStateException("Missing received biome " + biomeId);
        return environment.at(biomeId);
    }
}
