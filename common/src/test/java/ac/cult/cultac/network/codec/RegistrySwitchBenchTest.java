package ac.cult.cultac.network.codec;

import ac.cult.blocksim.environment.ClientWorldDefaults;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Thread CPU per server switch of a connection's model registry names; CULT_BENCH=1 enables it. */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class RegistrySwitchBenchTest {
    private static final int WARMUP = 1000;
    private static final int REPS = 9;
    private static final int SWITCHES = 2000;

    @Test
    void serverSwitch() throws IOException {
        var defaults = ClientWorldDefaults.defaults();
        var state = new ModelRegistryNamesState();
        Runnable oneSwitch = () -> {
            for (String registry : defaults.networkRegistries())
                state.append(registry, new ArrayList<>(defaults.initialNames(registry)));
            state.publish(state.staged(false));
        };
        for (int i = 0; i < WARMUP; i++) oneSwitch.run();
        var bean = ManagementFactory.getThreadMXBean();
        long[] perSwitch = new long[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            long start = bean.getCurrentThreadCpuTime();
            for (int i = 0; i < SWITCHES; i++) oneSwitch.run();
            perSwitch[rep] = (bean.getCurrentThreadCpuTime() - start) / SWITCHES;
        }
        report("registry-switch ns/switch", perSwitch);
    }

    static void report(String name, long[] samples) throws IOException {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        String line = name + " median=" + sorted[sorted.length / 2] + " min=" + sorted[0] + " max="
                + sorted[sorted.length - 1] + " all=" + Arrays.toString(samples) + System.lineSeparator();
        System.out.print(line);
        String out = System.getenv("CULT_BENCH_OUT");
        if (out != null)
            Files.writeString(
                    Path.of(out), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
