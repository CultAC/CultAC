package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.combat.RidingInteract;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.manager.player.SetbackFixtures;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RidingInteractTest {
    private static final int BOAT = 40, ZOMBIE = 41, COW = 42, CRYSTAL = 43, RIDER = 44;
    // A 26.1 client sends a right-click as one packet with the clicked point.
    private static final List<InteractAction> ACTIONS = List.of(InteractAction.ATTACK, InteractAction.INTERACT_AT);

    @Test
    void clicksOnTheVehicleAndAFellowPassengerFlag() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.rideBoatWithZombie();
            for (int target : new int[] {BOAT, ZOMBIE}) {
                for (var action : ACTIONS) {
                    double before = setup.check.violations;
                    assertTrue(setup.route(target, action).isCancelled());
                    assertEquals(before + 1, setup.check.violations);
                }
            }
        }
    }

    @Test
    void clicksOutsideTheRidingTreeAreNotJudged() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.rideBoatWithZombie();
            setup.entity(COW, EntityTypeIds.COW, new Vec3(.5, 64, .5));
            for (var action : ACTIONS) assertFalse(setup.route(COW, action).isCancelled());
            // On foot the boat is no longer the player's vehicle.
            setup.self().eject();
            for (var action : ACTIONS) assertFalse(setup.route(BOAT, action).isCancelled());
            assertEquals(0, setup.check.violations);
        }
    }

    @Test
    void aRiderWhoseBoxHoldsTheEyeIsPickedFromInside() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            // A box around the eye that rides the player, as a carried player does.
            setup.entity(RIDER, EntityTypeIds.END_CRYSTAL, new Vec3(.5, 64.6, -1.5));
            setup.entity(RIDER).mount(setup.self());
            assertTrue(setup.player.compensatedEntities.certainlySharesRootVehicleWithSelf(setup.entity(RIDER)));
            for (var action : ACTIONS) assertFalse(setup.route(RIDER, action).isCancelled());
            assertEquals(0, setup.check.violations);
        }
    }

    @Test
    void anotherEntityAroundTheEyeLetsTheRayPickTheVehicle() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.rideBoatWithZombie();
            assertTrue(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
            Vec3 eye = setup.eye();
            setup.entity(CRYSTAL, EntityTypeIds.END_CRYSTAL, new Vec3(eye.x, eye.y - 1, eye.z));
            for (var action : ACTIONS) assertFalse(setup.route(BOAT, action).isCancelled());
            assertEquals(1, setup.check.violations);
        }
    }

    @Test
    void anItemRangePickSelectsTheVehicle() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.rideBoatWithZombie();
            setup.hold("{\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            for (var action : ACTIONS) assertFalse(setup.route(BOAT, action).isCancelled());
            assertEquals(0, setup.check.violations);
            setup.tickEnd();
            setup.player.getInventory().inventory.setHeldItem(SimItemStack.EMPTY);
            assertTrue(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @Test
    void anUnansweredPassengerChangeIsNotJudged() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.rideBoatWithZombie();
            setup.entity(COW, EntityTypeIds.COW, new Vec3(.5, 64, .5));
            // The cow mounts the zombie: until the client answers, it may still stand outside the riding tree.
            int mounted = setup.setPassengers(ZOMBIE, List.of(COW));
            assertFalse(setup.route(COW, InteractAction.INTERACT_AT).isCancelled());
            assertTrue(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
            setup.pongThrough(mounted);
            assertTrue(setup.route(COW, InteractAction.INTERACT_AT).isCancelled());
            // The cow leaves: until the client answers, it may already stand outside the riding tree.
            int left = setup.setPassengers(ZOMBIE, List.of());
            assertFalse(setup.route(COW, InteractAction.INTERACT_AT).isCancelled());
            assertTrue(setup.route(ZOMBIE, InteractAction.INTERACT_AT).isCancelled());
            setup.pongThrough(left);
            assertFalse(setup.route(COW, InteractAction.INTERACT_AT).isCancelled());
            assertEquals(3, setup.check.violations);
        }
    }

    @Test
    void aSpectatorActionOnTheVehicleFlags() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.rideBoatWithZombie();
            setup.player.gamemode = GameMode.SPECTATOR;
            var packet = new ServerboundSpectatorAction(OptionalInt.of(BOAT));
            var event = new PacketReceiveEvent<>(
                    setup.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.SPECTATOR_ACTION, packet);
            setup.player.checkManager.dispatchEarlyReceive(event);
            assertTrue(event.isCancelled());
            assertEquals(1, setup.check.violations);
        }
    }

    @Test
    void anInteractionDecodedAcrossTheAttackPacketSplitIsNotJudged() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V1_21_11)) {
            setup.rideBoatWithZombie();
            for (var action : InteractAction.values())
                assertFalse(setup.route(BOAT, action).isCancelled());
            assertEquals(0, setup.check.violations);
        }
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture;
        final CultPlayer player;
        final ProtocolVersion version;
        final RidingInteract check;

        Setup(ProtocolVersion version) throws Exception {
            this.version = version;
            fixture = version == ProtocolVersion.V26_3
                    ? new RecordReceiveFixture(version, false)
                    : RecordReceiveFixture.olderHost(version, false);
            services.useNetwork(fixture.manager);
            CultAPI.INSTANCE.getPlayerDataManager().addUser(fixture.user);
            player = fixture.user.getCultPlayer();
            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            check = player.checkManager.getCheck(RidingInteract.class);
            check.setEnabled(true);
            player.setExperimentalChecks(true);
            SetbackFixtures.setLoaded(player.getSetbackTeleportUtil(), true);
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -1.5;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, .5, 64, -1.5);
            player.registryState = new ClientComponentRegistries();
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(0, -1);
            fixture.registrar.register(routes -> new CheckManagerListener()
                    .registerForwardingEarlyReceivePackets(routes, PacketListenerPriority.LOW));
        }

        PacketEntity self() {
            return player.compensatedEntities.getSelf();
        }

        PacketEntity entity(int id) {
            return player.compensatedEntities.getEntity(id);
        }

        void entity(int id, int type, Vec3 position) {
            player.compensatedEntities.addEntity(id, type, position, 0, 0, 0);
        }

        // The player behind a zombie in a boat at its feet, as the client holds them.
        void rideBoatWithZombie() {
            entity(BOAT, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, -1.5));
            entity(ZOMBIE, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, -1.5));
            entity(ZOMBIE).mount(entity(BOAT));
            self().mount(entity(BOAT));
        }

        // The standing eye of the player on its seat.
        Vec3 eye() {
            var riding = self().getRiding();
            Vec3 seat = BoundingBoxSize.getRidingOffsetFromVehicle(
                    riding,
                    self(),
                    player,
                    riding.getPositionCollisionBoxCandidates().getFirst());
            return seat.add(0, player.getEyeHeight(), 0);
        }

        void hold(String attackRange) {
            var sword = ItemUtil.modelItems().stack("minecraft:iron_sword", 1);
            var components = new Components(Map.of("minecraft:attack_range", JsonParser.parseString(attackRange)));
            player.getInventory().inventory.setHeldItem(new SimItemStack(sword.definition(), 1, components));
        }

        /** Sends a set_passengers without answering it; returns the transaction that proves it. */
        int setPassengers(int vehicle, List<Integer> passengers) {
            var packet = new ClientboundSetPassengers(vehicle, passengers);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
            player.packetEntityReplication.onSetPassengers(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
            return player.lastTransactionSent.get() + 1;
        }

        void pongThrough(int transaction) {
            for (int id = player.lastTransactionReceived.get() + 1; id <= transaction; id++) {
                player.lastTransactionReceived.set(id);
                player.latencyUtils.handleNettySyncTransaction(id);
            }
        }

        PacketReceiveEvent<ServerboundInteract> route(int id, InteractAction action) {
            var packet = new ServerboundInteract(
                    id,
                    action,
                    Hand.MAIN_HAND,
                    action == InteractAction.INTERACT_AT ? Optional.of(new Vec3d(0, .5, -.3)) : Optional.empty(),
                    false);
            var event =
                    new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet);
            player.checkManager.dispatchEarlyReceive(event);
            return event;
        }

        void tickEnd() {
            player.checkManager.getListener(ClientVisibilityProcessor.class).onPlayerTickEnd(null);
        }

        @Override
        public void close() throws Exception {
            try {
                fixture.close();
            } finally {
                services.close();
            }
        }
    }
}
