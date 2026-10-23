package ac.cult.cultac.cloud.perf;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ac.cult.cultac.cloud.intake.v3.PerfAllocCalibration;
import ac.cult.cultac.cloud.intake.v3.PerfProfile;
import ac.cult.cultac.platform.api.PlatformServer;
import com.github.luben.zstd.Zstd;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Kept against exact allocated bytes per thread group over {@code CULTAC_PERF_ALLOC_BENCH} windows of 20 s, 10 s
 * apart, after 20 s of warm-up: steady and bursty {@code Netty} threads, quiet {@code cult-} threads and virtual
 * threads on their carriers (group other). Writes {@code build/perf-alloc-bench/<CULTAC_PERF_ALLOC_BENCH_LABEL>.txt}.
 */
@EnabledIfEnvironmentVariable(named = "CULTAC_PERF_ALLOC_BENCH", matches = "\\d+")
class PerfAllocBenchTest {
    private static final int[] GROUPS = {Attribution.NETTY, Attribution.OWN, Attribution.OTHER};

    private static volatile boolean running;

    @Test
    void bench() throws Exception {
        int windows = Integer.parseInt(System.getenv("CULTAC_PERF_ALLOC_BENCH"));
        String label = System.getenv().getOrDefault("CULTAC_PERF_ALLOC_BENCH_LABEL", "run");
        running = true;
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long rate = (60L + 40L * i) << 20;
            threads.add(Thread.ofPlatform().daemon().name("Netty Steady #" + i).start(() -> steady(rate, 16 << 10)));
        }
        for (int i = 0; i < 2; i++) {
            threads.add(Thread.ofPlatform().daemon().name("Netty Burst #" + i).start(PerfAllocBenchTest::burst));
        }
        for (int i = 0; i < 4; i++) {
            long rate = 256L * 1024 << i;
            threads.add(Thread.ofPlatform().daemon().name("cult-quiet-" + i).start(() -> steady(rate, 8 << 10)));
        }
        for (int i = 0; i < 4; i++) {
            threads.add(Thread.ofVirtual().name("vt-" + i).start(() -> steady(20L << 20, 8 << 10)));
        }
        StringBuilder out = new StringBuilder();
        long[] sampled = new long[Attribution.GROUPS.size()];
        long[] exact = new long[Attribution.GROUPS.size()];
        long recorderCpu = 0;
        try {
            Thread.sleep(20_000);
            PerfRecorder recorder =
                    new PerfRecorder(platform(), new AgentInfo("p", "s", "c", "v", "h", "m"), Clock.systemUTC());
            for (int w = 0; w < windows; w++) {
                if (w > 0) Thread.sleep(10_000);
                PerfRecorder.Result result =
                        recorder.record(new PerfSettings(20, 10, 20, 300, 1 << 20, true, 45_000), () -> false);
                byte[] zstd = assertInstanceOf(PerfRecorder.Result.Done.class, result, result::toString)
                        .zstdProfile();
                PerfProfile profile =
                        PerfProfile.parseFrom(Zstd.decompress(zstd, (int) Zstd.getFrameContentSize(zstd)));
                recorderCpu += profile.getRecorderCpuNanos();
                out.append(String.format(
                        Locale.ROOT, "window %2d recorderCpu %6.1f ms", w + 1, profile.getRecorderCpuNanos() / 1e6));
                for (PerfAllocCalibration group : profile.getAllocCalibrationList()) {
                    sampled[group.getThreadGroup()] += group.getSampledBytes();
                    exact[group.getThreadGroup()] += group.getExactBytes();
                }
                for (int group : GROUPS) {
                    PerfAllocCalibration calibration = profile.getAllocCalibrationList().stream()
                            .filter(g -> g.getThreadGroup() == group)
                            .findFirst()
                            .orElse(PerfAllocCalibration.getDefaultInstance());
                    out.append(String.format(
                            Locale.ROOT,
                            "  %s %7.1f/%7.1f MB",
                            Attribution.GROUPS.get(group),
                            calibration.getSampledBytes() / 1e6,
                            calibration.getExactBytes() / 1e6));
                }
                out.append('\n');
            }
        } finally {
            running = false;
            for (Thread thread : threads) thread.join();
        }
        out.append(
                String.format(Locale.ROOT, "TOTAL %s recorderCpu %.1f ms/window", label, recorderCpu / 1e6 / windows));
        for (int group : GROUPS) {
            out.append(String.format(
                    Locale.ROOT,
                    "  %s kept/exact %.3f",
                    Attribution.GROUPS.get(group),
                    (double) sampled[group] / exact[group]));
        }
        out.append('\n');
        Path dir = Path.of("build", "perf-alloc-bench");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(label + ".txt"), out, StandardCharsets.UTF_8);
        System.out.print(out);
    }

    private static void steady(long bytesPerSecond, int maxArray) {
        Object[] sink = new Object[512];
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long start = System.nanoTime();
        long done = 0;
        while (running) {
            for (int i = 0; i < 32; i++) {
                int n = 64 + random.nextInt(maxArray);
                sink[random.nextInt(sink.length)] = new byte[n];
                done += n + 16;
            }
            long ahead = (long) (done * 1e9 / bytesPerSecond) - (System.nanoTime() - start);
            if (ahead > 1_000_000) LockSupport.parkNanos(ahead);
        }
    }

    private static void burst() {
        Object[] sink = new Object[512];
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long done = 0;
        while (running) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500 + random.nextInt(4000)));
            long target = done + (50L << 20) + random.nextLong(300L << 20);
            while (running && done < target) {
                int n = 64 + random.nextInt(64 << 10);
                sink[random.nextInt(sink.length)] = new byte[n];
                done += n + 16;
            }
        }
    }

    private static PlatformServer platform() {
        return (PlatformServer) Proxy.newProxyInstance(
                PlatformServer.class.getClassLoader(), new Class<?>[] {PlatformServer.class}, (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 20.0;
                    if (type == boolean.class) return false;
                    if (type == String.class) return "bench";
                    return null;
                });
    }
}
