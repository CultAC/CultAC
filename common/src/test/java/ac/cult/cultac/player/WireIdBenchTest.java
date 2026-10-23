package ac.cult.cultac.player;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Thread CPU per ping id drawn from a per-player DRBG; CULT_BENCH=1 enables it. */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class WireIdBenchTest {
    private static final int REPS = 9;
    private static final int IDS = 4_000_000;
    static int sink;

    @Test
    void wireIds() throws Exception {
        var random = SecureRandom.getInstance("DRBG");
        var block = new WireIds();
        IntSupplier ids = () -> block.next(random);
        for (int i = 0; i < IDS; i++) sink += CultPlayer.nextWireId(ids);
        var bean = ManagementFactory.getThreadMXBean();
        long[] perId = new long[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            long start = bean.getCurrentThreadCpuTime();
            for (int i = 0; i < IDS; i++) sink += CultPlayer.nextWireId(ids);
            perId[rep] = (bean.getCurrentThreadCpuTime() - start) * 1000 / IDS;
        }
        long[] sorted = perId.clone();
        Arrays.sort(sorted);
        String line = "wire-id ps/id median=" + sorted[REPS / 2] + " min=" + sorted[0] + " max=" + sorted[REPS - 1]
                + " all=" + Arrays.toString(perId) + System.lineSeparator();
        System.out.print(line);
        String out = System.getenv("CULT_BENCH_OUT");
        if (out != null)
            Files.writeString(
                    Path.of(out), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
