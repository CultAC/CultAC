package ac.cult.cultac.checks.impl.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.TestProtocolRuntime;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * FairReach judges an attack against the position the client has been shown at most its compensation after the server
 * sent it, whether the target rides a vehicle or the attacker does.
 */
class FairReachRiderTest {
    private static final int TARGET = 3000;
    private static final int TARGET_HORSE = 3001;
    private static final int OWN_HORSE = 3002;
    private static final int OWN_CART = 3003;
    private static final int OWN_BOAT = 3004;
    private static final int OWN_PIG = 3005;
    private static final int BLOCK = 4096;
    private static final int BOAT_PADDLE_LEFT = 11;
    private static final int HORSE_FLAGS = WatchableIndexUtil.forModel(ProtocolVersion.V1_21_11).horseFlags();

    @Test
    void aRiderIsJudgedWhereItsVehicleCarriedIt() {
        try (Fixture f = new Fixture()) {
            f.addTarget(2);
            f.addHorse(TARGET_HORSE, 2);
            f.send(new ClientboundSetPassengers(TARGET_HORSE, List.of(TARGET)));
            f.acknowledge();
            assertFalse(f.attackCancelled(), "a rider beside the attacker is in reach");

            f.send(move(TARGET_HORSE, 4));
            assertFalse(f.attackCancelled(), "the client may not have the vehicle's move yet");

            f.passDeadlines();
            f.tick(3);
            assertTrue(f.attackCancelled(), "the vehicle's move is due, so its rider is out of reach");
        }
    }

