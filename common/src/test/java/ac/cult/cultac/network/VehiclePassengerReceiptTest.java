package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.checks.VehicleOffsetCheck;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ClientPacketListener#handleSetEntityPassengersPacket rebuilds passengers between ticks. AbstractHorse's saddled
 * first passenger remains the controller when only a rear passenger changes; the packet changes no root motion.
 */
class VehiclePassengerReceiptTest {
    private static final int CAMEL = 100;
    private static final int REAR = 101;
    private static final int ATTACHMENT = 10;
    private static final int UPDATE = 11;
    private static final Vec3 POSITION = new Vec3(0.5, 64, 0.5);
    private static final Vec3 CARRY = new Vec3(0.16537225600119138, -0.0784000015258789, 0.09852737452212806);

    private RecordReceiveFixture mounted(boolean rear, boolean received) {
        OfflineCultTestBootstrap.installConfig();
        var f = new RecordReceiveFixture(ProtocolVersion.of(775));
        f.user.bind(mock(PlatformPlayer.class));
        var p = f.player;
        // Supply a fully standing camel, past Camel#isInPoseTransition's 52-tick stand-up period.
        p.compensatedWorld.applyClientTime(new TimeUpdate(100, Map.of()));
        p.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, POSITION, 0, 0, 0);
        p.compensatedEntities.addEntity(REAR, EntityTypeIds.PLAYER, POSITION, 0, 0, 0);
        ((PacketEntityHorse) p.compensatedEntities.getEntity(CAMEL)).hasSaddle = true;
        int[] passengers = rear ? new int[] {p.entityID, REAR} : new int[] {p.entityID};
        p.compensatedEntities.vehicles.setServerVehicle(CAMEL, passengers, ATTACHMENT);
        p.lastTransactionSent.set(ATTACHMENT);
        p.lastTransactionReceived.set(received ? ATTACHMENT : ATTACHMENT - 1);
        if (received) p.compensatedEntities.vehicles.applyVehiclePassengers(CAMEL, passengers);
        p.compensatedEntities.getEntity(CAMEL).deltaMovement = CARRY;
        return f;
    }

    @ParameterizedTest
    @ValueSource(strings = {"identical", "add", "remove"})
    void passengerUpdatesKeepProvenControlButDelayThePassengerGraph(String transition) throws Exception {
        try (var f = mounted(transition.equals("remove"), true);
                var observe = RecordReceiveFixture.observeOnly(true)) {
            var p = f.player;
            var state = p.compensatedEntities.vehicles;
            var camel = p.compensatedEntities.getEntity(CAMEL);
            List<Integer> before =
                    camel.passengers.stream().map(e -> e.getEntityId()).toList();
            List<Integer> after = transition.equals("add") ? List.of(p.entityID, REAR) : List.of(p.entityID);
            var packet = new ClientboundSetPassengers(CAMEL, after);
            var event = new PacketSendEvent<>(
                    p.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);

            p.packetEntityReplication.onSetPassengers(event, p, packet);
            event.tasksAfter().forEach(Runnable::run);
            int graphReceipt = p.lastTransactionSent.get();

            assertTrue(graphReceipt > p.lastTransactionReceived.get());
            assertEquals(ATTACHMENT, state.serverPlayerVehicleTransaction);
            assertTrue(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
            assertEquals(
                    before, camel.passengers.stream().map(e -> e.getEntityId()).toList());

            p.lastTransactionReceived.set(graphReceipt);
            p.latencyUtils.handleNettySyncTransaction(graphReceipt);
            assertEquals(
                    after, camel.passengers.stream().map(e -> e.getEntityId()).toList());
            assertTrue(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
            assertEquals(POSITION, camel.clientPhysicalPosition);
            assertEquals(CARRY, camel.deltaMovement);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"identical", "add", "remove"})
    void anUpdateCannotProveAnUnreceivedFirstMount(String transition) {
        try (var f = mounted(transition.equals("remove"), false)) {
            var p = f.player;
            var state = p.compensatedEntities.vehicles;
            int[] after = transition.equals("add") ? new int[] {p.entityID, REAR} : new int[] {p.entityID};
            state.setServerVehicle(CAMEL, after, UPDATE);
            assertEquals(ATTACHMENT, state.serverPlayerVehicleTransaction);
            assertFalse(state.canCurrentPlayerControlServerVehicleForClientTickMovement());

            p.lastTransactionReceived.set(ATTACHMENT);
            assertTrue(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
        }
    }

    @Test
    void acquiringTheFirstSeatRequiresNewReceipt() {
        try (var f = mounted(true, true)) {
            var p = f.player;
            var state = p.compensatedEntities.vehicles;
            state.setServerVehicle(CAMEL, new int[] {REAR, p.entityID}, ATTACHMENT);
            state.setServerVehicle(CAMEL, new int[] {p.entityID, REAR}, UPDATE);
            assertEquals(UPDATE, state.serverPlayerVehicleTransaction);
            assertFalse(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
            p.lastTransactionReceived.set(UPDATE);
            assertTrue(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
        }
    }

    @Test
    void losingTheFirstSeatLosesControl() {
        try (var f = mounted(true, true)) {
            var p = f.player;
            var state = p.compensatedEntities.vehicles;
            state.setServerVehicle(CAMEL, new int[] {REAR, p.entityID}, UPDATE);
            assertEquals(UPDATE, state.serverPlayerVehicleTransaction);
            assertFalse(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
            p.lastTransactionReceived.set(UPDATE);
            assertFalse(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
        }
    }

    @Test
    void changingVehiclesRequiresNewReceipt() {
        try (var f = mounted(false, true)) {
            var p = f.player;
            int other = CAMEL + 2;
            p.compensatedEntities.addEntity(other, EntityTypeIds.CAMEL, POSITION, 0, 0, 0);
            ((PacketEntityHorse) p.compensatedEntities.getEntity(other)).hasSaddle = true;
            var state = p.compensatedEntities.vehicles;
            state.setServerVehicle(other, new int[] {p.entityID}, UPDATE);
            assertEquals(UPDATE, state.serverPlayerVehicleTransaction);
            assertFalse(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
            p.lastTransactionReceived.set(UPDATE);
            assertTrue(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void remountAfterPendingOrCompletedDismountRequiresNewReceipt(boolean completeDismount) {
        try (var f = mounted(false, true)) {
            var p = f.player;
            var state = p.compensatedEntities.vehicles;
            state.setServerVehicleDismount(CAMEL, UPDATE);
            if (completeDismount) state.applyClientVisibleDismount();
            state.setServerVehicle(CAMEL, new int[] {p.entityID}, UPDATE + 1);
            assertEquals(UPDATE + 1, state.serverPlayerVehicleTransaction);
            assertFalse(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
            p.lastTransactionReceived.set(UPDATE);
            assertFalse(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
            p.lastTransactionReceived.set(UPDATE + 1);
            assertTrue(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
        }
    }

    @Test
    void passengerArraysAreCopiedEvenWhenTheControllerIsUnchanged() {
        try (var f = mounted(false, true)) {
            var p = f.player;
            var state = p.compensatedEntities.vehicles;
            int[] after = {p.entityID, REAR};
            state.setServerVehicle(CAMEL, after, UPDATE);
            after[0] = REAR;
            assertArrayEquals(new int[] {p.entityID, REAR}, state.serverPlayerVehiclePassengers);
            assertTrue(state.canCurrentPlayerControlServerVehicleForClientTickMovement());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedLandingKeepsRiddenAccelerationBeforeTheUpdateReceipt(boolean rear) throws Exception {
        try (var f = mounted(rear, true)) {
            Class.forName(ac.cult.cultac.checks.impl.verbose.VerboseCodecs.class.getName());
            var p = f.player;
            p.compensatedEntities.vehicles.setServerVehicle(CAMEL, new int[] {p.entityID}, UPDATE);
            p.packetStateData.markVehicleMove(true, true);
            var ctx = mock(SimulationContext.class);
            var world = mock(WorldData.class);
            var version = p.getClientVersion();
            when(ctx.getVersion()).thenReturn(version);
            when(ctx.getVehicle()).thenReturn(p.compensatedEntities.getEntity(CAMEL));
            when(ctx.getHorseInputs()).thenReturn(new Vec3(0, 0, 1));
            when(ctx.getXRot()).thenReturn(324.39648F);
            when(ctx.getWorldData()).thenReturn(world);
            when(ctx.getCamelRiderSprinting(p)).thenReturn(DesyncStatus.FALSE);
            when(world.getInWater()).thenReturn(DesyncStatus.FALSE);
            when(world.getInLava()).thenReturn(DesyncStatus.FALSE);
            when(world.getLastOnGround()).thenReturn(DesyncStatus.FALSE);
            when(ctx.getMoveRelativeSpeed(p, false, false, false, false)).thenReturn(.019F);

            // Captured dry, airborne carry and horizontal displacement at tick 42989. This component test supplies
            // the captured speed and compares X/Z only; it does not reconstruct the missing terrain/collision state.
            Vec3 carry = new Vec3(.17088685196839976, -.3766304982386517, .0931331663597394);
            var method = VelocityTransformer.class.getDeclaredMethod(
                    "applyRideableInput", ac.cult.cultac.player.CultPlayer.class, List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            var candidates =
                    (List<PredVector>) method.invoke(new VelocityTransformer(ctx), p, List.of(new PredVector(carry)));
            assertEquals(1, candidates.size());
            Vec3 movement = candidates.getFirst();
            assertEquals(.18172774861010055, movement.x, 1e-7);
            assertEquals(.10827183701383092, movement.z, 1e-7);

            var valid = mock(ValidMovements.class);
            when(valid.getClosestToTarget()).thenReturn(movement);
            var accepted = new PredictionResult(p, new PredVector(carry), ctx, movement, null, List.of(), List.of());
            accepted.setValidMovements(valid);
            new VehicleOffsetCheck().handleResult(p, accepted, null);
            assertTrue(accepted.getFlags().isEmpty());

            var excessive = new PredictionResult(
                    p, new PredVector(carry), ctx, movement.add(.5, 0, 0), null, List.of(), List.of());
            excessive.setValidMovements(valid);
            new VehicleOffsetCheck().handleResult(p, excessive, null);
            assertEquals(1, excessive.getFlags().size());
            assertEquals(.5, excessive.getFlagSeverity(), 1e-12);
        }
    }
}
