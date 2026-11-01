package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.scaffolding.FarPlace;
import ac.cult.cultac.events.packets.PacketEntityReplication;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.BlockActionReconciler;
import ac.cult.cultac.utils.math.Vec3;
import ac.grim.grimac.api.config.ConfigManager;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An entity query the compensated candidates leave open declines the block action without writes, and the packet's
 * handler, checks and bookkeeping still complete, whether a flagging check rejects the action or forwards it.
 */
class EntityQueryUncertaintyTest {
    private static final Vec3 STANDING = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Vec3 FAR = new Vec3(8.5, JavaTickFixture.FLOOR_Y, 8.5);
    private static final BlockPos CLICKED = new BlockPos(2, JavaTickFixture.FLOOR_Y, 0);
    private static final BlockPos PLACED = new BlockPos(1, JavaTickFixture.FLOOR_Y, 0);
    private static final int COW = 41;
    private static final int DRAGON = 50;
    private final List<ServerboundPacket> observed = new ArrayList<>();
    private JavaTickFixture fixture;
    private CultPlayer player;

    @BeforeEach
    void connect() throws Exception {
        fixture = new JavaTickFixture();
        player = fixture.player;
        player.food = 20;
        fixture.fixture.registrar.register(
                routes -> routes.registerReceiveListener(PacketListenerPriority.LOW, fixture.listener));
        player.observer = new PlayerObserver() {
            @Override
            public void received(ServerboundPacket packet, boolean cancelled) {
                observed.add(packet);
            }
        };
    }

    @AfterEach
    void disconnect() throws Exception {
        fixture.close();
    }

    @Test
    void aBoatUseWhileADragonIsTrackedCompletesWithoutSimulating() {
        stand(STANDING);
        hold("minecraft:oak_boat", 1);
        player.compensatedEntities.addEntity(
                DRAGON, EntityTypes.defaults().byKey("minecraft:ender_dragon").id(), new Vec3(60, 100, 60), 0, 0, 0);
        player.packetStateData.clientSidePosition = STANDING;

        var event = dispatch(ServerboundPackets.USE_ITEM, new ServerboundUseItem(Hand.MAIN_HAND, 7, player.xRot, player.yRot));

        assertFalse(event.isCancelled());
        assertEquals(7, lastForwarded(), "the reconciler records the forwarded sequence");
        assertEquals(1, held().count());
        assertEquals(1, player.clientItemUse.lowestCount(held()));
        assertFalse(player.isClientUsingItem());
    }

    @Test
    void aPlaceBesideACowBetweenItsMovePongsCompletesWithoutSimulating() throws Exception {
        straddlingCow(STANDING);

        var event = dispatch(ServerboundPackets.USE_ITEM_ON, westClick());

        assertFalse(event.isCancelled());
        assertEquals(1, lastForwarded(), "the reconciler records the forwarded sequence");
        assertNotSimulated();
    }

    @Test
    void aCancellingFarPlaceRejectsTheClickAndQueuesItsCorrection() throws Exception {
        straddlingCow(FAR);
        var farPlace = farPlaceWithCancelVL(0);
        double before = farPlace.getViolations();
        // A prior forwarded action the backend has not acknowledged holds the correction in the queue.
        player.blockActionReconciler.forwarded(0);

        var event = dispatch(ServerboundPackets.USE_ITEM_ON, westClick());

        assertTrue(event.isCancelled(), "the flagged click is rejected");
        assertTrue(farPlace.getViolations() > before, "reach does not depend on the entity query");
        assertEquals(0, lastForwarded(), "the rejected sequence is not forwarded");
        assertEquals(1, pendingCorrections(), "the rejection queues its correction");
        Object correction = ((ArrayDeque<?>) reconcilerField("pending")).getFirst();
        assertEquals(1, correctionField(correction, "sequence"));
        assertEquals(Set.of(CLICKED, PLACED), correctionField(correction, "positions"));
        assertEquals(Set.of(Inventory.HOTBAR_OFFSET), correctionField(correction, "slots"));
        assertNotSimulated();
    }

    @Test
    void aNonCancellingFarPlaceForwardsTheClick() throws Exception {
        straddlingCow(FAR);
        var farPlace = farPlaceWithCancelVL(-1);
        double before = farPlace.getViolations();

        var event = dispatch(ServerboundPackets.USE_ITEM_ON, westClick());

        assertFalse(event.isCancelled(), "the flagged click is forwarded");
        assertTrue(farPlace.getViolations() > before, "reach does not depend on the entity query");
        assertEquals(1, lastForwarded(), "the reconciler records the forwarded sequence");
        assertEquals(0, pendingCorrections(), "a forwarded click queues no correction");
        assertNotSimulated();
    }

