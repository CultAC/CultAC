package ac.cult.cultac.companion.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClearActiveItem;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CloseInventory;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerStateResult;
import ac.cult.cultac.companion.Stubs;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ServerActionsTest {
    private static final UUID NETHER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final String SHIELD = "minecraft:shield";

    private final World nether = world();

    @Test
    void playerStateCarriesTheGameModeAndTheLocationWithItsWorld() {
        Location location = new Location(nether, 12.5, 70, -3.25, 45f, -10f);

        assertEquals(
                new PlayerStateResult(4, 3, NETHER_ID, 12.5, 70, -3.25, 45f, -10f),
                ServerActions.state(4, GameMode.SPECTATOR, location));
        assertEquals(1, ServerActions.state(5, GameMode.CREATIVE, location).gameMode());
    }

    @Test
    void clearAndCloseRunInPacketOrder() {
        QueueEndpoint endpoint = new QueueEndpoint(true);
        List<String> calls = new ArrayList<>();
        BridgeSession session = session(endpoint, calls);

        actions().onMessage(session, new ClearActiveItem(ClearActiveItem.OFF_HAND, SHIELD));
        actions().onMessage(session, new CloseInventory());
        actions().onMessage(session, new ClearActiveItem(ClearActiveItem.OFF_HAND, SHIELD));

        assertEquals(3, endpoint.queued.size());
        assertEquals(List.of(), calls);
        endpoint.queued.forEach(Runnable::run);
        assertEquals(List.of("isHandRaised", "closeInventory", "isHandRaised"), calls);
    }

    @Test
    void clearIsDroppedWhenTheConnectionCannotQueueIt() {
        QueueEndpoint endpoint = new QueueEndpoint(false);
        List<String> calls = new ArrayList<>();

        actions().onMessage(session(endpoint, calls), new ClearActiveItem(ClearActiveItem.OFF_HAND, SHIELD));

        assertEquals(List.of(), endpoint.queued);
        assertEquals(List.of(), calls);
    }

    @Test
    void clearMatchesOnlyAUseOfTheSameTypeInTheSameHand() {
        var offhandShield = new ClearActiveItem(ClearActiveItem.OFF_HAND, SHIELD);
        var mainBread = new ClearActiveItem(ClearActiveItem.MAIN_HAND, "minecraft:bread");

        assertTrue(ServerActions.matches(offhandShield, EquipmentSlot.OFF_HAND, SHIELD));
        assertTrue(ServerActions.matches(mainBread, EquipmentSlot.HAND, "minecraft:bread"));
        assertFalse(ServerActions.matches(offhandShield, EquipmentSlot.HAND, SHIELD));
        assertFalse(ServerActions.matches(offhandShield, EquipmentSlot.OFF_HAND, "minecraft:bread"));
        assertFalse(ServerActions.matches(offhandShield, null, null));
        assertFalse(ServerActions.matches(offhandShield, EquipmentSlot.OFF_HAND, null));
        assertFalse(ServerActions.matches(new ClearActiveItem(7, SHIELD), EquipmentSlot.OFF_HAND, SHIELD));
    }

    private static ServerActions actions() {
        return new ServerActions(
                null, new BridgeService(new byte[] {1}, null, Logger.getLogger("actions-test"), false));
    }

    private static BridgeSession session(BridgeEndpoint endpoint, List<String> calls) {
        BridgeSession session = new BridgeSession(endpoint, UUID.randomUUID(), new byte[0], new byte[0]);
        session.state = BridgeSession.READY;
        session.player = player(calls);
        return session;
    }

    private static Player player(List<String> calls) {
        return Stubs.stub(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "isOnline" -> true;
            case "isHandRaised" -> {
                calls.add(method.getName());
                yield false;
            }
            case "clearActiveItem", "closeInventory" -> {
                calls.add(method.getName());
                yield null;
            }
            default -> null;
        });
    }

    private static final class QueueEndpoint extends BridgeEndpoint {
        private final List<Runnable> queued = new ArrayList<>();
        private final boolean accepts;

        QueueEndpoint(boolean accepts) {
            this.accepts = accepts;
        }

        @Override
        @Nullable
        UUID playerId() {
            return null;
        }

        @Override
        void send(BridgeMessage message) {}

        @Override
        void execute(Runnable task) {
            task.run();
        }

        @Override
        boolean runInPacketOrder(Runnable task) {
            return accepts && queued.add(task);
        }
    }

    private static World world() {
        return Stubs.stub(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "getUID" -> NETHER_ID;
            default -> null;
        });
    }
}