    @Test
    void aMountedAttackerIsJudgedFromItsSeat() {
        try (Fixture f = new Fixture()) {
            f.addHorse(OWN_HORSE, 0);
            f.send(new ClientboundSetPassengers(OWN_HORSE, List.of(f.player.entityID)));
            f.acknowledge();
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle(), "the attacker rides its horse");
            f.addTarget(2);
            assertFalse(f.attackCancelled(), "a target beside the mounted attacker is in reach");

            f.send(move(TARGET, 4));
            assertFalse(f.attackCancelled(), "the client may not have the target's move yet");

            f.passDeadlines();
            f.tick(3);
            assertTrue(f.attackCancelled(), "the target's move is due, so it is out of reach");
        }
    }

    @Test
    void aPassengerIsJudgedFromWhereTheServerMovedItsVehicle() {
        try (Fixture f = new Fixture()) {
            f.addVehicle(OWN_CART, EntityTypes.defaults().byKey("minecraft:minecart").id(), 0);
            f.send(new ClientboundSetPassengers(OWN_CART, List.of(f.player.entityID)));
            f.acknowledge();
            f.tick(1);
            f.addTarget(2);
            assertFalse(f.attackCancelled(), "a target beside the cart's passenger is in reach");

            f.send(move(OWN_CART, -4));
            assertFalse(f.attackCancelled(), "the client may not have the cart's move yet");

            f.passDeadlines();
            f.tick(10);
            assertTrue(f.attackCancelled(), "the cart's move is due, so it carried its passenger out of reach");
        }
    }

    @Test
    void aRiderMovesWithItsVehicleUntilTheClientRemovesIt() {
        try (Fixture f = new Fixture()) {
            f.addTarget(6);
            f.addHorse(TARGET_HORSE, 6);
            f.send(new ClientboundSetPassengers(TARGET_HORSE, List.of(TARGET)));
            f.acknowledge();
            f.tick(3);
            assertTrue(f.attackCancelled(), "the rider is out of reach");

            f.fairReach.max_ping_compensation = 300;
            f.send(move(TARGET_HORSE, -4));
            int moved = f.ping();
            f.remove(TARGET_HORSE);
            f.acknowledge(moved);
            f.tick(3);
            assertFalse(f.attackCancelled(), "the client shows the rider where its vehicle carried it");

            f.acknowledge();
            f.tick(3);
            assertFalse(f.attackCancelled(), "Entity#setRemoved leaves the rider on its last seat");
        }
    }

    @Test
    void aRidersOwnRotationLeavesItOnItsSeat() {
        try (Fixture f = new Fixture()) {
            f.addTarget(6);
            f.addHorse(TARGET_HORSE, 6);
            f.send(new ClientboundSetPassengers(TARGET_HORSE, List.of(TARGET)));
            f.send(move(TARGET_HORSE, -4));
            f.acknowledge();
            f.tick(3);
            assertFalse(f.attackCancelled(), "the vehicle carried its rider into reach");

            f.send(new ClientboundMoveEntity(
                    TARGET, new EntityDelta.Linear((short) 0, (short) 0, (short) 0), 90.0F, 0.0F, true, false, true));
            f.acknowledge();
            f.tick(3);
            assertFalse(f.attackCancelled(), "Entity#positionRider keeps the rider on its seat");
        }
    }

    @Test
    void aDismountedRiderStaysOnItsLastSeat() {
        try (Fixture f = new Fixture()) {
            f.addTarget(6);
            f.addHorse(TARGET_HORSE, 6);
            f.send(new ClientboundSetPassengers(TARGET_HORSE, List.of(TARGET)));
            f.send(move(TARGET_HORSE, -4));
            f.acknowledge();
            f.tick(3);
            assertFalse(f.attackCancelled(), "the vehicle carried its rider into reach");

            f.send(new ClientboundSetPassengers(TARGET_HORSE, List.of()));
            f.acknowledge();
            f.tick(3);
            assertFalse(f.attackCancelled(), "Entity#stopRiding leaves the rider on its last seat");
        }
    }

    @Test
    void aPassengerTheServerDismountedIsJudgedFromWhereItWasTeleported() {
        try (Fixture f = new Fixture()) {
            f.addVehicle(OWN_CART, EntityTypes.defaults().byKey("minecraft:minecart").id(), 0);
            f.send(new ClientboundSetPassengers(OWN_CART, List.of(f.player.entityID)));
            f.acknowledge();
            f.tick(1);
            f.addTarget(2.5);
            assertFalse(f.attackCancelled(), "a target beside the cart's passenger is in reach");

            f.fairReach.max_ping_compensation = 300;
            f.send(new ClientboundSetPassengers(OWN_CART, List.of()));
            f.teleport(-3);
            assertFalse(f.attackCancelled(), "the client may not have its dismount yet");

            f.tick(7);
            assertTrue(f.attackCancelled(), "the dismount teleport is due, so the attacker is out of reach");
        }
    }

    @Test
    void aDriverTheServerDismountedIsJudgedFromWhereItWasTeleported() {
        try (Fixture f = new Fixture()) {
            f.addVehicle(OWN_BOAT, EntityTypeIds.OAK_BOAT, 0);
            f.send(new ClientboundSetPassengers(OWN_BOAT, List.of(f.player.entityID)));
            f.acknowledge();
            f.tick(1);
            f.addTarget(2.5);
            assertFalse(f.attackCancelled(), "a target beside the boat's driver is in reach");

            f.fairReach.max_ping_compensation = 300;
            f.send(new ClientboundSetPassengers(OWN_BOAT, List.of()));
            f.teleport(-3);
            assertFalse(f.attackCancelled(), "the client may not have its dismount yet");

            f.tick(7);
            assertTrue(
                    f.player.compensatedEntities.getSelf().inVehicle(), "the client has not answered the dismount");
            assertTrue(f.attackCancelled(), "the dismount teleport is due, so the attacker is out of reach");
        }
    }

    @Test
    void aRidingChangeForAReplacedLevelLeavesTheNewLevelAlone() {
        try (Fixture f = new Fixture()) {
            f.fairReach.handlePassengers(
                    OWN_CART, new int[] {f.player.entityID}, f.player.lastTransactionSent.get() + 1);
            f.fairReach.clearLevel();
            f.acknowledge();
            f.tick(1);
            f.addTarget(5);
            f.player.x = 3;
            assertFalse(f.attackCancelled(), "the attacker attacks from where it stands in the new level");
        }
    }

    @Test
    void aFirstMountInABundleSeatsTheAttackerWhenTheClientHandlesIt() {
        try (Fixture f = new Fixture()) {
            f.addHorse(OWN_HORSE, 3);
            f.addTarget(6);
            assertTrue(f.attackCancelled(), "the target is out of reach of the attacker on foot");

            f.fairReach.max_ping_compensation = 300;
            int bundled = f.ping();
            f.send(new ClientboundSetPassengers(OWN_HORSE, List.of(f.player.entityID)), bundled);
            f.acknowledge(bundled);
            f.tick(1);
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle(), "the client handled the mount");
            assertFalse(f.attackCancelled(), "the attacker attacks from its seat");
        }
    }

    @Test
    void aDriverWhoseSteeringItemTheServerReplacedIsJudgedFromWhereTheServerHoldsItsVehicle() {
        try (Fixture f = new Fixture()) {
            f.drivePig();

            f.fairReach.max_ping_compensation = 300;
            f.setPlayerInventorySlot(0, stack("fishing_rod"));
            assertFalse(f.attackCancelled(), "the client may not have its new item yet");

            f.tick(7);
            assertTrue(
                    f.player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(
                            f.player.compensatedEntities.getEntity(OWN_PIG)),
                    "the client has not answered the item change");
            assertTrue(f.attackCancelled(), "the item change is due, so the server moves the pig");
        }
    }

    @Test
    void aDriverWhoseSaddleTheServerRemovedIsJudgedFromWhereTheServerHoldsItsVehicle() {
        try (Fixture f = new Fixture()) {
            f.drivePig();

            f.fairReach.max_ping_compensation = 300;
            f.send(new InventoryPackets.Equipment(
                    OWN_PIG, List.of(new InventoryPackets.EquipmentEntry(EquipmentSlot.SADDLE, SimItemStack.EMPTY))));
            assertFalse(f.attackCancelled(), "the client may not have the saddle change yet");

            f.tick(7);
            assertTrue(f.attackCancelled(), "the saddle change is due, so the server moves the pig");
        }
    }

    @Test
    void aDriverWhoseVehicleTheServerMadeABabyIsJudgedFromWhereTheServerHoldsIt() {
        try (Fixture f = new Fixture()) {
            f.drivePig();

            f.fairReach.max_ping_compensation = 300;
            f.send(data(OWN_PIG, WatchableIndexUtil.AGEABLE_BABY, true));
            assertFalse(f.attackCancelled(), "the client may not have the age change yet");

            f.tick(7);
            assertTrue(f.attackCancelled(), "the age change is due, so the server moves the pig");
        }
    }

    @Test
    void entityDataThatDecidesNoControlLeavesTheDriverOnTheVehicleItDrives() {
        try (Fixture f = new Fixture()) {
            f.addVehicle(OWN_BOAT, EntityTypeIds.OAK_BOAT, 0);
            f.drive(OWN_BOAT);
            f.fairReach.max_ping_compensation = 300;
            for (int stroke = 0; stroke < 3; stroke++) {
                // AbstractBoat#setPaddleState, from the driver's input.
                f.send(data(OWN_BOAT, BOAT_PADDLE_LEFT, stroke % 2 == 0));
                f.tick(7);
                assertFalse(f.attackCancelled(), "the boat's driver attacks from where it drove it");
            }
        }
        try (Fixture f = new Fixture()) {
            f.addVehicle(OWN_HORSE, EntityTypeIds.HORSE, 0);
            PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(OWN_HORSE);
            horse.hasSaddle = true;
            horse.isTame = true;
            f.drive(OWN_HORSE);
            f.fairReach.max_ping_compensation = 300;
            // AbstractHorse#setEating keeps the tame flag.
            f.send(data(OWN_HORSE, HORSE_FLAGS, (byte) 0x12));
            f.tick(7);
            assertFalse(f.attackCancelled(), "the horse's driver attacks from where it drove it");
        }
    }

    private static SimItemStack stack(String item) {
        return ItemUtil.modelItems().stack("minecraft:" + item, 1);
    }

    private static EntityMetadata data(int entityId, int index, Object value) {
        return new EntityMetadata(entityId, List.of(new EntityMetadata.Entry(index, value, ByteBuffer.allocate(0))));
    }

    @SuppressWarnings("unchecked")
    private static <T> PacketType<T> type(Class<T> packet) {
        return (PacketType<T>) TestProtocolRuntime.catalog().stream()
                .filter(each -> each.recordClass() == packet)
                .findFirst()
                .orElseThrow();
    }

    private static ClientboundMoveEntity move(int entityId, int blocks) {
        return new ClientboundMoveEntity(
                entityId,
                new EntityDelta.Linear((short) (blocks * BLOCK), (short) 0, (short) 0),
                0.0F,
                0.0F,
                true,
                true,
                false);
    }

    private static final class Fixture implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final CultPlayer player;
        final FairReach fairReach;

        Fixture() {
            OfflineCultTestBootstrap.installConfig();
            channel.freezeTime();
            User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Fair_Rider"), channel, platform());
            player = new CultPlayer(user);
            user.bind(Mockito.mock(PlatformPlayer.class));
            player.entityID = 7;
            player.x = 0;
            player.y = 64;
            player.z = 0;
            fairReach = player.checkManager.getListener(FairReach.class);
            fairReach.max_ping_compensation = 0;
            // The join teleport, sent before any other.
            player.getSetbackTeleportUtil()
                    .javaTeleports()
                    .addSentTeleport(
                            new Vec3(0, 64, 0), new Vec3(0, 0, 0), 0, new RelativeFlag(0), false, 0, 0, 0, 0, 0);
        }

        void addTarget(double x) {
            fairReach.onAddEntity(
                    null, player, new ClientboundAddEntity(TARGET, "minecraft:player", new Vec3d(x, 64, 0), 0, 0, 0));
            player.packetEntityReplication.addEntity(TARGET, EntityTypeIds.PLAYER, new Vec3(x, 64, 0), 0, 0, 0, -1);
            acknowledge();
        }

        void addHorse(int entityId, double x) {
            addVehicle(entityId, EntityTypeIds.HORSE, x);
        }

        void addVehicle(int entityId, int type, double x) {
            player.packetEntityReplication.addEntity(entityId, type, new Vec3(x, 64, 0), 0, 0, 0, -1);
            acknowledge();
        }

        void remove(int entityId) {
            var packet = new ClientboundRemoveEntities(List.of(entityId));
            var event = new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
            fairReach.onRemoveEntities(event, player, packet);
            player.packetEntityReplication.onRemoveEntities(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
        }

        /** A ping the client has not answered yet; later packets reach it after this one. */
        int ping() {
            return player.lastTransactionSent.incrementAndGet();
        }

        void acknowledge(int transaction) {
            player.lastTransactionReceived.set(transaction);
            player.latencyUtils.handleNettySyncTransaction(transaction);
        }

        void send(ClientboundMoveEntity packet) {
            player.packetEntityReplication.onMoveEntity(
                    new PacketSendEvent<>(
                            player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false),
                    player,
                    packet);
        }

        void send(ClientboundSetPassengers packet) {
            send(packet, 0);
        }

        /** A set_passengers in a flush bundle that holds the ping of {@code sharedPing}; 0 for none. */
        void send(ClientboundSetPassengers packet, int sharedPing) {
            var event = new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
            event.sharedPing(sharedPing);
            player.packetEntityReplication.onSetPassengers(event, player, packet);
        }

        /** A set_player_inventory for Inventory's {@code slot}. */
        void setPlayerInventorySlot(int slot, SimItemStack item) {
            var packet = new InventoryPackets.PlayerInventory(slot, item);
            var event = new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, type(InventoryPackets.PlayerInventory.class), packet, false);
            player.getInventory().onSetPlayerInventory(event, player, packet);
        }

        void send(InventoryPackets.Equipment packet) {
            var event = new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, type(InventoryPackets.Equipment.class), packet, false);
            player.packetEntityReplication.onSetEquipment(event, player, packet);
        }

        void send(EntityMetadata packet) {
            var event =
                    new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, type(EntityMetadata.class), packet, false);
            player.packetEntityReplication.onSetEntityData(event, player, packet);
        }

        /** A saddled pig the attacker steers with a carrot on a stick; see {@link #drive}. */
        void drivePig() {
            addVehicle(OWN_PIG, EntityTypeIds.PIG, 0);
            ((PacketEntityRideable) player.compensatedEntities.getEntity(OWN_PIG)).hasSaddle = true;
            player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET, stack("carrot_on_a_stick"));
            drive(OWN_PIG);
        }

        /**
         * The attacker mounts {@code vehicleId}, which the server holds at 0, and drives it to 3, with the target at 5.
         */
        void drive(int vehicleId) {
            send(new ClientboundSetPassengers(vehicleId, List.of(player.entityID)));
            acknowledge();
            tick(1);
            addTarget(5);
            // The client's own vehicle moves put its vehicle here.
            player.compensatedEntities
                    .getEntity(vehicleId)
                    .setPositionRaw(new SimpleCollisionBox(2.55, 64, -0.45, 3.45, 64.9, 0.45));
            assertFalse(attackCancelled(), "the driver attacks from where it drove its vehicle");
        }

        /** LivingEntity#dismountVehicle: the server teleports the player to {@code x} with an absolute position. */
        void teleport(double x) {
            var teleport = new ClientboundPlayerPosition(
                    1, new Vec3d(x, 64, 0), new Vec3d(0, 0, 0), 0, 0, Set.of(Relative.Y_ROT, Relative.X_ROT));
            var event = new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, teleport, false);
            new PacketServerTeleport().onPlayerPosition(event, player, teleport);
            event.getTasksAfterSend().forEach(Runnable::run);
        }

        /** The client answers every ping sent so far. */
        void acknowledge() {
            int transaction = player.lastTransactionSent.get() + 1;
            player.lastTransactionSent.set(transaction);
            player.lastTransactionReceived.set(transaction);
            player.latencyUtils.handleNettySyncTransaction(transaction);
        }

        void passDeadlines() {
            channel.runScheduledPendingTasks();
        }

        void tick(int ticks) {
            for (int i = 0; i < ticks; i++) {
                channel.advanceTimeBy(50, TimeUnit.MILLISECONDS);
                channel.runScheduledPendingTasks();
                fairReach.onClientTickEnd(null, player, null);
            }
        }

        boolean attackCancelled() {
            var attack =
                    new ServerboundInteract(TARGET, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            var event =
                    new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, attack);
            fairReach.onInteract(event, player, attack);
            return event.isCancelled();
        }

        @Override
        public void close() {
            player.onRemove();
            channel.runPendingTasks();
            channel.finishAndReleaseAll();
        }
    }

    private static PlatformConnection platform() {
        return new PlatformConnection() {
            @Override
            public User.Profile authenticatedProfile() {
                return null;
            }

            @Override
            public PlayerBinding playerBinding() {
                return null;
            }

            @Override
            public ProtocolVersion getObservedProtocol() {
                return ProtocolVersion.V1_21_11;
            }

            @Override
            public ProtocolVersion getClientProtocol() {
                return ProtocolVersion.V1_21_11;
            }

            @Override
            public void disconnect(Component reason) {}

            @Override
            public void sendMessage(Component message) {}
        };
    }
}
