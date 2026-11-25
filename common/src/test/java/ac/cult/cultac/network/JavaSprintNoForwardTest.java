package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.sprint.SprintI;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHealth;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * LocalPlayer#aiStep stops a sprint without forward impulse after KeyboardInput#tick, and LocalPlayer#tick sends that
 * tick's PLAYER_INPUT before sendIsSprintingIfNeeded's command. Only a swimmer on the ground or holding shift keeps the
 * sprint (shouldStopSwimSprinting), and only a tick that reaches the send announces anything.
 */
class JavaSprintNoForwardTest {
    private static final String RELEASES = "ac.cult.cultac.network.JavaPistonReleaseCases#releases";
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
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void theServersSprintFlagOnAStandingClientAnnouncesNothing(String release, ClientVersion version) throws Exception {
        try (var f = standing(version)) {
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
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void theServersFlagsStartASwimSprintOnThePoolFloorWithoutAnyKey(String release, ClientVersion version)
            throws Exception {
        try (var f = standing(version)) {
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
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void theServersSwimFlagOnDryGroundCarriesNoSprint(String release, ClientVersion version) throws Exception {
        try (var f = standing(version)) {
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

    /** A wader's eye is above the water: Entity#updateSwimming starts no swim, so aiStep's run stop clears the flag. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void theServersSprintFlagAloneOnAWaderAnnouncesNothing(String release, ClientVersion version) throws Exception {
        try (var f = standing(version)) {
            pool(f, FLOOR_Y);
            SprintI check = enabled(f);
            f.tick(STAND, true);
            sharedFlags(f, SPRINTING);
            for (int tick = 0; tick < 3; tick++) f.tickEnd();
            assertEquals(0, check.violations, release);
        }
    }

    /**
     * Under water the server's sprint flag alone lets Entity#updateSwimming start a swim, and a swimmer on the floor
     * keeps the sprint without forward, so sendIsSprintingIfNeeded announces it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void theServersSprintFlagAloneStartsASwimSprintOnThePoolFloor(String release, ClientVersion version)
            throws Exception {
        try (var f = standing(version)) {
            pool(f, FLOOR_Y + 2);
            SprintI check = enabled(f);
            f.tick(STAND, true);
            sharedFlags(f, SPRINTING);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            assertEquals(0, check.violations, release);
        }
    }

    /**
     * A rider holding forward under water keeps its flag on 1.21.2 and 1.21.3, whose aiStep has no passenger stop.
     * Off the vehicle the kept flag starts a swim in Entity#updateSwimming and shift keeps it without forward; the
     * flag was never cleared, so nothing is announced. Later releases clear the rider's flag and announce the STOP.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aSprinterLeavingASubmergedMinecartHoldingShiftKeepsItsSwimSprint(String release, ClientVersion version)
            throws Exception {
        try (var f = standing(version)) {
            pool(f, FLOOR_Y + 4);
            SprintI check = enabled(f);
            f.input(FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            // A minecart sinking through the water: the rider's feet are in a water block, not the floor.
            f.player.compensatedEntities.addEntity(
                    VEHICLE, EntityTypes.defaults().byKey("minecraft:minecart").id(), STAND.add(0, 1, 0), 0, 0, 0);
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int tick = 0; tick < 5; tick++) f.rideTick();
            removeEntity(f, VEHICLE);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(SHIFT);
            boolean riderKeepsFlag = version.isOlderThan(ClientVersion.V_1_21_4);
            if (!riderKeepsFlag) command(f, PlayerCommandAction.STOP_SPRINTING);
            f.move(STAND, true);
            f.tickEnd();
            f.move(STAND, true);
            f.tickEnd();
            assertEquals(0, check.violations, release);
        }
    }

    /**
     * A kept-data respawn's LocalPlayer keeps the flag and wasSprinting, so it announces nothing; any other starts
     * from a clear flag and the keys still held start the sprint again.
     */
    @ParameterizedTest(name = "{0} keep {2}")
    @MethodSource("releasesAndKeptData")
    void aForwardSprintThroughARespawnDoesNotFlag(String release, ClientVersion version, byte keep) throws Exception {
        try (var f = standing(version)) {
            SprintI check = enabled(f);
            f.input(FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tick(STAND, true);
            f.fixture.respawn(keep);
            f.input(FORWARD_SPRINT);
            // A kept-data respawn's LocalPlayer starts with wasSprinting set to the flag it keeps.
            if ((keep & ClientboundRespawn.KEEP_ENTITY_DATA) == 0) command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            f.tickEnd();
            assertEquals(0, check.violations, release);
        }
    }

    /** A swimmer kept on the pool floor without forward, through a respawn that keeps or drops its flags. */
    @ParameterizedTest(name = "{0} keep {2}")
    @MethodSource("releasesAndKeptData")
    void aFloorSwimmerThroughARespawnDoesNotFlag(String release, ClientVersion version, byte keep) throws Exception {
        try (var f = standing(version)) {
            pool(f, FLOOR_Y + 2);
            SprintI check = enabled(f);
            f.tick(STAND, true);
            sharedFlags(f, (byte) (SPRINTING | SWIMMING));
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            f.fixture.respawn(keep);
            // The new level's chunks hold the same pool.
            JavaTickFixture.floor(f.player);
            pool(f, FLOOR_Y + 2);
            // A kept-data LocalPlayer keeps the flags and wasSprinting but starts off the ground, so its first aiStep
            // stops the swim sprint of a swimmer holding neither forward nor shift.
            if ((keep & ClientboundRespawn.KEEP_ENTITY_DATA) != 0) command(f, PlayerCommandAction.STOP_SPRINTING);
            f.tickEnd();
            f.tickEnd();
            assertEquals(0, check.violations, release);
        }
    }

    /** A passive rider's last START outlives a respawn whose new player has no vehicle and no keys. */
    @ParameterizedTest(name = "{0} keep {2}")
    @MethodSource("releasesAndKeptData")
    void aPassiveRiderThroughARespawnDoesNotFlag(String release, ClientVersion version, byte keep) throws Exception {
        try (var f = standing(version)) {
            SprintI check = enabled(f);
            f.input(FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.BAT, STAND, 0, 0, 0);
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            for (int tick = 0; tick < 3; tick++) f.rideTick();
            f.fixture.respawn(keep);
            f.move(STAND, true);
            f.tickEnd();
            f.move(STAND, true);
            f.tickEnd();
            assertEquals(0, check.violations, release);
        }
    }

    /** 1.21.2 has no client-loaded gate: the old LocalPlayer's ticks reach the proxy before the respawn's pong. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aTickEndBeforeTheRespawnIsAnsweredDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = standing(version)) {
            SprintI check = enabled(f);
            f.input(FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tick(STAND, true);
            var respawn = new ClientboundRespawn(
                    new PlayerSpawnInfo(
                            ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_end"),
                            f.player
                                    .compensatedWorld
                                    .getLastClientboundDimension()
                                    .dimension(),
                            GameMode.SURVIVAL),
                    (byte) 0);
            var event = new PacketSendEvent<>(
                    f.player.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, respawn, false);
            new PacketPlayerRespawn().onRespawn(event, f.player, respawn);
            event.tasksAfter().forEach(Runnable::run);
            f.tickEnd();
            // The loading screen releases the keys; the new player stops the sprint it no longer holds.
            f.input(NONE);
            command(f, PlayerCommandAction.STOP_SPRINTING);
            f.tickEnd();
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();
            assertEquals(0, check.violations, release);
        }
    }

    /** A passive rider released forward and was ejected; its first tick on foot announces the stop. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void anEjectedPassiveRiderStopsItsStaleSprint(String release, ClientVersion version) throws Exception {
        try (var f = standing(version)) {
            SprintI check = enabled(f);
            f.input(FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.BAT, STAND, 0, 0, 0);
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            for (int tick = 0; tick < 5; tick++) f.rideTick();
            f.setPassengers(VEHICLE, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());
            command(f, PlayerCommandAction.STOP_SPRINTING);
            f.move(STAND, true);
            f.tickEnd();
            f.move(STAND, true);
            f.tickEnd();
            assertEquals(0, check.violations, release);
        }
    }

    /**
     * Sprinting into a vehicle it controls with forward held: 1.21.2 and 1.21.3 keep the flag, later releases stop it
     * in the first ride tick's sendIsSprintingIfNeeded.
     */
    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("releasesAndControlledVehicles")
    void aSprinterBoardingAControlledVehicleWithForwardDoesNotFlag(String release, ClientVersion version, String type)
            throws Exception {
        try (var f = standing(version)) {
            SprintI check = enabled(f);
            f.input(FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tick(STAND, true);
            f.player.compensatedEntities.addEntity(
                    VEHICLE, EntityTypes.defaults().byKey(type).id(), STAND, 0, 0, 0);
            if (f.player.compensatedEntities.getEntity(VEHICLE) instanceof PacketEntityHorse horse)
                horse.hasSaddle = true;
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int tick = 0; tick < 4; tick++) {
                var rot = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, false, false, false, true);
                f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
                f.moveVehicle(STAND, f.player.yRot, f.player.xRot, true);
                if (tick == 0 && version.isNewerThanOrEquals(ClientVersion.V_1_21_4))
                    command(f, PlayerCommandAction.STOP_SPRINTING);
                f.tickEnd();
            }
            assertEquals(0, check.violations, release + " " + type);
        }
    }

    /** Flying and gliding keep no sprint without forward: shouldStopRunSprinting has no exception for them. */
    @ParameterizedTest(name = "{0} flying {2}")
    @MethodSource("releasesAndBooleans")
    void aSprintStartedFlyingOrGlidingWithoutForwardIsFlagged(String release, ClientVersion version, boolean flying)
            throws Exception {
        try (var f = standing(version)) {
            SprintI check = enabled(f);
            if (flying) {
                f.player.canFly = true;
                f.player.isFlying = true;
            } else {
                f.player.isGliding = true;
            }
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            assertEquals(1, check.violations, release);
        }
    }

    /** A controlled camel's rider announces its sprint after the MoveVehicle, from the same aiStep as its keys. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aControlledCamelsRiderStartingWithoutForwardIsFlagged(String release, ClientVersion version) throws Exception {
        try (var f = standing(version)) {
            SprintI check = enabled(f);
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.CAMEL, STAND, 0, 0, 0);
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            var rot = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, false, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            f.moveVehicle(STAND, f.player.yRot, f.player.xRot, true);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            assertEquals(1, check.violations, release);
        }
    }

    /**
     * The tick LocalPlayer#tickDeath removes the dead player skips aiStep, while 26.2 and older still run
     * sendIsSprintingIfNeeded: a server sprint flag is announced with no aiStep to stop it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void theRemovalTickOfADeadPlayerIsNotJudged(String release, ClientVersion version) throws Exception {
        try (var f = standing(version)) {
            f.player.nettyScheduler.removeScheduler();
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            SprintI check = enabled(f);
            f.tick(STAND, true);
            var health = new ClientboundSetHealth(0.0F, 20, 5.0F);
            var healthEvent = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_HEALTH, health, false);
            new PacketPlayerRespawn().onSetHealth(healthEvent, f.player, health);
            healthEvent.tasksAfter().forEach(Runnable::run);
            var dead = new EntityMetadata(
                    f.player.entityID,
                    List.of(EntityMetadata.Entry.health(
                            WatchableIndexUtil.LIVING_HEALTH, f.fixture.data.version(), 0.0F)));
            var deadEvent = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, metadataType(), dead, false);
            f.player.packetEntityReplication.onSetEntityData(deadEvent, f.player, dead);
            deadEvent.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int tick = 0; tick < 19; tick++) f.tickEnd();
            sharedFlags(f, SPRINTING);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            for (int tick = 0; tick < 5; tick++) f.tickEnd();
            assertEquals(0, check.violations, release);
        }
    }

    /**
     * A tick end the timer cancelled ends no tick, so its legal swim START and the next tick's STOP close together.
     * The swimmer sprinting on the floor then lifts off it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aCancelledTickEndBetweenASwimStartAndItsStopDoesNotFlag(String release, ClientVersion version)
            throws Exception {
        try (var f = standing(version)) {
            pool(f, FLOOR_Y + 3);
            SprintI check = enabled(f);
            f.tick(STAND, true);
            sharedFlags(f, (byte) (SPRINTING | SWIMMING));
            f.move(STAND, true);
            command(f, PlayerCommandAction.START_SPRINTING);
            var cancelled =
                    f.event(ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue());
            cancelled.setCancelled(true);
            f.listener.processClientTickEndReceive(cancelled, f.player);
            f.move(STAND.add(0, 0.1, 0), false);
            command(f, PlayerCommandAction.STOP_SPRINTING);
            f.tickEnd();
            f.tickEnd();
            assertEquals(0, check.violations, release);
        }
    }

    static Stream<Arguments> releasesAndKeptData() {
        return JavaPistonReleaseCases.releases()
                .flatMap(release -> Stream.of((byte) 0, ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS, (byte)
                                (ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS | ClientboundRespawn.KEEP_ENTITY_DATA))
                        .map(keep -> Arguments.of(release.get()[0], release.get()[1], keep)));
    }

    static Stream<Arguments> releasesAndBooleans() {
        return JavaPistonReleaseCases.releases()
                .flatMap(release ->
                        Stream.of(true, false).map(value -> Arguments.of(release.get()[0], release.get()[1], value)));
    }

    static Stream<Arguments> releasesAndControlledVehicles() {
        return JavaPistonReleaseCases.releases()
                .flatMap(release -> Stream.of("minecraft:oak_boat", "minecraft:horse")
                        .map(type -> Arguments.of(release.get()[0], release.get()[1], type)));
    }

    private static void removeEntity(JavaTickFixture f, int id) {
        var packet = new ClientboundRemoveEntities(List.of(id));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
        f.player.packetEntityReplication.onRemoveEntities(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
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
