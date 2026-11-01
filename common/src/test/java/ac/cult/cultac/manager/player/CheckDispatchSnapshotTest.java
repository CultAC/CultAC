package ac.cult.cultac.manager.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.movement.SetbackBlocker;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.PositionListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.checks.type.RotationListener;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PositionUpdate;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The dispatch arrays hold exactly the listeners the map iterations reached, in the same order. */
class CheckDispatchSnapshotTest {
    @Test
    void javaPlayerSnapshotsMatchTheMapIterations() throws ReflectiveOperationException {
        withPlayer(CheckDispatchSnapshotTest::assertSnapshots);
    }

    private static void assertSnapshots(CultPlayer player) {
        CheckManager manager = player.checkManager;
        try {
            List<Object> positions = new ArrayList<>();
            for (var map : List.of(manager.prePredictionChecks, manager.positionCheck, manager.packetChecks)) {
                for (CheckListener check : map.values()) {
                    if (declaresCallback(
                            check.getClass(), "onPositionUpdate", PositionUpdate.class, Set.of(CheckListener.class)))
                        positions.add(check);
                }
            }
            assertEquals(positions, array(manager, "positionUpdateListeners"));
            assertTrue(positions.size() < manager.packetChecks.size(), "position cull removed nothing");

            List<Object> rotations = new ArrayList<>(manager.rotationCheck.values());
            for (BlockPlaceCheck check : manager.blockPlaceCheck.values()) {
                if (check instanceof RotationListener) rotations.add(check);
            }
            assertEquals(rotations, array(manager, "rotationListeners"));
            assertEquals(new ArrayList<>(manager.vehicleCheck.values()), array(manager, "vehicleListeners"));
            assertEquals(new ArrayList<>(manager.blockPlaceCheck.values()), array(manager, "blockPlaceListeners"));

            List<Object> predictions = new ArrayList<>();
            for (Object check : manager.postPredictionCheck.values()) {
                if (declaresCallback(
                        check.getClass(),
                        "onPredictionComplete",
                        PredictionComplete.class,
                        Set.of(PostPredictionListener.class))) predictions.add(check);
            }
            assertEquals(predictions, array(manager, "predictionListeners"));
            assertEquals(new ArrayList<>(manager.pseudoCheck.values()), array(manager, "pseudoListeners"));

            for (var entry : manager.allCheckListeners.entrySet()) {
                assertSame(entry.getValue(), manager.getListener(entry.getKey()));
            }
            assertNull(manager.getListener(Unregistered.class));
            assertNull(manager.getListener(null));
            assertNotNull(manager.getWorldBorder());
            assertSame(manager.getListener(PacketWorldBorder.class), manager.getWorldBorder());
            assertNotNull(manager.getSetbackBlocker());
            assertSame(manager.getListener(SetbackBlocker.class), manager.getSetbackBlocker());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void onlyAnInheritedEmptyDefaultIsCulled() {
        assertFalse(CheckManager.overridesPositionUpdate(new PlainCheck()));
        assertFalse(CheckManager.overridesPositionUpdate(new PlainPosition()));
        assertTrue(CheckManager.overridesPositionUpdate(new MovingCheck()));
        assertTrue(CheckManager.overridesPositionUpdate(new InheritsMovingCheck()));
        assertTrue(CheckManager.overridesPositionUpdate(new ConcreteAbstractRedeclaration()));
        assertTrue(CheckManager.overridesPositionUpdate(new DefaultFromAnotherInterface()));
        assertFalse(CheckManager.overridesPredictionComplete(new PlainPosition()));
        assertFalse(CheckManager.overridesPredictionComplete(new MovingPostPrediction()));
        assertTrue(CheckManager.overridesPredictionComplete(new CompletingPostPrediction()));
        assertTrue(CheckManager.overridesPositionUpdate(new MovingPostPrediction()));
        assertFalse(CheckManager.overridesPositionUpdate(new CompletingPostPrediction()));
    }

    @Test
    void theSkippedDefaultsAreEmpty() throws ReflectiveOperationException, IOException {
        for (Method method : List.of(
                CheckListener.class.getMethod("onPositionUpdate", PositionUpdate.class),
                PostPredictionListener.class.getMethod("onPredictionComplete", PredictionComplete.class))) {
            assertTrue(method.isDefault(), method.toString());
            Path source = Path.of(
                    "src/main/java", method.getDeclaringClass().getName().replace('.', '/') + ".java");
            String declaration = "default void " + method.getName() + "(final "
                    + method.getParameterTypes()[0].getSimpleName() + " ";
            String body = Files.readString(source).replaceAll("\\s+", " ");
            int at = body.indexOf(declaration);
            assertTrue(at >= 0, source.toString());
            assertTrue(body.substring(body.indexOf(')', at)).startsWith(") {}"), method + " is not an empty body");
        }
    }

    /**
     * An independent resolution: a concrete class in the hierarchy declares the callback, or an interface other than
     * the given empty-default owners supplies a default.
     */
    private static boolean declaresCallback(Class<?> type, String name, Class<?> parameter, Set<Class<?>> emptyOwners) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameter);
                if (!Modifier.isAbstract(method.getModifiers())) return true;
            } catch (NoSuchMethodException ignored) {
                // Inherited, if at all.
            }
        }
        List<Class<?>> pending = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            pending.addAll(Arrays.asList(current.getInterfaces()));
        }
        while (!pending.isEmpty()) {
            Class<?> next = pending.removeLast();
            if (!emptyOwners.contains(next)) {
                try {
                    if (next.getDeclaredMethod(name, parameter).isDefault()) return true;
                } catch (NoSuchMethodException ignored) {
                    // Not declared here.
                }
            }
            pending.addAll(Arrays.asList(next.getInterfaces()));
        }
        return false;
    }

    private static List<Object> array(CheckManager manager, String name) throws ReflectiveOperationException {
        Field field = CheckManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return Arrays.asList((Object[]) field.get(manager));
    }

    private static void withPlayer(Consumer<CultPlayer> body) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = new CultPlayer(
                TestUsers.create(new User.Profile(UUID.randomUUID(), ".Snapshot_Test"), new EmbeddedChannel()));
        try {
            body.accept(player);
        } finally {
            Object channel = player.user.getChannel();
            player.onRemove();
            if (channel instanceof EmbeddedChannel embedded) embedded.finishAndReleaseAll();
        }
    }

    private interface Unregistered extends CheckListener {}

    private static class PlainCheck implements CheckListener {}

    private static class PlainPosition implements PositionListener {}

    private static class MovingCheck implements CheckListener {
        @Override
        public void onPositionUpdate(PositionUpdate positionUpdate) {}
    }

    private static class InheritsMovingCheck extends MovingCheck {}

    private abstract static class AbstractRedeclaration implements PositionListener {
        @Override
        public abstract void onPositionUpdate(PositionUpdate positionUpdate);
    }

    private static class ConcreteAbstractRedeclaration extends AbstractRedeclaration {
        @Override
        public void onPositionUpdate(PositionUpdate positionUpdate) {}
    }

    private interface MovingDefault extends CheckListener {
        @Override
        default void onPositionUpdate(PositionUpdate positionUpdate) {}
    }

    private static class DefaultFromAnotherInterface implements MovingDefault {}

    private static class MovingPostPrediction implements PostPredictionListener {
        @Override
        public void onPositionUpdate(PositionUpdate positionUpdate) {}
    }

    private static class CompletingPostPrediction implements PostPredictionListener {
        @Override
        public void onPredictionComplete(PredictionComplete predictionComplete) {}
    }
}
