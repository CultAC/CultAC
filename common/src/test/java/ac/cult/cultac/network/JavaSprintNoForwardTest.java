package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.sprint.SprintI;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LocalPlayer#aiStep stops a sprint without forward impulse after KeyboardInput#tick, and LocalPlayer#tick sends that
 * tick's PLAYER_INPUT before sendIsSprintingIfNeeded's command. Only a swimmer on the ground or holding shift keeps the
 * sprint (shouldStopSwimSprinting), and only a tick that reaches the send announces anything.
 */
class JavaSprintNoForwardTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final Vec3 STAND = new Vec3(0.5, FLOOR_Y, 0.5);
    // LivingEntity#travel's resting fall on the ground.
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final byte SPRINTING = 0x08;
    private static final byte SWIMMING = 0x10;
    private static final int VEHICLE = 100;

    @ParameterizedTest(name = "{0}: a sprint started with no key held")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aSprintStartedStandingStillIsFlaggedEveryTickItLasts(String release, ClientVersion version) throws Exception {
        try (var f = standing(version)) {
            SprintI check = enabled(f);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            assertEquals(1, check.violations, release);
            f.tickEnd();
            assertEquals(2, check.violations, release);

            command(f, PlayerCommandAction.STOP_SPRINTING);
            f.tickEnd();
            // A stopped tick without forward rewards the check.
            assertTrue(check.violations < 2, release + ": flagged after the sprint stopped");
        }
    }

    @ParameterizedTest(name = "{0}: a sprint that outlives its forward key")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aSprintKeptAfterReleasingForwardIsFlagged(String release, ClientVersion version) throws Exception {
        try (var f = standing(version)) {
            SprintI check = enabled(f);
            f.input(FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING);
            for (int tick = 0; tick < 3; tick++) f.tickEnd();
            assertEquals(0, check.violations, release + ": a forward sprint");

            f.input(NONE);
            f.tickEnd();
            assertEquals(1, check.violations, release);
        }
    }

    /** aiStep's stop and its STOP_SPRINTING share the tick whose PLAYER_INPUT released forward. */
    @Test
    void releasingForwardStopsTheSprintInTheSameTick() throws Exception {
        try (var f = standing(ClientVersion.V_26_3)) {
            SprintI check = enabled(f);
            f.input(FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            f.input(NONE);
            command(f, PlayerCommandAction.STOP_SPRINTING);
            for (int tick = 0; tick < 3; tick++) f.tickEnd();
            assertEquals(0, check.violations);
        }
    }

    /** KeyboardInput#tick's forward impulse is forward without backward, whatever the strafe keys. */
    @ParameterizedTest(name = "forward {0} backward {1} left {2} right {3}")
    @CsvSource({
        "false, true, false, false, true",
        "false, false, true, false, true",
        "false, false, false, true, true",
        "true, true, false, false, true",
        "true, true, true, false, true",
        "true, false, false, false, false",
        "true, false, true, false, false",
        "true, false, false, true, false"
    })
    void onlyForwardWithoutBackwardCarriesASprint(
            boolean forward, boolean backward, boolean left, boolean right, boolean flagged) throws Exception {
        try (var f = standing(ClientVersion.V_26_3)) {
            SprintI check = enabled(f);
            f.input(new ServerboundPlayerInput(forward, backward, left, right, false, false, true));
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            assertEquals(flagged ? 1 : 0, check.violations);
        }
    }

    /** One sendIsSprintingIfNeeded a tick: a STOP_SPRINTING after the START_SPRINTING does not make it vanilla. */
    @Test
    void aStartWithoutForwardIsFlaggedEvenWhenTheTickStopsItAgain() throws Exception {
        try (var f = standing(ClientVersion.V_26_3)) {
            SprintI check = enabled(f);
            command(f, PlayerCommandAction.START_SPRINTING);
            command(f, PlayerCommandAction.STOP_SPRINTING);
            f.tickEnd();
            assertEquals(1, check.violations);
            f.tickEnd();
            assertTrue(check.violations < 1, "flagged a tick after the sprint stopped");
        }
    }

    /**
     * ClientPacketListener#handleSetEntityData sets the flag between ticks and the next aiStep clears it again with
     * wasSprinting still false, so the client sends nothing and the last announcement stays the stopped one.
     */
    @Test
    void theServersSprintFlagOnAStandingClientAnnouncesNothing() throws Exception {
        try (var f = standing(ClientVersion.V_26_3)) {
            SprintI check = enabled(f);
            sharedFlags(f, SPRINTING);
            assertTrue(f.player.sprintState.flag());
            for (int tick = 0; tick < 3; tick++) f.tickEnd();
            assertFalse(f.player.isSprinting);
            assertEquals(0, check.violations);
        }
    }

    /**
     * The server's sprint and swim flags on a client standing in water: Entity#updateSwimming keeps the swim, and
     * shouldStopSwimSprinting keeps the sprint of a swimmer on the ground, so sendIsSprintingIfNeeded announces a
     * sprint no key started.
     */
    @Test
    void theServersFlagsStartASwimSprintOnThePoolFloorWithoutAnyKey() throws Exception {
        try (var f = standing(ClientVersion.V_26_3)) {
            pool(f, FLOOR_Y + 2);
            SprintI check = enabled(f);
            f.tick(STAND, true);
            sharedFlags(f, (byte) (SPRINTING | SWIMMING));
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            assertEquals(0, check.violations);
        }
    }

    /** Entity#updateSwimming ends a swim out of water in Entity#baseTick, before aiStep's stop reads it. */
    @Test
    void theServersSwimFlagOnDryGroundCarriesNoSprint() throws Exception {
        try (var f = standing(ClientVersion.V_26_3)) {
            SprintI check = enabled(f);
            f.tick(STAND, true);
            sharedFlags(f, (byte) (SPRINTING | SWIMMING));
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            assertEquals(1, check.violations);
        }
    }

    /** Off the floor shouldStopSwimSprinting keeps a sprint without forward only for the shift key. */
    @ParameterizedTest(name = "shift {0}")
    @CsvSource({"true", "false"})
    void aSwimmerOffTheFloorKeepsItsSprintOnlyHoldingShift(boolean shift) throws Exception {
        try (var f = standing(ClientVersion.V_26_3)) {
            pool(f, FLOOR_Y + 3);
            SprintI check = enabled(f);
            Vec3 afloat = new Vec3(0.5, FLOOR_Y + 1.2, 0.5);
            f.place(afloat, Vec3.ZERO);
            f.move(afloat, false);
            f.tickEnd();
            sharedFlags(f, (byte) (SPRINTING | SWIMMING));
            if (shift) f.input(SHIFT);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.move(afloat, false);
            f.tickEnd();
            assertEquals(shift ? 0 : 1, check.violations);
        }
    }

    /**
     * LocalPlayer#sendChanges sends a passenger's sprint only for a root it controls, so a passive rider keeps its last
     * START_SPRINTING while aiStep clears the flag, whatever its keys.
     */
    @Test
    void aPassivePassengerKeepsItsAnnouncementWithoutForward() throws Exception {
        try (var f = standing(ClientVersion.V_26_3)) {
            SprintI check = enabled(f);
            f.input(FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.BAT, STAND, 0, 0, 0);
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle());

            f.input(NONE);
            for (int tick = 0; tick < 5; tick++) f.rideTick();
            assertTrue(f.player.isSprinting);
            assertEquals(0, check.violations);
        }
    }

    /**
     * LocalPlayer#tick waits for ClientPacketListener#hasClientLoaded, so a respawned player's kept sprint and its
     * fresh keys are not one tick's until its first tick runs.
     */
    @Test
    void aClientThatHasNotLoadedIsNotJudged() throws Exception {
        try (var f = standing(ClientVersion.V_26_3)) {
            SprintI check = enabled(f);
            f.player.isSprinting = true;
            f.player.packetStateData.playerLoadedIntoLevel = false;
            for (int tick = 0; tick < 3; tick++) f.tickEnd();
            assertEquals(0, check.violations);

            f.player.packetStateData.playerLoadedIntoLevel = true;
            command(f, PlayerCommandAction.STOP_SPRINTING);
            f.tickEnd();
            assertEquals(0, check.violations);
        }
    }

    /** Clients before 1.21.2 never send the keys their aiStep read. */
    @Test
    void aClientThatReportsNoKeysIsNotJudged() throws Exception {
        try (var f = standing(ClientVersion.V_1_21)) {
            SprintI check = enabled(f);
            command(f, PlayerCommandAction.START_SPRINTING);
            for (int tick = 0; tick < 3; tick++) f.tickEnd();
            assertEquals(0, check.violations);
        }
    }

    private static JavaTickFixture standing(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.place(STAND, GROUNDED);
        return f;
    }

    private static void pool(JavaTickFixture f, int surfaceY) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y <= surfaceY; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
    }

    private static SprintI enabled(JavaTickFixture f) {
        f.player.setExperimentalChecks(true);
        SprintI check = f.player.checkManager.getCheck(SprintI.class);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        return check;
    }

    /** The command as the receive pipeline routes it: the checks' early handlers, then the sprint state. */
    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        f.player.checkManager.dispatchEarlyReceive(f.event(ServerboundPackets.PLAYER_COMMAND, packet));
        f.command(action);
    }

    /** Self entity data carrying the shared flags, applied by its trailing proof between client ticks. */
    private static void sharedFlags(JavaTickFixture f, byte flags) {
        var packet = new EntityMetadata(
                f.player.entityID,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_SHARED_FLAGS, flags, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(f.player.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
