package ac.cult.cultac.network;

import static ac.cult.cultac.network.WorldBookkeepingBenchTest.interleaved;
import static ac.cult.cultac.network.WorldBookkeepingBenchTest.line;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.environment.EnvironmentData;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import com.google.gson.JsonObject;
import com.sun.management.ThreadMXBean;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Thread CPU and allocation of the registry memos beside the lookups they skip, reps interleaved. CULT_BENCH=1. */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class RegistryMemoBenchTest {
    private static final ThreadMXBean BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** A login or respawn: readableDimension builds the environment once, setDimension again on the same binding. */
    @Test
    void dimensionEnvironments() throws Exception {
        var worlds = new ClientWorldRegistries(ClientWorldRegistries.modelDefaults());
        var last = MethodHandles.privateLookupIn(ClientWorldRegistries.class, MethodHandles.lookup())
                .findVarHandle(ClientWorldRegistries.class, "lastEnvironment", lastType());
        long before = BEAN.getCurrentThreadAllocatedBytes();
        var built = worlds.readableDimension(0).environment().get();
        line("environment one-build bytes " + (BEAN.getCurrentThreadAllocatedBytes() - before) + " " + built);
        var copy = EnvironmentData.class.getDeclaredConstructor(JsonObject.class, Map.class, List.class);
        Object[] fields = new Object[3];
        int f = 0;
        for (String name : List.of("dimensionAttributes", "biomeAttributes", "timelines")) {
            var field = EnvironmentData.class.getDeclaredField(name);
            field.setAccessible(true);
            fields[f++] = field.get(built);
        }
        before = BEAN.getCurrentThreadAllocatedBytes();
        copy.newInstance(fields);
        line("environment deep-copy bytes (retained bound) " + (BEAN.getCurrentThreadAllocatedBytes() - before));
        var bodies = new LinkedHashMap<String, LongUnaryOperator>();
        bodies.put("environment rebuild", units -> {
            long seen = 0;
            for (long unit = 0; unit < units; unit++) {
                last.set(worlds, null);
                var binding = worlds.readableDimension(0);
                last.set(worlds, null);
                seen += binding.environment().get().hashCode();
            }
            return seen;
        });
        bodies.put("environment memo", units -> {
            long seen = 0;
            for (long unit = 0; unit < units; unit++) {
                last.set(worlds, null);
                var binding = worlds.readableDimension(0);
                seen += binding.environment().get().hashCode();
            }
            return seen;
        });
        interleaved(1, bodies);
    }

    /** One block simulation's tag overlay lookup on the same connection. */
    @Test
    void simulatorTags() throws Exception {
        var state = new ClientComponentRegistries();
        state.appendTags(new RegistryTags(Map.of(
                "minecraft:worldgen/biome", new RegistryTags.Payload(Map.of("test:received", List.of(1))))));
        var defaults = DataTables.defaults();
        VarHandle last = MethodHandles.privateLookupIn(ClientComponentRegistries.class, MethodHandles.lookup())
                .findVarHandle(ClientComponentRegistries.class, "lastSimulatorTags", overlayType());
        long calls = 50_000_000, start = System.nanoTime();
        for (long call = 0; call < calls; call++) {
            last.set(state, null);
            WorldBookkeepingBenchTest.sink +=
                    state.blockSimulatorTags(ProtocolVersion.V26_3, ProtocolVersion.V26_3, defaults).hashCode();
        }
        line("simulator-tags map wall ps/call " + (System.nanoTime() - start) * 1000 / calls);
        var bodies = new LinkedHashMap<String, LongUnaryOperator>();
        bodies.put("simulator-tags map", units -> {
            long seen = 0;
            for (long unit = 0; unit < units; unit++) {
                last.set(state, null);
                seen += state.blockSimulatorTags(ProtocolVersion.V26_3, ProtocolVersion.V26_3, defaults).hashCode();
            }
            return seen;
        });
        bodies.put("simulator-tags memo", units -> {
            long seen = 0;
            for (long unit = 0; unit < units; unit++)
                seen += state.blockSimulatorTags(ProtocolVersion.V26_3, ProtocolVersion.V26_3, defaults).hashCode();
            return seen;
        });
        interleaved(1, bodies);
    }

    private static Class<?> lastType() throws Exception {
        return ClientWorldRegistries.class.getDeclaredField("lastEnvironment").getType();
    }

    private static Class<?> overlayType() throws Exception {
        return ClientComponentRegistries.class.getDeclaredField("lastSimulatorTags").getType();
    }
}
