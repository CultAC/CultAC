package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.engine.ClientClocks;
import ac.cult.cultac.utils.latency.ClientEnvironment;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceivedEnvironmentTest {
    @Test
    void receivedClocksRemainCurrentWhenATimelineStartsAndCrossesItsTickBoundary() {
        ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.installConfig();
        var context = new ac.cult.cultac.utils.latency.ClientWorldRegistries(
                ac.cult.cultac.utils.latency.ClientWorldRegistries.modelDefaults());
        var clocks = new ClientClocks();
        var environment = new ClientEnvironment();
        int plains = ac.cult.cultac.network.codec.ModelRegistryNamesState.defaults()
                .id("minecraft:worldgen/biome", "minecraft:plains");
        environment.configure(
                context.dimension("minecraft:the_nether").environment().get(), clocks);
        clocks.handleUpdates(100, Map.of("minecraft:overworld", new ClientClocks.State(23400, 0, 1)));
        environment.configure(
                context.dimension("minecraft:overworld").environment().get(), clocks);
        assertTrue(environment.at(plains).creakingActive());
        clocks.tick(101);
        environment.tick();
        assertFalse(environment.at(plains).creakingActive());
        clocks.handleUpdates(102, Map.of("minecraft:overworld", new ClientClocks.State(18000, 0, 0)));
        environment.tick();
        assertTrue(environment.at(plains).creakingActive());
        clocks.tick(20000);
        environment.tick();
        assertTrue(environment.at(plains).creakingActive(), "A received paused clock stays paused");
    }
}