    private FarPlace farPlaceWithCancelVL(int cancelVL) {
        var farPlace = player.checkManager.getCheck(FarPlace.class);
        var config = mock(ConfigManager.class);
        when(config.getIntElse(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getIntElse(farPlace.getConfigName() + ".cancelVL", 5)).thenReturn(cancelVL);
        farPlace.onReload(config);
        farPlace.setEnabled(true);
        return farPlace;
    }

    private void assertNotSimulated() {
        int air = DataTables.defaults().registry().block("minecraft:air").defaultState();
        assertEquals(air, player.compensatedWorld.getBlockStateIdAt(PLACED), "no block is predicted");
        assertFalse(player.compensatedWorld.hasPendingBlockPrediction(PLACED));
        assertEquals(16, held().count());
        assertEquals(16, player.clientItemUse.lowestCount(held()));
        assertFalse(player.isClientUsingItem());
    }

    /** A cow whose candidates straddle PLACED once its move's first transaction is answered and before the next one. */
    private void straddlingCow(Vec3 standing) throws Exception {
        JavaTickFixture.setField(CultPlayer.class, player, "resolvedClientVersion", ClientVersion.V_1_21_11);
        JavaTickFixture.setField(
                PacketEntityReplication.class, player.packetEntityReplication, "useReachBundleDelimiter", false);
        int stone = DataTables.defaults().registry().block("minecraft:stone").defaultState();
        player.compensatedWorld.updateBlock(CLICKED.getX(), CLICKED.getY(), CLICKED.getZ(), stone);
        stand(standing);
        hold("minecraft:stone", 16);
        player.packetEntityReplication.addEntity(
                COW, EntityTypeIds.COW, new Vec3(1.6, JavaTickFixture.FLOOR_Y, 1.6), 0, 0, 0, -1);
        var move = new ClientboundMoveEntity(
                COW, new EntityDelta.Linear((short) 0, (short) 0, (short) -4506), 0.0F, 0.0F, true, true, false);
        var sent = new PacketSendEvent<>(
                fixture.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
        player.packetEntityReplication.onMoveEntity(sent, player, move);
        sent.getTasksAfterSend().forEach(Runnable::run);
        fixture.pongThrough(player.lastTransactionSent.get());
        fixture.tick(standing, true);
        var candidates = player.compensatedEntities.getEntity(COW).getPossibleMovementCollisionBoxCandidates();
        var cell = new SimpleCollisionBox(PLACED);
        assertTrue(candidates.stream().anyMatch(box -> box.isIntersected(cell)), "the cow may straddle the cell");
        assertFalse(candidates.stream().allMatch(box -> box.isIntersected(cell)), "the cow may still be clear of it");
        player.packetStateData.clientSidePosition = standing;
    }

    private static ServerboundUseItemOn westClick() {
        return new ServerboundUseItemOn(
                Hand.MAIN_HAND, CLICKED, Direction.WEST, new Vec3d(0, 0.5, 0.5), false, false, 1);
    }

    private void stand(Vec3 position) {
        fixture.place(position, Vec3.ZERO);
        player.xRot = -90;
        player.yRot = 36;
        fixture.tick(position, true);
    }

    private void hold(String item, int count) {
        player.getInventory().inventory.getInventoryStorage()
                .setItem(Inventory.HOTBAR_OFFSET, ItemUtil.modelItems().stack(item, count));
        player.getInventory().selectHotbarSlot(0);
    }

    private SimItemStack held() {
        return player.getInventory().getHandItem(Hand.MAIN_HAND);
    }

    /** The production receive route: CheckManagerListener's handler, the later routes and the observer. */
    private <T extends ServerboundPacket> PacketReceiveEvent<T> dispatch(PacketType<T> type, T packet) {
        PacketReceiveEvent<T> event = fixture.event(type, packet);
        player.packetStateData.lastPacketMatchedTeleportPosition = true;
        try (var reported = new ReportedFailures()) {
            fixture.fixture.registrar.receive(event, fixture.fixture.registrar.get(type).receive());
            reported.assertNone();
        }
        assertFalse(player.packetStateData.lastPacketMatchedTeleportPosition, "the transient packet state is cleared");
        assertEquals(List.of(packet), observed, "the observer receives the handled packet");
        return event;
    }

    private int lastForwarded() {
        return (int) reconcilerField("lastForwarded");
    }

    private int pendingCorrections() {
        return ((Collection<?>) reconcilerField("pending")).size();
    }

    private Object reconcilerField(String name) {
        return field(BlockActionReconciler.class, player.blockActionReconciler, name);
    }

    private static Object correctionField(Object correction, String name) {
        return field(correction.getClass(), correction, name);
    }

    private static Object field(Class<?> owner, Object target, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
