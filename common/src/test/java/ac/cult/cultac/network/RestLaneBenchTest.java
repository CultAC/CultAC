package ac.cult.cultac.network;

import static ac.cult.cultac.network.WorldBookkeepingBenchTest.interleaved;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsO;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.events.packets.PacketEntityReplication;
import ac.cult.cultac.manager.player.CheckManager;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vector3dm;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The small per-tick forms of the rest lane, each old form beside the new one, reps interleaved. CULT_BENCH=1. */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class RestLaneBenchTest {
    private static final Hand[] HANDS = Hand.values();
    static volatile Object escape;

    @Test
    void perTickForms() throws Exception {
        try (var services = new RecordConsumerServices();
                var record = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            CultPlayer player = record.player;
            CheckManager manager = player.checkManager;
            BadPacketsO keepalives = manager.getCheck(BadPacketsO.class);
            var received = new AtomicInteger(5);
            var oldKeepalives = new LinkedList<long[]>();
            var pending = new ConcurrentHashMap<Integer, Integer>();
            var digging = new BlockPos(3, 64, 7);
            player.lastX = 1.25;
            player.lastY = 64.5;
            player.lastZ = 2.75;
            Map<Class<?>, CheckListener> byClass = new IdentityHashMap<>(manager.allCheckListeners);
            List<CheckListener> listeners = new ArrayList<>(manager.allCheckListeners.values());
            Consumer<Runnable> sink = task -> escape = task;
            Runnable cached = player::getName;
            var bodies = new LinkedHashMap<String, LongUnaryOperator>();
            bodies.put("keepalive retire removeIf", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) {
                    int at = received.get();
                    if (oldKeepalives.removeIf(p -> p[0] > 0 && p[0] <= at)) seen++;
                }
                return seen;
            });
            bodies.put("keepalive retire guarded", units -> {
                for (long unit = 0; unit < units; unit++) keepalives.onClientTickEnd(null, player, null);
                return 1;
            });
            bodies.put("dig distance vectors", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) {
                    Vector3dm position = new Vector3dm(player.lastX, player.lastY, player.lastZ);
                    escape = position;
                    if (position.distanceSquared(new Vector3dm(digging.getX(), digging.getY(), digging.getZ())) < 100)
                        seen++;
                }
                return seen;
            });
            bodies.put("dig distance primitive", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) {
                    double x = player.lastX, y = player.lastY, z = player.lastZ;
                    if (CultMath.square(x - digging.getX())
                                    + CultMath.square(y - digging.getY())
                                    + CultMath.square(z - digging.getZ())
                            < 100) seen++;
                }
                return seen;
            });
            bodies.put("hands values()", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) for (Hand hand : Hand.values()) seen += hand.ordinal();
                return seen;
            });
            bodies.put("hands cached", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) for (Hand hand : HANDS) seen += hand.ordinal();
                return seen;
            });
            bodies.put("pending slots iterator", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) {
                    for (Iterator<Map.Entry<Integer, Integer>> it = pending.entrySet().iterator(); it.hasNext(); )
                        seen += it.next().getValue();
                }
                return seen;
            });
            bodies.put("pending slots guarded", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) {
                    if (!pending.isEmpty()) {
                        for (Iterator<Map.Entry<Integer, Integer>> it = pending.entrySet().iterator(); it.hasNext(); )
                            seen += it.next().getValue();
                    }
                }
                return seen;
            });
            bodies.put("tick task method ref", units -> {
                for (long unit = 0; unit < units; unit++) sink.accept(player::getName);
                return 1;
            });
            bodies.put("tick task cached", units -> {
                for (long unit = 0; unit < units; unit++) sink.accept(cached);
                return 1;
            });
            bodies.put("listener lookup map", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    seen += System.identityHashCode(byClass.get(PacketEntityReplication.class));
                return seen;
            });
            bodies.put("listener lookup field", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) seen += System.identityHashCode(manager.getFairReach());
                return seen;
            });
            bodies.put("dispatch gate per listener", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    for (CheckListener listener : listeners)
                        if (!(listener instanceof Check check) || !player.isBedrockMovement() || check.isBedrockSupported())
                            seen++;
                return seen;
            });
            bodies.put("dispatch gate hoisted", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) {
                    boolean bedrock = player.isBedrockMovement();
                    for (CheckListener listener : listeners)
                        if (!bedrock || !(listener instanceof Check check) || check.isBedrockSupported()) seen++;
                }
                return seen;
            });
            interleaved(1, bodies);
        }
    }
}
