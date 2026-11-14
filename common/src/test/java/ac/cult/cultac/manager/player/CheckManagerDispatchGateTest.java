package ac.cult.cultac.manager.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.PacketTypeTable;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Route gates answer exactly as {@code shouldDispatch} on either platform, and are left out only where it is true. */
class CheckManagerDispatchGateTest {
    @Test
    void gatesAnswerAsShouldDispatchWhicheverPlatformThePlayerMovesOn() throws Exception {
        withPlayer(player -> {
            CheckManager manager = player.checkManager;
            List<CheckListener> listeners = new ArrayList<>(manager.allCheckListeners.values());
            var plain = new PlainCheck(player);
            var supported = new SupportedCheck(player);
            var overriding = new OverridingCheck(player);
            CheckListener notACheck = new CheckListener() {};
            listeners.addAll(List.of(plain, supported, overriding, notACheck));
            MovementPlatform initial = player.movementPlatform;
            int elided = 0;
            for (CheckListener listener : listeners) {
                BooleanSupplier gate = gate(manager, listener);
                if (gate == null) elided++;
                for (boolean answer : new boolean[] {true, false}) {
                    overriding.answer = answer;
                    for (MovementPlatform platform : MovementPlatform.values()) {
                        player.movementPlatform = platform;
                        boolean expected = shouldDispatch(manager, listener);
                        if (gate == null)
                            assertTrue(expected, listener.getClass().getName() + " on " + platform);
                        else assertEquals(expected, gate.getAsBoolean(), listener.getClass() + " on " + platform);
                    }
                }
                player.movementPlatform = initial;
            }
            assertNull(gate(manager, supported));
            assertNull(gate(manager, notACheck));
            assertNotNull(gate(manager, plain));
            assertNotNull(gate(manager, overriding));
            assertTrue(elided > 4 && elided < listeners.size(), "elided " + elided);
        });
    }

    /** The listener loops read the platform once and test only Bedrock players; that answers as shouldDispatch. */
    @Test
    void bedrockOnlyTestAnswersAsShouldDispatch() throws Exception {
        withPlayer(player -> {
            CheckManager manager = player.checkManager;
            List<CheckListener> listeners = new ArrayList<>(manager.allCheckListeners.values());
            var overriding = new OverridingCheck(player);
            listeners.addAll(List.of(new PlainCheck(player), new SupportedCheck(player), overriding, new CheckListener() {}));
            Method onBedrock = CheckManager.class.getDeclaredMethod("dispatchesOnBedrock", CheckListener.class);
            onBedrock.setAccessible(true);
            MovementPlatform initial = player.movementPlatform;
            int skipped = 0;
            for (CheckListener listener : listeners) {
                for (boolean answer : new boolean[] {true, false}) {
                    overriding.answer = answer;
                    for (MovementPlatform platform : MovementPlatform.values()) {
                        player.movementPlatform = platform;
                        boolean hoisted =
                                !player.isBedrockMovement() || (boolean) onBedrock.invoke(null, listener);
                        boolean expected = shouldDispatch(manager, listener);
                        assertEquals(expected, hoisted, listener.getClass() + " on " + platform);
                        if (!expected) skipped++;
                    }
                }
                player.movementPlatform = initial;
            }
            assertTrue(skipped > 4, "skipped " + skipped);
        });
    }

    /** Every handler the manager routes is a gated scanned callback, dispatched through gated segments. */
    @Test
    void checkRoutesHoldOnlyGatedHandlersInGatedSegments() throws Exception {
        withPlayer(player -> {
            CheckManager manager = player.checkManager;
            int routes = 0;
            int segments = 0;
            for (String name :
                    List.of("earlyReceiveRoutes", "prePredictionReceiveRoutes", "receiveRoutes", "sendRoutes")) {
                PacketTypeTable<?> table =
                        (PacketTypeTable<?>) field(CheckManager.class, name).get(manager);
                for (PacketType<?> type : OfflineCultTestBootstrap.catalog()) {
                    Object route = table.get(type);
                    if (route == null) continue;
                    routes++;
                    Object[] handlers =
                            (Object[]) field(route.getClass(), "handlers").get(route);
                    if (handlers.length == 0) continue;
                    for (Object handler : handlers) {
                        assertTrue(handler.getClass().getName().contains("PacketHandlerScanner$Gated"), name + type);
                    }
                    Object[] chain = (Object[]) field(route.getClass(), "chain").get(route);
                    assertNotNull(chain, name + " " + type);
                    segments += chain.length;
                    for (Object segment : chain) {
                        assertTrue(
                                segment.getClass().getName().contains(".Gated"),
                                segment.getClass().getName());
                        assertTrue(segment.getClass().isHidden());
                    }
                }
            }
            assertTrue(routes > 20, "routes " + routes);
            // Each segment is at most one clone, so a player's routes use a small part of the clone bound.
            assertTrue(segments < 256, "segments " + segments);
        });
    }

    private static BooleanSupplier gate(CheckManager manager, CheckListener listener) throws Exception {
        Method method = CheckManager.class.getDeclaredMethod("dispatchGate", CheckListener.class);
        method.setAccessible(true);
        return (BooleanSupplier) method.invoke(manager, listener);
    }

    private static boolean shouldDispatch(CheckManager manager, CheckListener listener) throws Exception {
        Method method = CheckManager.class.getDeclaredMethod("shouldDispatch", CheckListener.class);
        method.setAccessible(true);
        return (boolean) method.invoke(manager, listener);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    interface PlayerBody {
        void accept(CultPlayer player) throws Exception;
    }

    private static void withPlayer(PlayerBody body) throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = new CultPlayer(
                TestUsers.create(new User.Profile(UUID.randomUUID(), ".Gate_Test"), new EmbeddedChannel()));
        try {
            body.accept(player);
        } finally {
            Object channel = player.user.getChannel();
            player.onRemove();
            if (channel instanceof EmbeddedChannel embedded) embedded.finishAndReleaseAll();
        }
    }

    private static class PlainCheck extends Check implements CheckListener {
        PlainCheck(CultPlayer player) {
            super(player);
        }
    }

    @BedrockSupported
    private static class SupportedCheck extends Check implements CheckListener {
        SupportedCheck(CultPlayer player) {
            super(player);
        }
    }

    @BedrockSupported
    private static class OverridingCheck extends Check implements CheckListener {
        boolean answer = true;

        OverridingCheck(CultPlayer player) {
            super(player);
        }

        @Override
        public boolean isBedrockSupported() {
            return answer;
        }
    }
}
