package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * ClientPacketListener's passenger handlers run without Entity#rideTick: Entity#stopRiding changes neither ground
 * nor motion. Only a real Player#tick clears ground and only a real resync replaces the retained velocity.
 */
class ZeroTickDismountGroundTest {
    private static final int VEHICLE = 100;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput DIAGONAL_SPRINT =
            new ServerboundPlayerInput(true, false, false, true, false, false, true);

    static Stream<Arguments> endings() {
        return Stream.of(ClientVersion.V_1_21_11, ClientVersion.V_26_3)
                .flatMap(version -> Stream.of(Arguments.of(version, false), Arguments.of(version, true)));
    }

    @ParameterizedTest
    @MethodSource("endings")
    void noRideTickPreservesNonzeroVelocityGroundAndCarry(ClientVersion version, boolean removeVehicle)
            throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly(true)) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 velocity = new Vec3(0.07, -0.0784, 0.09);
            f.place(START, velocity);
            Set<Vec3> candidates = Set.of(velocity, velocity.add(0.001, 0, 0));
            f.player.checkManager.getSimulationProcessor().seedStartingVelocities(candidates);
            f.player.compensatedEntities.getSelf().deltaMovement = velocity;
            Object carry = field(f.player.checkManager.getSimulationProcessor(), "profileCarry");
            mount(f, VEHICLE);
            mount(f, VEHICLE);
            mount(f, VEHICLE + 1);
            dismount(f, VEHICLE + 1, removeVehicle);
            assertTrue(f.player.onGround);
            assertEquals(DesyncStatus.TRUE, field(f.player.checkManager.getSimulationProcessor(), "lastOnGround"));
            assertEquals(velocity, f.player.compensatedEntities.getSelf().deltaMovement);
            assertEquals(candidates, field(f.player.checkManager.getSimulationProcessor(), "validPlayerStartingVels"));
            assertSame(carry, field(f.player.checkManager.getSimulationProcessor(), "profileCarry"));
            assertFalse(f.player.checkManager.getSimulationProcessor().hasRideTick());
        }
    }

    /** The task's teleport/mount/dismount/teleport order, translated to the fixture's loaded stone floor. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void groundSprintAfterTwoTeleportsUsesGroundAcceleration(boolean excessive) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly(true)) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.place(START, Vec3.ZERO);
            f.playerPosition(START, Vec3.ZERO, Set.of());
            mount(f, VEHICLE);
            dismount(f, VEHICLE, false);
            f.playerPosition(START, Vec3.ZERO, Set.of());
            sprint(f);
            // AttributeInstance's float sprint speed and Entity#getInputVector's normalized diagonal.
            float speed = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
            double axis = speed / Math.sqrt(2.0D);
            if (excessive) axis += 0.2D;
            var result = f.tick(START.add(-axis, 0.0D, axis), true);
            if (excessive) {
                assertTrue(result.getOffset() > 1.0E-5, describe(result));
                assertTrue(result.getFlagSeverity() > 0.0D, describe(result));
            } else {
                assertTrue(exact(result), describe(result));
                assertEquals(
                        DesyncStatus.TRUE,
                        result.getSimulationContext().getWorldData().getLastOnGround());
            }
        }
    }

    @Test
    void airborneMountDismountDoesNotGainGroundSpeed() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly(true)) {
            Vec3 start = START.add(0, 3, 0);
            f.place(start, Vec3.ZERO);
            mount(f, VEHICLE);
            dismount(f, VEHICLE, false);
            assertFalse(f.player.onGround);
            sprint(f);
            double axis = 0.13F / Math.sqrt(2.0D);
            var result = f.tick(start.add(-axis, 0, axis), false);
            assertTrue(result.getOffset() > 1.0E-5, describe(result));
            assertTrue(result.getFlagSeverity() > 0, describe(result));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateListsAndVehicleSwitchesKeepTheCompletedRide(boolean switchVehicle) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly(true)) {
            f.place(START, Vec3.ZERO);
            mount(f, VEHICLE);
            f.rideTick();
            int finalVehicle = switchVehicle ? VEHICLE + 1 : VEHICLE;
            mount(f, finalVehicle);
            dismount(f, finalVehicle, false);
            assertFalse(f.player.onGround);
            assertEquals(new Vec3(0, -0.08D * 0.98F, 0), f.player.compensatedEntities.getSelf().deltaMovement);
        }
    }

    /** TickRateManager#isEntityFrozen excludes a root with a player passenger. It still executes rideTick. */
    @Test
    void aFrozenWorldStillTicksThePlayersRidingTree() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly(true)) {
            f.place(START, Vec3.ZERO);
            mount(f, VEHICLE);
            f.player.packetStateData.serverTicksFrozen = true;
            f.rideTick();
            assertTrue(f.player.checkManager.getSimulationProcessor().hasRideTick());
            dismount(f, VEHICLE, false);
            assertFalse(f.player.onGround);
        }
    }

    @Test
    void aClientWithoutTickEndKeepsTheExistingDismountModel() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly(true)) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21);
            Vec3 velocity = new Vec3(0.07, -0.0784, 0.09);
            f.place(START, velocity);
            f.player.compensatedEntities.getSelf().deltaMovement = velocity;
            mount(f, VEHICLE);
            dismount(f, VEHICLE, false);
            assertFalse(f.player.onGround);
            assertEquals(Vec3.ZERO, f.player.compensatedEntities.getSelf().deltaMovement);
        }
    }

    @Test
    void anActualZeroTickDismountResyncStillReplacesVelocity() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly(false)) {
            Vec3 velocity = new Vec3(0.07, 0.04, 0.09);
            f.place(START, velocity);
            mount(f, VEHICLE);
            f.player.compensatedEntities.getSelf().deltaMovement = velocity;
            dismount(f, VEHICLE, false);
            assertEquals(Vec3.ZERO, f.player.compensatedEntities.getSelf().deltaMovement);
            assertEquals(
                    Set.of(Vec3.ZERO),
                    field(f.player.checkManager.getSimulationProcessor(), "validPlayerStartingVels"));
            assertTrue(f.player.onGround, "a resync teleport does not run Player#tick");
        }
    }

    private static void sprint(JavaTickFixture f) {
        f.input(DIAGONAL_SPRINT);
        var command = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }

    private static void mount(JavaTickFixture f, int vehicle) {
        if (f.player.compensatedEntities.getEntity(vehicle) == null)
            f.player.compensatedEntities.addEntity(vehicle, EntityTypeIds.ARMOR_STAND, START, 0, 0, 0);
        f.setPassengers(vehicle, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
    }

    private static void dismount(JavaTickFixture f, int vehicle, boolean removeVehicle) {
        if (removeVehicle) {
            var packet = new ClientboundRemoveEntities(List.of(vehicle));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
            f.player.packetEntityReplication.onRemoveEntities(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
        } else f.setPassengers(vehicle, List.of());
        f.pongThrough(f.player.lastTransactionSent.get());
        assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static AutoCloseable observeOnly(boolean value) throws ReflectiveOperationException {
        BaseConfigManager manager = CultAPI.INSTANCE.getConfigManager();
        Field field = BaseConfigManager.class.getDeclaredField("observeOnly");
        field.setAccessible(true);
        boolean previous = field.getBoolean(manager);
        field.setBoolean(manager, value);
        return () -> field.setBoolean(manager, previous);
    }
}
