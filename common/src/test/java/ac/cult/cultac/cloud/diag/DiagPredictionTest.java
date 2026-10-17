package ac.cult.cultac.cloud.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.cloud.intake.v3.DiagCoverage;
import ac.cult.cultac.cloud.intake.v3.DiagPart;
import ac.cult.cultac.cloud.intake.v3.DiagTick;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPlayerSteer;
import ac.cult.cultac.manager.datastore.DataStoreLifecycle;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.anticheat.NettyScheduler;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.api.config.ConfigManager;
import com.github.luben.zstd.ZstdInputStream;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Diagnostics over real predictions: offline Java players on a stone floor beside a wall of stairs, fences, slabs and
 * panes, fed vanilla walking with a speed burst every {@link #CHEAT_EVERY} ticks so the engine flags. A watched
 * player's predictions, flags, violations, {@code /cult log} entries and the bytes written to them match an unwatched
 * player's fed the same ticks; a capture that throws trips diagnostics without touching prediction.
 */
class DiagPredictionTest {
    private static final int FLOOR_Y = 64;
    private static final int FLOOR_RADIUS = 48;
    private static final int CHEAT_EVERY = 23;
    private static final Limits ON = Limits.of(
            true,
            400,
            1 << 20,
            Limits.MAX_SERVER_RING_BYTES,
            Limits.MAX_PLAYERS,
            0,
            Limits.MAX_UPLOAD_BYTES_PER_SECOND,
            0,
            0);

    private static Field dataStore;
    private static Object previousDataStore;

    private final List<Diagnostics> opened = new ArrayList<>();

    @BeforeAll
    static void bootstrap() throws ReflectiveOperationException {
        OfflineCultTestBootstrap.installConfig();
        Class.forName(VerboseCodecs.class.getName());
        ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
        Mockito.when(config.getLongElse(Mockito.anyString(), Mockito.anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        // A flag records itself through the data store, which a plugin-less test has to provide.
        dataStore = CultAPI.class.getDeclaredField("dataStoreLifecycle");
        dataStore.setAccessible(true);
        previousDataStore = dataStore.get(CultAPI.INSTANCE);
        dataStore.set(
                CultAPI.INSTANCE,
                new DataStoreLifecycle(CultAPI.INSTANCE.getGrimPlugin(), CultAPI.INSTANCE.getBackendRegistry()));
    }

    @AfterAll
    static void restore() throws IllegalAccessException {
        dataStore.set(CultAPI.INSTANCE, previousDataStore);
    }

    @BeforeEach
    void shippedSetbacks() {
        // Other tests in this JVM leave prediction overrides set; the script's writes are the shipped setbacks.
        clearDouble("Simulation.threshold");
        clearDouble("Simulation.immediate-setback-threshold");
    }

    @AfterEach
    void close() {
        for (Diagnostics diagnostics : opened) diagnostics.close();
        Diagnostics.resetTrip();
    }

    @Test
    void watchingLeavesPredictionUnchanged() throws IOException {
        Diagnostics diagnostics = diagnostics();
        Bot plain = new Bot(0);
        Bot watched = new Bot(0);
        AtomicLong tick = new AtomicLong();
        DiagWatch watch = watched.watch(diagnostics, tick::get);
        assertNotNull(watch);
        try {
            run(200, tick, plain, watched);
            assertTrue(plain.flagged > 0, "the script makes the engine flag");
            assertEquals(plain.signatures, watched.signatures);
            assertEquals(plain.violations(), watched.violations());
            assertEquals(plain.logs, watched.logs);
            assertTrue(plain.writes > 0, "the script makes cultac write to the player");
            assertFalse(Diagnostics.tripped());
            assertNotNull(watched.debug.watch());

            await(() -> watch.ring != null && watch.ring.newest() >= watched.lastPredicted);
            assertFalse(watch.ended(), String.valueOf(watch.endReason));
            UUID player = watched.player.getUniqueId();
            DiagUpload upload = diagnostics.upload();
            long to = watched.lastPredicted;
            diagnostics.cut(player, new Cut(new byte[16], to - 19, to, to - 9, to), "header");
            List<DiagPart> parts = parts(upload);
            DiagPart last = parts.get(parts.size() - 1);
            assertEquals(DiagCoverage.DIAG_COVERAGE_FULL, last.getCoverage());
            int fulls = 0;
            for (DiagPart part : parts) {
                for (DiagTick diagTick : part.getTicksList()) {
                    if (diagTick.getFull().isEmpty()) continue;
                    fulls++;
                    String text = unzstd(diagTick.getFull().toByteArray());
                    assertTrue(text.contains("Bounding box: minX="), text);
                    assertTrue(text.contains("Starting vel: "), text);
                    assertTrue(text.contains("oak_stairs"), "the block grid is printed");
                }
            }
            assertTrue(fulls >= 10, "full texts " + fulls);
        } finally {
            plain.close();
            watched.close();
        }
        diagnostics.close();
        await(() -> !diagnostics.threadRunning());
        assertNull(watch.ring);
        assertNull(watched.debug.watch());
    }

    @Test
    void aCaptureThatThrowsTripsDiagnosticsAndPredictionGoesOn() {
        Diagnostics diagnostics = diagnostics();
        Bot plain = new Bot(1);
        AtomicLong tick = new AtomicLong();
        Bot failing = new Bot(1).counting(tick::get);
        DiagWatch watch = failing.watch(diagnostics, () -> {
            if (tick.get() >= 20) throw new IllegalStateException("injected capture failure");
            return tick.get();
        });
        try {
            run(120, tick, plain, failing);
            assertTrue(Diagnostics.tripped());
            assertTrue(watch.ended());
            assertEquals("failed", watch.endReason);
            assertNull(failing.debug.watch());
            assertEquals(plain.signatures, failing.signatures);
            assertEquals(plain.violations(), failing.violations());
            assertTrue(diagnostics.status().contains("tripped"));
        } finally {
            plain.close();
            failing.close();
        }
    }

    private static void clearDouble(String key) {
        try {
            Method clear = OfflineCultTestBootstrap.class.getDeclaredMethod("clearDoubleConfigOverride", String.class);
            clear.setAccessible(true);
            clear.invoke(null, key);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Diagnostics diagnostics() {
        Diagnostics diagnostics = new Diagnostics(new DiagUpload(), "prediction-test");
        diagnostics.captureLimitNanos = Long.MAX_VALUE;
        diagnostics.connected(1, ON);
        opened.add(diagnostics);
        return diagnostics;
    }

    /** {@code ticks} client ticks for every bot, round-robin at 20 a second so the timer checks see vanilla pacing. */
    private static void run(int ticks, AtomicLong tick, Bot... bots) {
        long origin = System.nanoTime();
        for (int slot = 0; slot < ticks; slot++) {
            for (Bot bot : bots) bot.tick();
            tick.incrementAndGet();
            long next = origin + (slot + 1) * 50_000_000L;
            while (System.nanoTime() < next) Thread.onSpinWait();
        }
    }

    private static List<DiagPart> parts(DiagUpload upload) {
        List<DiagPart> parts = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long nanos = 0;
        while (System.nanoTime() < deadline) {
            byte[] record = upload.poll(1, nanos += TimeUnit.SECONDS.toNanos(10));
            if (record == null) {
                sleep(5);
                continue;
            }
            try {
                DiagPart part = Intake.parseFrom(record).getMessages(0).getDiag();
                parts.add(part);
                if (part.getLast()) return parts;
            } catch (IOException malformed) {
                throw new AssertionError(malformed);
            }
        }
        throw new AssertionError("no last part");
    }

    private static String unzstd(byte[] frame) throws IOException {
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(frame))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            sleep(5);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** One offline player and the walk it is fed; what each tick's prediction concluded is kept. */
    private static final class Bot {
        private static final CheckManagerListener LISTENER = new CheckManagerListener();
        private static final PacketEntityAction ACTIONS = new PacketEntityAction();
        private static final PacketPlayerSteer STEER = new PacketPlayerSteer();

        final CultPlayer player = player();
        final EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
        final SuperDebug debug = player.checkManager.getListener(SuperDebug.class);
        final Mover mover;
        final List<String> signatures = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        int flagged;
        int writes;
        /** The seq of the last tick whose movement was predicted, on the watch's count. */
        long lastPredicted = -1;

        private static final LongSupplier NO_SEQ = () -> -1;
        private LongSupplier seq = NO_SEQ;

        /** Counts this bot's ticks on {@code ticks}, not on its watch's seq. */
        Bot counting(LongSupplier ticks) {
            seq = ticks;
            return this;
        }

        private PredictionResult last;

        Bot(int seed) {
            mover = new Mover(seed);
            seed(player, mover);
        }

        DiagWatch watch(Diagnostics diagnostics, LongSupplier seq) {
            if (this.seq == NO_SEQ) this.seq = seq;
            DiagWatch watch = diagnostics.watch(player.getUniqueId(), 1, 3600, seq, debug::history, debug::unwatch);
            debug.watch(watch);
            return watch;
        }

        void tick() {
            Step step = mover.tick();
            if (step.input != null) {
                var input = event(ServerboundPackets.PLAYER_INPUT, step.input);
                STEER.onPlayerInput(input, player, step.input);
                LISTENER.onPlayerInput(input, player, step.input);
            }
            if (step.sprintChange != null) {
                ServerboundPlayerCommand command = new ServerboundPlayerCommand(0, step.sprintChange, 0);
                var event = event(ServerboundPackets.PLAYER_COMMAND, command);
                ACTIONS.onPlayerCommand(event, player, command);
                LISTENER.onPlayerCommand(event, player, command);
            }
            ServerboundMovePlayer move = new ServerboundMovePlayer(
                    mover.x, mover.y, mover.z, mover.yaw, mover.pitch, mover.onGround, false, true, true);
            LISTENER.onMovePlayer(event(ServerboundPackets.MOVE_PLAYER, move), player, move);
            LISTENER.processClientTickEndReceive(
                    event(ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue()),
                    player);
            String written = written();
            PredictionResult result =
                    player.checkManager.getSimulationProcessor().getLastPrediction();
            if (result == null || result == last) {
                signatures.add("- writes" + written);
                return;
            }
            last = result;
            lastPredicted = seq.getAsLong();
            StringBuilder signature = new StringBuilder(written);
            signature.append(result.getOffset()).append(' ').append(result.isExempt());
            for (PredictionResult.Flag flag : result.getFlags()) {
                signature
                        .append(' ')
                        .append(flag.getCheck().getCheckName())
                        .append('=')
                        .append(flag.getVerbose().getString());
            }
            signature.append(" setback ").append(player.getSetbackTeleportUtil().isPendingSetback());
            signatures.add(signature.toString());
            if (result.getIdentifier() != 0) {
                flagged++;
                String log = SuperDebug.getFlag(result.getIdentifier());
                logs.add(
                        log == null
                                ? null
                                : log.replaceAll("(?m)^(Time|Player Name): .*$", "")
                                        .replaceAll("@[0-9a-f]+", "@"));
            }
        }

        Map<String, Double> violations() {
            Map<String, Double> violations = new TreeMap<>();
            for (Check check : player.checkManager.getAllChecks()) {
                violations.merge(check.getClass().getName(), check.violations, Double::sum);
            }
            return violations;
        }

        /**
         * What cultac wrote to the player this tick, in order: each write's kind. The transaction pings its scheduler
         * sends on a wall-clock timer are left out; they follow each player's own start, not its predictions.
         */
        String written() {
            StringBuilder written = new StringBuilder();
            channel.runPendingTasks();
            channel.flushOutbound();
            Object message;
            while ((message = channel.readOutbound()) != null) {
                Object kind = message instanceof CultConnection.WriteTask task ? task.action() : message;
                String name = kind.getClass().getName().replaceAll("/0x[0-9a-f]+", "");
                ReferenceCountUtil.release(message);
                if (name.startsWith(NettyScheduler.class.getName())) continue;
                writes++;
                written.append(' ').append(name);
            }
            return written.toString();
        }

        void close() {
            player.onRemove();
            channel.runPendingTasks();
            channel.finishAndReleaseAll();
        }

        private <T extends ServerboundPacket> PacketReceiveEvent<T> event(PacketType<T> type, T packet) {
            return new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, type, packet);
        }
    }

    private static CultPlayer player() {
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".Diag_Bench"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        player.gamemode = GameMode.SURVIVAL;
        player.canFly = false;
        player.isFlying = false;
        CompensatedWorld world = player.compensatedWorld;
        BlockRegistry registry = DataTables.defaults().registry();
        for (int cx = -FLOOR_RADIUS >> 4; cx <= FLOOR_RADIUS >> 4; cx++) {
            for (int cz = -FLOOR_RADIUS >> 4; cz <= FLOOR_RADIUS >> 4; cz++) {
                world.chunks.put(
                        CompensatedWorld.chunkPositionToLong(cx, cz),
                        new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
            }
        }
        for (int x = -FLOOR_RADIUS; x < FLOOR_RADIUS; x++) {
            for (int z = -FLOOR_RADIUS; z < FLOOR_RADIUS; z++) {
                world.applyBlockChangeRawDANGER(x, FLOOR_Y - 1, z, state(registry, "stone"));
            }
        }
        int[] wall = {
            state(registry, "oak_stairs"),
            state(registry, "oak_fence"),
            state(registry, "stone_slab"),
            state(registry, "glass_pane"),
            state(registry, "chest"),
            state(registry, "redstone_wire")
        };
        for (int z = -FLOOR_RADIUS; z < FLOOR_RADIUS; z++) {
            for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++) {
                world.applyBlockChangeRawDANGER(-2, y, z, wall[Math.floorMod(z + y, wall.length)]);
                world.applyBlockChangeRawDANGER(3, y, z, wall[Math.floorMod(z * 7 + y, wall.length)]);
            }
        }
        return player;
    }

    private static int state(BlockRegistry registry, String block) {
        return registry.block("minecraft:" + block).defaultState();
    }

    private static void seed(CultPlayer player, Mover mover) {
        player.x = player.lastX = mover.x;
        player.y = player.lastY = mover.y;
        player.z = player.lastZ = mover.z;
        player.xRot = player.lastTickXRot = mover.yaw;
        player.yRot = player.lastTickYRot = mover.pitch;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, mover.x, mover.y, mover.z);
        boolean onGround = Collisions.collide(player, 0, -SimpleCollisionBox.COLLISION_EPSILON, 0).y == 0;
        player.onGround = onGround;
        player.lastOnGround = onGround;
        player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
    }

    private record Step(ServerboundPlayerInput input, PlayerCommandAction sprintChange) {}

    /**
     * A Java client walking on flat stone along z, turning every 150 ticks, per LivingEntity#aiStep and travel:
     * friction 0.6, gravity 0.08, drag 0.98. Every {@link #CHEAT_EVERY} ticks it moves 0.35 further than it may.
     */
    private static final class Mover {
        double x = 0.5;
        double y = FLOOR_Y;
        double z;
        double vx;
        double vy = (0 - 0.08) * 0.98F;
        double vz;
        float yaw;
        float pitch = 10;
        boolean onGround = true;
        int tick;
        ServerboundPlayerInput lastInput;

        Mover(int seed) {
            z = -20.5 + seed % 5;
            tick = seed * 11;
        }

        Step tick() {
            tick++;
            yaw = tick / 150 % 2 == 0 ? 0 : 180;
            ServerboundPlayerInput input = new ServerboundPlayerInput(true, false, false, false, false, false, false);
            ServerboundPlayerInput changed = input.equals(lastInput) ? null : input;
            lastInput = input;
            PlayerCommandAction sprintChange = null;
            if (vx * vx + vz * vz < 9.0E-6) {
                vx = 0;
                vz = 0;
            }
            if (Math.abs(vy) < 0.003) vy = 0;
            float speed = onGround ? 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F)) : 0.02F;
            float forward = 0.98F * speed;
            float angle = yaw * ((float) Math.PI / 180F);
            vx += -Math.sin(angle) * forward;
            vz += Math.cos(angle) * forward;
            double dy = vy;
            if (y + dy < FLOOR_Y) {
                dy = FLOOR_Y - y;
                onGround = vy < 0;
                vy = 0;
            } else {
                onGround = false;
            }
            x += vx;
            y += dy;
            z += vz;
            if (tick % CHEAT_EVERY == 0) z += yaw == 0 ? 0.35 : -0.35;
            float friction = (onGround ? 0.6F : 1.0F) * 0.91F;
            vx *= friction;
            vy = (vy - 0.08) * 0.98F;
            vz *= friction;
            return new Step(changed, sprintChange);
        }
    }
}
