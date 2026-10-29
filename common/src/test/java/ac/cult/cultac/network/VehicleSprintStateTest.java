package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsC;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsD;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.sprint.SprintH;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LocalPlayer#aiStep clears the sprint flag of a passenger unless its vehicle can sprint (only a Camel) and the player
 * controls it, and LocalPlayer#sendChanges sends no STOP_SPRINTING for a vehicle the player does not control, so the
 * server keeps the last START_SPRINTING.
 */
class VehicleSprintStateTest {
    private static final int VEHICLE = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 1, 0.5);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    /**
     * LocalPlayer#wasSprinting stays true while a passive passenger skips sendIsSprintingIfNeeded. Holding the keys
     * across the entire ride and dismount therefore resumes sprinting without another START_SPRINTING command.
     */
    @ParameterizedTest(name = "{0}: continuous sprint keeps its announcement through a passive ride and dismount")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void continuousSprintSurvivesAPassiveRideWithoutAnotherAnnouncement(String release, ClientVersion version)
            throws Exception {
        try (var f = ride(EntityTypeIds.BAT, true, version)) {
            f.input(FORWARD_SPRINT);
            for (int tick = 0; tick < 20; tick++) {
                f.rideTick();
                assertTrue(f.player.isSprinting, "ride tick " + tick + " changed the last sprint announcement");
            }
            f.setPassengers(VEHICLE, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();

            assertTrue(f.player.isSprinting);
            assertTrue(f.player.isClientSprinting());
            assertTrue(f.player.sprintState.flag());
            assertTrue(f.player.sprintState.modifier());
        }
    }

    /**
     * From 1.21.5 shouldStopRunSprinting clears the passive rider's flag. Player#getFlyingSpeed reads that local
     * flag, and Entity#rideTick zeroes velocity each tick, so its dismount carries exactly the unsprinted air travel.
     */
    @ParameterizedTest(name = "{0}: passive air carry uses the local flag before a continuous sprint resumes")
    @MethodSource("modernReleases")
    void aPassiveRideCarriesUnsprintedTravelBeforeSprintResumes(String release, ClientVersion version)
            throws Exception {
        try (var f = ride(EntityTypeIds.BAT, true, version);
                var ignored = observeOnly()) {
            f.input(FORWARD_SPRINT);
            for (int tick = 0; tick < 20; tick++) f.rideTick();
            assertTrue(f.player.isSprinting);
            assertFalse(f.player.sprintState.flag());
            assertFalse(f.player.sprintState.modifier());
            f.setPassengers(VEHICLE, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());

            var carry = (JavaPredictionCarry)
                    f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
            assertFalse(carry.states().isEmpty());
            Vec3 expected = new Vec3(0, -.08D * .98F, (double) .98F * .02F * .91F);
            for (var state : carry.states()) {
                assertEquals(expected.x, state.velocity().x, 1.0E-15);
                assertEquals(expected.y, state.velocity().y, 1.0E-15);
                assertEquals(expected.z, state.velocity().z, 1.0E-15);
            }
            var state = carry.states().getFirst();
            Vec3 firstSprint = expected.add(0, 0, (double) .98F * .025999999F);
            var result = f.tick(state.pistonStart().position().add(firstSprint), false);
            assertTrue(exact(result), describe(result));
            assertTrue(f.player.sprintState.flag());
            assertTrue(f.player.sprintState.modifier());
        }
    }

    static Stream<Arguments> modernReleases() {
        return JavaPistonReleaseCases.releases().filter(arguments ->
                ((ClientVersion) arguments.get()[1]).isNewerThanOrEquals(ClientVersion.V_1_21_5));
    }

    @Test
    void aBatClearsTheLocalFlagAndModifierOnItsFirstRideTickWithoutChangingTheAnnouncement() throws Exception {
        try (var f = ride(EntityTypeIds.BAT, true)) {
            // SetPassengers itself never calls LivingEntity#setSprinting.
            assertTrue(f.player.isClientSprinting());
            assertTrue(f.player.sprintState.flag());
            assertTrue(f.player.sprintState.modifier());
            f.rideTick();

            assertTrue(f.player.isSprinting, "noncontrolling passengers send no STOP_SPRINTING");
            assertFalse(f.player.isClientSprinting());
            assertFalse(f.player.sprintState.flag());
            assertFalse(f.player.sprintState.modifier());
        }
    }

    @Test
    void aMountedTickEndWithoutMovementAlsoAdvancesTheLocalSprintState() throws Exception {
        try (var f = ride(EntityTypeIds.BAT)) {
            f.player.isSprinting = true;
            f.player.sprintState.onClientTick(true);
            f.player.sprintState.onClientTickEnd();
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.tickEnd();

            assertTrue(f.player.isSprinting);
            assertFalse(f.player.sprintState.flag());
            assertFalse(f.player.sprintState.modifier());
        }
    }

    @Test
    void aPassengerOnlyRemovesTheModifierWhenVanillaActuallyStopsSprinting() throws Exception {
        try (var f = ride(EntityTypeIds.BAT)) {
            // Attribute replacement can add a modifier independently of the flag. With the flag already false,
            // aiStep does not call setSprinting(false), so the modifier survives the passenger tick.
            f.player.sprintState.onServerModifier(CompensatedEntities.SPRINTING_SPEED_MODIFIER);
            f.rideTick();
            assertFalse(f.player.sprintState.flag());
            assertTrue(f.player.sprintState.modifier());

            // A later self-metadata update sets only the flag; the next aiStep stops it and removes the modifier.
            f.player.sprintState.onServerFlag(true);
            f.rideTick();
            assertFalse(f.player.sprintState.flag());
            assertFalse(f.player.sprintState.modifier());
        }
    }

    @Test
    void aControlledCamelsRiderMaySilentlyDropAModifierWhileTheFlagStaysClear() throws Exception {
        try (var f = ride(EntityTypeIds.CAMEL)) {
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            // A movement_speed snapshot holds the modifier while the flag is clear.
            f.player.sprintState.onServerModifier(CompensatedEntities.SPRINTING_SPEED_MODIFIER);
            f.input(FORWARD_SPRINT);
            f.rideTick();
            // canStartSprinting -> setSprinting(true); without a START_SPRINTING a stop check removed it again, which
            // depends on the rider's own fluids at its seat.
            assertFalse(f.player.isSprinting);
            assertTrue(f.player.sprintState.modifier());
            assertTrue(f.player.sprintState.clearedPossible());
        }
    }

    @Test
    void anUncontrolledCamelOrNoForwardKeyCannotStartItsRider() throws Exception {
        try (var f = ride(EntityTypeIds.CAMEL)) {
            f.player.sprintState.onServerModifier(CompensatedEntities.SPRINTING_SPEED_MODIFIER);
            f.input(FORWARD_SPRINT);
            f.rideTick();
            assertFalse(f.player.sprintState.clearedPossible());
        }
        try (var f = ride(EntityTypeIds.CAMEL)) {
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            f.player.sprintState.onServerModifier(CompensatedEntities.SPRINTING_SPEED_MODIFIER);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, true));
            f.rideTick();
            assertFalse(f.player.sprintState.clearedPossible());
        }
    }

    @Test
    void oneTwentyOneFourRemovesANonCamelPassengersModifierWhateverTheFlag() throws Exception {
        try (var f = ride(EntityTypeIds.BAT, false, ClientVersion.V_1_21_4)) {
            f.player.sprintState.onServerModifier(CompensatedEntities.SPRINTING_SPEED_MODIFIER);
            f.rideTick();
            assertFalse(f.player.sprintState.flag());
            assertFalse(f.player.sprintState.modifier());
        }
    }

    @Test
    void neutralBatPassengerInventoryActionsDoNotUseTheStaleSprintAnnouncement() throws Exception {
        try (var f = ride(EntityTypeIds.BAT)) {
            f.player.isSprinting = true;
            f.player.sprintState.onClientTick(true);
            f.player.sprintState.onClientTickEnd();
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            for (int tick = 0; tick < 12; tick++) f.rideTick();
            inventoryActions(f, 0);
            assertTrue(f.player.isSprinting);
        }
    }

    @Test
    void aControllingCamelStillEnforcesSprintForInventoryActions() throws Exception {
        try (var f = ride(EntityTypeIds.CAMEL)) {
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            f.player.isSprinting = true;
            f.rideTick();
            assertTrue(f.player.sprintState.flag());
            assertTrue(f.player.sprintState.modifier());
            inventoryActions(f, 1);
        }
    }

    @SuppressWarnings("unchecked")
    private static void inventoryActions(JavaTickFixture f, int violations) {
        var clickCheck = f.player.checkManager.getCheck(MultiActionsC.class);
        var closeCheck = f.player.checkManager.getCheck(MultiActionsD.class);
        f.player.setExperimentalChecks(true);
        clickCheck.setEnabled(true);
        closeCheck.setEnabled(true);
        clickCheck.violations = closeCheck.violations = 0;
        assertFalse(MultiActionsC.isVerboseInput(f.player));
        var click = new InventoryClick(0, 0, 36, 0, WindowClickType.SWAP, Map.of(), SimItemStack.EMPTY);
        var type = (PacketType<InventoryClick>)
                f.fixture.registrar.scanner().runtime().typeForRecord(InventoryClick.class);
        clickCheck.onContainerClick(f.event(type, click), f.player, click);
        var close = new ServerboundContainerClose(0);
        closeCheck.onContainerClose(f.event(ServerboundPackets.CONTAINER_CLOSE, close), f.player, close);
        assertEquals(violations, clickCheck.violations);
        assertEquals(violations, closeCheck.violations);
    }

    @Test
    void aPassengerOfAnUnsaddledHorseThatMountedSprintingIsNotSprinting() throws Exception {
        try (var f = ride(EntityTypeIds.HORSE)) {
            SprintH check = enabled(f);
            f.player.isSprinting = true;
            f.rideTick();
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            for (int tick = 0; tick < 3; tick++) f.rideTick();

            assertFalse(f.player.isClientSprinting());
            assertFalse(MultiActionsC.isVerboseSprinting(f.player));
            assertEquals(0, check.violations);
        }
    }

    @Test
    void aPassengerOfAVehicleItCannotControlIsNotSprintingWhateverItsType() throws Exception {
        for (int type : new int[] {EntityTypeIds.DONKEY, EntityTypeIds.PIG, EntityTypeIds.CAMEL}) {
            try (var f = ride(type)) {
                f.player.isSprinting = true;
                f.rideTick();

                assertFalse(f.player.isClientSprinting());
            }
        }
    }

    @Test
    void aVehicleThatCannotSprintClearsTheFlagEvenWhenTheRiderControlsIt() throws Exception {
        try (var f = ride(EntityTypeIds.HORSE)) {
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            f.player.isSprinting = true;
            f.rideTick();

            assertFalse(f.player.isClientSprinting());
        }
    }

    @Test
    void aSaddledPigRiderHoldingTheStickHasAClearedFlagToo() throws Exception {
        try (var f = ride(EntityTypeIds.PIG)) {
            ((PacketEntityRideable) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            f.player
                    .getInventory()
                    .inventory
                    .setHeldItem(ItemUtil.modelItems().stack("minecraft:carrot_on_a_stick", 1));
            f.player.isSprinting = true;
            f.rideTick();

            assertFalse(f.player.isClientSprinting());
        }
    }

    /** A passenger of a camel nobody controls never reaches LocalPlayer#sendIsSprintingIfNeeded mounted. */
    @Test
    void aPassengerOfAnUnsaddledCamelThatMountedSprintingIsNotSprinting() throws Exception {
        try (var f = ride(EntityTypeIds.CAMEL)) {
            SprintH check = enabled(f);
            f.player.isSprinting = true;
            f.rideTick();
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            for (int tick = 0; tick < 3; tick++) f.rideTick();

            assertFalse(f.player.isClientSprinting());
            assertFalse(MultiActionsC.isVerboseSprinting(f.player));
            assertEquals(0, check.violations);
        }
    }

    /**
     * A tick controlling the camel ends in LocalPlayer#sendIsSprintingIfNeeded: a rider that no longer sprints sends
     * STOP_SPRINTING there.
     */
    @Test
    void gainingControlOfACamelSendsTheStopOfAClearedFlag() throws Exception {
        try (var f = ride(EntityTypeIds.CAMEL)) {
            f.player.isSprinting = true;
            f.rideTick();
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            command(f, PlayerCommandAction.STOP_SPRINTING);
            f.rideTick();

            assertFalse(f.player.isClientSprinting());
        }
    }

    /**
     * AbstractHorse#getControllingPassenger needs the saddle, which the rider's client gets from
     * ClientPacketListener#handleSetEquipment: until it reads the set_equipment, its ticks stay passenger ticks.
     */
    @Test
    void aSaddleEquippedOnTheCamelGivesControlOnlyOnceTheClientReadsIt() throws Exception {
        try (var f = ride(EntityTypeIds.CAMEL)) {
            f.player.isSprinting = true;
            f.rideTick();
            sendSaddle(f);
            f.rideTick();

            assertFalse(f.player.isClientSprinting());

            f.pongThrough(f.player.lastTransactionSent.get());
            f.rideTick();

            assertTrue(f.player.isClientSprinting());
        }
    }

    /**
     * LocalPlayer#aiStep sprints a controlling rider holding forward and sprint again, which matches the last command
     * sent, so LocalPlayer#sendIsSprintingIfNeeded sends nothing.
     */
    @Test
    void gainingControlOfACamelWithoutAStopSprintsAgain() throws Exception {
        try (var f = ride(EntityTypeIds.CAMEL)) {
            f.player.isSprinting = true;
            f.rideTick();
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            f.rideTick();

            assertTrue(f.player.isClientSprinting());
        }
    }

    @Test
    void theFlagStaysClearedAfterDismountingUntilTheClientSprintsAgain() throws Exception {
        try (var f = ride(EntityTypeIds.HORSE)) {
            f.player.isSprinting = true;
            f.rideTick();
            f.setPassengers(VEHICLE, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());

            assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
            assertFalse(f.player.isClientSprinting());

            startSprinting(f);

            assertTrue(f.player.isClientSprinting());
        }
    }

    /** LocalPlayer#sendPosition ends a tick on foot in LocalPlayer#sendIsSprintingIfNeeded. */
    @Test
    void aDismountedRiderThatSendsNoStopSprintsAgain() throws Exception {
        try (var f = ride(EntityTypeIds.HORSE)) {
            f.player.isSprinting = true;
            f.rideTick();
            f.setPassengers(VEHICLE, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();

            assertTrue(f.player.isClientSprinting());
        }
    }

    @Test
    void aPassengerOfACamelItControlsKeepsSprintingAndIsStillFlaggedInAnInventory() throws Exception {
        try (var f = ride(EntityTypeIds.CAMEL)) {
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            SprintH check = enabled(f);
            f.player.isSprinting = true;
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            f.rideTick();

            assertTrue(f.player.isClientSprinting());
            assertEquals(1, check.violations);
        }
    }

    @Test
    void aPlayerOnFootSprintingInAnInventoryIsStillFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            SprintH check = enabled(f);
            f.player.isSprinting = true;
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            f.tickEnd();

            assertTrue(f.player.isClientSprinting());
            assertEquals(1, check.violations);
        }
    }

    /** Prevent automatic server dismount corrections from replacing the vanilla passenger carry under test. */
    private static AutoCloseable observeOnly() throws ReflectiveOperationException {
        var manager = CultAPI.INSTANCE.getConfigManager();
        var field = BaseConfigManager.class.getDeclaredField("observeOnly");
        field.setAccessible(true);
        boolean previous = field.getBoolean(manager);
        field.setBoolean(manager, true);
        return () -> field.setBoolean(manager, previous);
    }

    private static void startSprinting(JavaTickFixture f) {
        command(f, PlayerCommandAction.START_SPRINTING);
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }

    /** A set_equipment putting a saddle in the vehicle's SADDLE slot. */
    @SuppressWarnings("unchecked")
    private static void sendSaddle(JavaTickFixture f) {
        var item = DataTables.defaults().items().stream()
                .filter(definition -> definition.key().equals("minecraft:saddle"))
                .findFirst()
                .orElseThrow();
        var packet = new InventoryPackets.Equipment(
                VEHICLE,
                List.of(new InventoryPackets.EquipmentEntry(
                        EquipmentSlot.SADDLE, new SimItemStack(item, 1, Components.EMPTY))));
        var type = (PacketType<InventoryPackets.Equipment>) TestProtocolRuntime.catalog().stream()
                .filter(candidate -> candidate.recordClass() == InventoryPackets.Equipment.class)
                .findFirst()
                .orElseThrow();
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        f.player.packetEntityReplication.onSetEquipment(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static JavaTickFixture ride(int type) throws Exception {
        return ride(type, false);
    }

    private static JavaTickFixture ride(int type, boolean sprinting) throws Exception {
        return ride(type, sprinting, ClientVersion.V_26_3);
    }

    private static JavaTickFixture ride(int type, boolean sprinting, ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.place(SEAT, Vec3.ZERO);
        f.player.isSprinting = sprinting;
        f.player.sprintState.onClientTick(sprinting);
        f.player.sprintState.onClientTickEnd();
        f.player.compensatedEntities.addEntity(VEHICLE, type, SEAT, 0, 0, 0);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        return f;
    }

    private static SprintH enabled(JavaTickFixture f) {
        f.player.setExperimentalChecks(true);
        SprintH check = f.player.checkManager.getCheck(SprintH.class);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        return check;
    }
}
