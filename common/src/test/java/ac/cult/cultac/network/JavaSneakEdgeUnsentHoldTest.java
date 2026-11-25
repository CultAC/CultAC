package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.SneakEdgeClient.EDGE_X;
import static ac.cult.cultac.network.SneakEdgeClient.RELEASE_ALONG_EDGE;
import static ac.cult.cultac.network.SneakEdgeClient.SNEAK;
import static ac.cult.cultac.network.SneakEdgeClient.SNEAK_ALONG_EDGE;
import static ac.cult.cultac.network.SneakEdgeClient.SNEAK_BACK_ALONG_EDGE;
import static ac.cult.cultac.network.SneakEdgeClient.SNEAK_TOWARD_EDGE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.ItemUseInventory;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Sneaking holds at an edge as a vanilla client sends them: a held tick that Player#maybeBackOffFromEdge keeps at the
 * edge moves under 2.0E-4, so LocalPlayer#sendPosition sends no position for it and the engine replays it from its
 * CLIENT_TICK_END. The release moves by deltaMovement's carry plus its own input; one 0.01 faster flags.
 */
class JavaSneakEdgeUnsentHoldTest {
    enum Shape {
        HOLD_SENT_STRAFE(c -> {
            c.tick(SNEAK, true, 0.0);
            strafe(c, SNEAK_ALONG_EDGE);
        }),
        HOLD_3_UNSENT(c -> {
            c.tick(SNEAK, true, 0.0);
            ticks(c, SNEAK_TOWARD_EDGE, 3);
            strafe(c, SNEAK_ALONG_EDGE);
        }),
        HOLD_8_UNSENT(c -> {
            c.tick(SNEAK, true, 0.0);
            ticks(c, SNEAK_TOWARD_EDGE, 8);
            strafe(c, SNEAK_ALONG_EDGE);
        }),
        HOLD_3_UNSENT_STRAFE_BACK(c -> {
            c.tick(SNEAK, true, 0.0);
            ticks(c, SNEAK_TOWARD_EDGE, 3);
            strafe(c, SNEAK_BACK_ALONG_EDGE);
        }),
        WALK_TO_EDGE_AND_HOLD(c -> approach(c, 0)),
        WALK_TO_EDGE_HOLD_IDLE_6(c -> approach(c, 6)),
        WALK_TO_EDGE_HOLD_IDLE_15(c -> approach(c, 15)),
        // The server's use flag leaves an uncertain use unresolved through the hold, then ends it; the idle decays
        // deltaMovement until LivingEntity#aiStep zeroes it.
        UNCERTAIN_USE_HOLD_IDLE_6(c -> uncertainUse(c, 6)),
        UNCERTAIN_USE_HOLD_IDLE_10(c -> uncertainUse(c, 10));

        private final Consumer<SneakEdgeClient> hold;

        Shape(Consumer<SneakEdgeClient> hold) {
            this.hold = hold;
        }
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void releaseAtTheCarryIsExact(Shape shape) throws Exception {
        PredictionResult release = release(shape, 0.0);
        assertTrue(exact(release), describe(release));
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void releasePastTheCarryIsFlagged(Shape shape) throws Exception {
        PredictionResult release = release(shape, 0.01);
        assertFalse(exact(release), describe(release));
    }

    /** Runs {@code shape}'s hold, then releases shift along the edge {@code past} beyond vanilla's move. */
    static SneakEdgeClient hold(JavaTickFixture f, Shape shape) {
        SneakEdgeClient client = new SneakEdgeClient(f, shape.name().startsWith("WALK") ? 8.95 : EDGE_X);
        if (shape.name().startsWith("UNCERTAIN_USE")) {
            var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
            f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
            f.player.clientItemUse.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), false);
            client.itemUse(0.2F);
        }
        shape.hold.accept(client);
        for (SneakEdgeClient.Tick tick : client.ticks) assertTrue(exact(tick.result()), describe(tick.result()));
        return client;
    }

    private static PredictionResult release(Shape shape, double past) throws Exception {
        try (var f = new JavaTickFixture()) {
            return hold(f, shape).tick(RELEASE_ALONG_EDGE, true, past);
        }
    }

    private static void ticks(SneakEdgeClient c, ServerboundPlayerInput keys, int count) {
        for (int i = 0; i < count; i++) c.tick(keys);
    }

    private static void strafe(SneakEdgeClient c, ServerboundPlayerInput keys) {
        ticks(c, keys, 4);
    }

    private static void approach(SneakEdgeClient c, int idle) {
        c.tick(SNEAK, true, 0.0);
        ticks(c, SNEAK_TOWARD_EDGE, 14);
        ticks(c, SNEAK, idle);
        strafe(c, SNEAK_ALONG_EDGE);
    }

    private static void uncertainUse(SneakEdgeClient c, int idle) {
        c.tick(SNEAK, true, 0.0);
        ticks(c, SNEAK_TOWARD_EDGE, 4);
        // The client sends RELEASE_USE_ITEM and the server clears its use flag.
        c.f().player.clientItemUse.release();
        ItemUseInventory.metadata(c.f().player, (byte) 0);
        c.itemUse(1.0F);
        ticks(c, SNEAK, idle);
        strafe(c, SNEAK_ALONG_EDGE);
    }
}
