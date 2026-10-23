package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoSneakSlow;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Strafe;
import ac.cult.cultac.events.packets.PacketChangeGameState;
import ac.cult.cultac.events.packets.PacketPlayerAbilities;
import ac.cult.cultac.events.packets.PacketServerRegistries;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundGameEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerAbilities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameEventType;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Flight the server grants through player_abilities. LocalPlayer#aiStep adds three times the abilities' flying speed
 * to the delta movement's Y while jump is held, and Player#travel carries 0.6 of the travelled Y without gravity.
 */
class CreativeFlightTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput JUMP_SHIFT =
            new ServerboundPlayerInput(false, false, false, false, true, true, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, false);
    private static final ServerboundPlayerInput FORWARD_SHIFT =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD_JUMP_SHIFT =
            new ServerboundPlayerInput(true, false, false, false, true, true, false);
    private static final float FLYING_SPEED = 0.05F;
    /** LivingEntity#getFrictionInfluencedSpeed on stone at 0.1 movement speed. */
    private static final float GROUND_SPEED = 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F));
    private static final double IMPULSE = FLYING_SPEED * 3.0F;
    private static final Vec3 HOVER = new Vec3(0.5, FLOOR_Y + 3, 0.5);
    private static final Vec3 GROUND = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final double JUMP_POWER = 0.42F;
    private static final Vec3 KNOCKBACK = new Vec3(0.21113349203442588, -0.0783739241897089, -0.3397424159189404);
    private static final Set<Relative> ALL_DELTAS = Set.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z);
    /** Clear floor away from the blocks a test places around {@link #GROUND}. */
    private static final Vec3 TAKEOFF = new Vec3(-2.5, FLOOR_Y, 0.5);
    /** A water source at the feet, the eyes above it. */
    private static final Vec3 POOL = new Vec3(4.5, FLOOR_Y, 0.5);
    private static final int HORSE = 100;
    /** LivingEntity#travel's gravity and drag on a fall of 0.2. */
    private static final double FALL = (-0.2 - 0.08) * 0.98F;
    /** The Y velocity a player standing on the floor starts each tick with. */
    private static final double STANDING_Y = (0 - 0.08) * 0.98F;
    /** LivingEntity#getFrictionInfluencedSpeed on stone at 0.1 movement speed, times the 0.98 forward input. */
    private static final double WALK_STEP = (double) 0.98F * (double) (0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F)));
    // The forward movement of a tick that starts in the air.
    private static final double AIR_STEP = (double) 0.98F * (double) 0.02F;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;

    @Test
    void aVelocityTakenInFlightWithJumpHeldRisesByTheFlightImpulse() throws Exception {
        try (var f = flying()) {
            f.input(JUMP);
            f.pongThrough(f.velocity(new Vec3(0, 2, 0)));

            double y = 2 + IMPULSE;
            Vec3 at = HOVER.add(0, y, 0);
            assertPredicted(f.tick(at, false));
            assertEquals(0.0, antiKb(f), "the client took the velocity");

            y = y * 0.6 + IMPULSE;
            at = at.add(0, y, 0);
            assertPredicted(f.tick(at, false));

            f.input(NONE);
            y *= 0.6;
            at = at.add(0, y, 0);
            assertPredicted(f.tick(at, false));
        }
    }

    @Test
    void aKnockbackTakenInFlightWithJumpHeldRisesByTheFlightImpulse() throws Exception {
        try (var f = flying()) {
            f.input(JUMP);
            f.pongThrough(f.velocity(KNOCKBACK));

            Vec3 at = HOVER.add(KNOCKBACK.x, KNOCKBACK.y + IMPULSE, KNOCKBACK.z);
            assertPredicted(f.tick(at, false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");

            // LivingEntity#travelInAir's 0.91 horizontally, Player#travel's 0.6 vertically.
            f.input(NONE);
            Vec3 next = new Vec3(KNOCKBACK.x * 0.91F, (KNOCKBACK.y + IMPULSE) * 0.6, KNOCKBACK.z * 0.91F);
            assertPredicted(f.tick(at.add(next), false));
        }
    }

    /** -0.1523530489 + 0.15F is under LivingEntity#aiStep's 0.003 cutoff, so the client holds its Y. */
    @Test
    void theImpulseAppliesBeforeTheMovementThreshold() throws Exception {
        Vec3 knockback = new Vec3(-0.2834035280473661, -0.1523530488921443, -0.3631813465177318);
        try (var f = flying()) {
            f.input(JUMP);
            f.pongThrough(f.velocity(knockback));

            assertPredicted(f.tick(HOVER.add(knockback.x, 0, knockback.z), false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    @Test
    void aVelocityAfterATeleportInFlightWithJumpHeldRisesByTheFlightImpulse() throws Exception {
        try (var f = flying()) {
            f.teleport(HOVER, new RelativeFlag(0));
            f.input(JUMP);
            Vec3 target = HOVER.add(1, 0, 0);
            f.playerPosition(target, Vec3.ZERO, ALL_DELTAS);
            f.pongThrough(f.velocity(new Vec3(0, KNOCKBACK.y, 0)));

            assertPredicted(f.tick(target.add(0, KNOCKBACK.y + IMPULSE, 0), false));
            assertEquals(0.0, antiKb(f), "the client took the velocity");
        }
    }

    @Test
    void aVelocityTakenInFlightWithShiftHeldSinksByTheFlightImpulse() throws Exception {
        try (var f = flying()) {
            f.input(SHIFT);
            f.pongThrough(f.velocity(new Vec3(0, 2, 0)));

            assertPredicted(f.tick(HOVER.add(0, 2 - IMPULSE, 0), false));
            assertEquals(0.0, antiKb(f), "the client took the velocity");
        }
    }

    /** The impulse follows the reported keys, so a velocity raised by it while neither key is held flags. */
    @Test
    void anImpulseWithoutItsKeyFlags() throws Exception {
        try (var f = flying()) {
            f.input(NONE);
            f.pongThrough(f.velocity(new Vec3(0, 2, 0)));

            f.tick(HOVER.add(0, 2 + IMPULSE, 0), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** A client that never sent player_input holds no keys: LocalPlayer#lastSentInput starts at Input.EMPTY. */
    @Test
    void anImpulseBeforeAnyPlayerInputFlags() throws Exception {
        try (var f = flying()) {
            f.pongThrough(f.velocity(KNOCKBACK));

            f.tick(HOVER.add(KNOCKBACK.x, KNOCKBACK.y + IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    @Test
    void sinkingByTheImpulseWhileJumpIsHeldFlags() throws Exception {
        try (var f = flying()) {
            f.input(JUMP);
            f.pongThrough(f.velocity(new Vec3(0, 2, 0)));

            f.tick(HOVER.add(0, 2 - IMPULSE, 0), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** The velocity is required from its proof in flight too, so a client that only rises by the impulse flags. */
    @Test
    void ignoringAVelocityInFlightFlags() throws Exception {
        try (var f = flying()) {
            f.input(JUMP);
            f.pongThrough(f.velocity(new Vec3(0, 2, 0)));

            f.tick(HOVER.add(0, IMPULSE, 0), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /**
     * The LocalPlayer a LOGIN after a reconfiguration creates holds no keys and sends player_input only once they
     * change, so jump held before the switch adds no impulse after it.
     */
    @Test
    void jumpHeldBeforeABackendSwitchAddsNoImpulseAfterIt() throws Exception {
        try (var f = flying()) {
            f.input(JUMP);
            assertPredicted(f.tick(HOVER.add(0, IMPULSE, 0), false));

            switchBackend(f);
            f.teleport(HOVER, new RelativeFlag(0));
            f.pongThrough(f.velocity(new Vec3(0, 2, 0)));

            assertPredicted(f.tick(HOVER.add(0, 2, 0), false));
            assertEquals(0.0, antiKb(f), "the client took the velocity");
        }
    }

    /**
     * LocalPlayer#aiStep runs jumpFromGround when a double-tap turns flight on while on the ground, before the
     * flight input, so a knockback taken on that tick rises by the jump and the impulse.
     */
    @Test
    void aKnockbackOnAGroundedFlightToggleTakesTheTakeoffJumpAndTheImpulse() throws Exception {
        try (var f = armedOnTheGround(true)) {
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            assertTrue(f.player.isFlying, "the toggle did not apply");
            f.input(JUMP);

            assertPredicted(f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** With shift held too the impulses cancel, and the takeoff jump alone lifts the knockback. */
    @Test
    void aKnockbackOnAGroundedFlightToggleWithShiftHeldTakesTheTakeoffJump() throws Exception {
        try (var f = armedOnTheGround(true)) {
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP_SHIFT);

            assertPredicted(f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER, KNOCKBACK.z), false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** Only the toggle sends a flight start on an armed grounded press, and it always runs jumpFromGround. */
    @Test
    void omittingTheTakeoffJumpOfAnArmedGroundedToggleFlags() throws Exception {
        try (var f = armedOnTheGround(true)) {
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP);

            f.tick(GROUND.add(KNOCKBACK.x, KNOCKBACK.y + IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** The toggle resets Player#jumpTriggerTime, so the next takeoff needs a new arming press. */
    @Test
    void aSecondTakeoffJumpWithoutANewArmingPressFlags() throws Exception {
        try (var f = armedOnTheGround(true)) {
            f.abilities(true);
            f.input(JUMP);
            assertPredicted(f.tick(GROUND.add(0, JUMP_POWER + IMPULSE, 0), false));

            grantFlight(f, false);
            f.place(GROUND, Vec3.ZERO);
            f.input(NONE);
            assertPredicted(f.tick(GROUND, true));
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP);

            f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /**
     * MultiPlayerGameMode#setLocalMode lets a creative player fly at the game event, so a press before the
     * player_abilities that follows it already arms the toggle.
     */
    @Test
    void aPressAfterACreativeGameModeArmsTheToggleBeforeItsAbilities() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(GROUND.add(0, 0.3, 0), new Vec3(0, -0.2, 0));
            gameMode(f, 1);
            f.input(JUMP);
            assertPredicted(f.tick(GROUND.add(0, 0.1, 0), false));
            f.input(NONE);
            assertPredicted(f.tick(GROUND, true));
            grantFlight(f, false);

            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP);
            assertPredicted(f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** ClientPacketListener#handleLogin runs MultiPlayerGameMode#setLocalMode, letting a creative player fly. */
    @Test
    void aPressAfterACreativeLoginArmsTheToggleBeforeItsAbilities() throws Exception {
        try (var f = new JavaTickFixture()) {
            login(f, GameMode.CREATIVE);
            relayTheFloor(f);
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.teleport(GROUND.add(0, 0.3, 0), new RelativeFlag(0));

            assertArmedTakeoffUnderAKnockback(f);
        }
    }

    /** ClientPacketListener#handleRespawn runs MultiPlayerGameMode#setLocalMode on its new LocalPlayer. */
    @Test
    void aPressAfterACreativeRespawnArmsTheToggleBeforeItsAbilities() throws Exception {
        try (var f = new JavaTickFixture()) {
            respawn(f, GameMode.CREATIVE);
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.teleport(GROUND.add(0, 0.3, 0), new RelativeFlag(0));

            assertArmedTakeoffUnderAKnockback(f);
        }
    }

    /** The first press only arms Player#jumpTriggerTime, so flight turned on with it has no takeoff jump. */
    @Test
    void aTakeoffJumpOnAnUnarmedFirstPressFlags() throws Exception {
        try (var f = flying(GROUND, false)) {
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP);

            f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** LocalPlayer#aiStep toggles only on the jump key going down, so a held jump has no takeoff jump. */
    @Test
    void aTakeoffJumpWithJumpHeldSinceTheArmingPressFlags() throws Exception {
        try (var f = armedOnTheGround(false)) {
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            assertTrue(f.player.isFlying, "the flight start did not apply");

            f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** Player#jumpTriggerTime runs out 7 ticks after the arming press. */
    @Test
    void aTakeoffJumpAfterTheArmingPressRanOutFlags() throws Exception {
        try (var f = armedOnTheGround(true)) {
            for (int tick = 0; tick < 5; tick++) {
                assertPredicted(f.tick(GROUND, true));
            }
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP);

            f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** LivingEntity#jumpFromGround does nothing without jump strength, so the toggle keeps the knockback. */
    @Test
    void aGroundedFlightToggleWithoutJumpStrengthKeepsTheKnockback() throws Exception {
        try (var f = armedOnTheGround(true)) {
            f.player.compensatedEntities.getSelf().jumpStrength = 0;
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP);

            assertPredicted(f.tick(GROUND.add(KNOCKBACK.x, KNOCKBACK.y + IMPULSE, KNOCKBACK.z), false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** Player#isAffectedByFluids is false in flight, so a player already flying on the ground has no jump. */
    @Test
    void aPlayerAlreadyFlyingOnTheGroundCannotJumpUnderAKnockback() throws Exception {
        try (var f = flying(GROUND, true)) {
            f.input(JUMP_SHIFT);
            f.pongThrough(f.velocity(KNOCKBACK));

            f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** A survival toggle needs the jump press; flight turned on without it takes no takeoff jump. */
    @Test
    void aFlightToggleWithoutAJumpPressTakesNoTakeoffJump() throws Exception {
        try (var f = flying(GROUND, false)) {
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(SHIFT);

            f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER - IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** A new LocalPlayer's Abilities fly at 0.05 until the server's player_abilities says otherwise. */
    @Test
    void aCreativeRespawnFliesAtTheDefaultSpeedBeforeItsAbilities() throws Exception {
        try (var f = new JavaTickFixture()) {
            grantFlight(f, false, 0.1F);
            respawn(f, GameMode.CREATIVE);
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.teleport(GROUND.add(0, 0.3, 0), new RelativeFlag(0));

            assertArmedTakeoffUnderAKnockback(f, false);
        }
    }

    /** ClientPacketListener#handleLogin builds a new LocalPlayer after a reconfiguration, at the default fly speed. */
    @Test
    void aCreativeLoginFliesAtTheDefaultSpeedBeforeItsAbilities() throws Exception {
        try (var f = new JavaTickFixture()) {
            grantFlight(f, false, 0.1F);
            login(f, GameMode.CREATIVE);
            relayTheFloor(f);
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.teleport(GROUND.add(0, 0.3, 0), new RelativeFlag(0));

            assertArmedTakeoffUnderAKnockback(f, false);
        }
    }

    /**
     * ClientPacketListener#handleLogin gives a LocalPlayer it keeps a new KeyboardInput, so a jump held across the
     * LOGIN goes down again on the next aiStep and toggles an armed Player#jumpTriggerTime.
     */
    @Test
    void aJumpHeldAcrossALoginThatKeepsThePlayerTogglesTheArmedTimer() throws Exception {
        try (var f = new JavaTickFixture()) {
            login(f, GameMode.CREATIVE);
            relayTheFloor(f);
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.teleport(GROUND.add(0, 0.3, 0), new RelativeFlag(0));
            f.place(GROUND.add(0, 0.3, 0), new Vec3(0, -0.2, 0));
            f.input(JUMP);
            assertPredicted(f.tick(GROUND.add(0, 0.1, 0), false));
            assertPredicted(f.tick(GROUND, true));

            f.pongThrough(ItemUseTestSupport.login(f.player, GameMode.CREATIVE));
            assertTrue((f.player.packetStateData.jumpTriggerTimes & 1) == 0, "the LOGIN replaced the player");
            relayTheFloor(f);
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.teleport(GROUND, new RelativeFlag(0));

            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            assertPredicted(f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** A step up ahead lets LocalPlayer#updateAutoJump fire, so the press may not reach the toggle. */
    @Test
    void aPressWhereAnAutoJumpCanFireMayLeaveTheToggleUnarmed() throws Exception {
        try (var f = new JavaTickFixture()) {
            block(f, 0, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            assertTrue(unarmedAfterAWalkingPress(f, JUMP_POWER), "the auto-jump history was dropped");

            PredictionResult takeoff = pressInTheAirThenTakeOff(f);
            assertPredicted(takeoff);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** LocalPlayer#updateAutoJump needs an obstacle ahead, so a press on flat ground arms the toggle. */
    @Test
    void aTakeoffRenewedByAnAutoJumpOnFlatGroundFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertFalse(unarmedAfterAWalkingPress(f, JUMP_POWER), "an auto-jump on flat ground was kept");

            pressInTheAirThenTakeOff(f);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** The simulation's exemption after flight leaves updateAutoJump's geometry as it is. */
    @Test
    void aTakeoffRenewedByAnAutoJumpOnFlatGroundAfterFlightFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertFalse(unarmedAfterAWalkingPressAfterFlight(f, JUMP_POWER), "an auto-jump on flat ground was kept");

            pressInTheAirThenTakeOff(f);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** A step up with headroom ahead lets updateAutoJump fire on the exempt ticks after flight. */
    @Test
    void aPressWhereAnAutoJumpCanFireAfterFlightMayLeaveTheToggleUnarmed() throws Exception {
        try (var f = new JavaTickFixture()) {
            block(f, 0, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            assertTrue(unarmedAfterAWalkingPressAfterFlight(f, JUMP_POWER), "the auto-jump history was dropped");

            PredictionResult takeoff = pressInTheAirThenTakeOff(f);
            assertPredicted(takeoff);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** Entity#move collides Y before the horizontal axes, so a walk off a ledge's edge ends on the ground. */
    @Test
    void aWalkOffALedgeWhereAnAutoJumpCanFireAfterFlightMayLeaveTheToggleUnarmed() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int z = 0; z <= 8; z++) block(f, 0, FLOOR_Y - 1, z, BlockIds.AIR.defaultState());
            block(f, 0, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            assertTrue(
                    unarmedAfterAWalkAfterFlight(
                            f,
                            new Vec3(0.5, FLOOR_Y, 0.299),
                            true,
                            null,
                            new Vec3(0, 0, WALK_STEP),
                            new Vec3(0, JUMP_POWER, WALK_STEP * GROUND_FRICTION)),
                    "the walk off the ledge was not grounded");

            PredictionResult takeoff = pressInTheAirThenTakeOff(f);
            assertPredicted(takeoff);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** A walk that starts past a ledge's edge has no downward collision to end on the ground. */
    @Test
    void aTakeoffRenewedByAnAutoJumpPastALedgeAfterFlightFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int z = 0; z <= 8; z++) block(f, 0, FLOOR_Y - 1, z, BlockIds.AIR.defaultState());
            block(f, 0, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            assertFalse(
                    unarmedAfterAWalkAfterFlight(
                            f,
                            new Vec3(0.5, FLOOR_Y, 0.301),
                            false,
                            null,
                            new Vec3(0, STANDING_Y, AIR_STEP),
                            new Vec3(0, (STANDING_Y - 0.08) * 0.98F, AIR_STEP * 0.91F)),
                    "an auto-jump past the ledge was kept");

            pressInTheAirThenTakeOff(f);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** Entity#collide's step up onto a half slab ends on the ground, with a step up ahead for updateAutoJump. */
    @Test
    void aStepUpWhereAnAutoJumpCanFireAfterFlightMayLeaveTheToggleUnarmed() throws Exception {
        try (var f = new JavaTickFixture()) {
            halfSlabStepAhead(f);
            double z = 0.48 + WALK_STEP;
            assertTrue(
                    unarmedAfterAWalkAfterFlight(
                            f,
                            GROUND,
                            true,
                            new Vec3(0, -0.1, 0.48),
                            new Vec3(0, 0.5, z),
                            new Vec3(0, JUMP_POWER, z * GROUND_FRICTION)),
                    "the step up was not grounded");

            PredictionResult takeoff = pressInTheAirThenTakeOff(f);
            assertPredicted(takeoff);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /**
     * Entity#collide resolves the whole descent before it steps, so a fall a block down onto the floor that steps up
     * onto a half slab ends on the ground.
     */
    @Test
    void aFallThatStepsUpWhereAnAutoJumpCanFireAfterFlightMayLeaveTheToggleUnarmed() throws Exception {
        try (var f = new JavaTickFixture()) {
            halfSlabStepAhead(f);
            double z = 0.48 + AIR_STEP;
            assertTrue(
                    unarmedAfterAWalkAfterFlight(
                            f,
                            GROUND.add(0, 1, 0),
                            false,
                            new Vec3(0, -1.2, 0.48),
                            new Vec3(0, -0.5, z),
                            new Vec3(0, JUMP_POWER, z * 0.91F)),
                    "the fall that steps up was not grounded");

            PredictionResult takeoff = pressInTheAirThenTakeOff(f);
            assertPredicted(takeoff);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /**
     * The position on the half slab's top, with the slab above the full block ahead, lets updateAutoJump fire, and the
     * engine judges no move after flight that rules out a step there, so a step through the corner keeps the history.
     */
    @Test
    void aStepThroughACornerAfterFlightMayLeaveTheToggleUnarmed() throws Exception {
        try (var f = new JavaTickFixture()) {
            int slab = DataTables.defaults().registry().block("minecraft:stone_slab").defaultState();
            block(f, 1, FLOOR_Y, 0, slab);
            block(f, 0, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            block(f, 1, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            block(f, 1, FLOOR_Y + 1, 1, slab);
            assertTrue(
                    unarmedAfterAWalkAfterFlight(
                            f,
                            GROUND,
                            true,
                            null,
                            new Vec3(0.25, 0.5, 0.25),
                            new Vec3(0, JUMP_POWER, 0.25 * GROUND_FRICTION)),
                    "the auto-jump history was dropped");

            PredictionResult takeoff = pressInTheAirThenTakeOff(f);
            assertPredicted(takeoff);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /**
     * Entity#move keeps the position of a movement within 1.0E-7 squared that collided, and still lands it, so a
     * player resting within that distance above the floor sends only StatusOnly.
     */
    @Test
    void aClippedLandingJustAboveTheFloorAfterFlightMayLeaveTheToggleUnarmed() throws Exception {
        try (var f = new JavaTickFixture()) {
            block(f, 0, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            assertTrue(unarmedAfterAClippedLandingAfterFlight(f, 0.0003, false), "the clipped landing was dropped");

            PredictionResult takeoff = pressInTheAirThenTakeOff(f);
            assertPredicted(takeoff);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** LocalPlayer#sendPosition's 20th-tick position repeats a landing that Entity#move kept in place. */
    @Test
    void aClippedLandingJustAboveTheFloorSentAsAPositionAfterFlightMayLeaveTheToggleUnarmed() throws Exception {
        try (var f = new JavaTickFixture()) {
            block(f, 0, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            assertTrue(unarmedAfterAClippedLandingAfterFlight(f, 0.0003, true), "the clipped landing was dropped");

            PredictionResult takeoff = pressInTheAirThenTakeOff(f);
            assertPredicted(takeoff);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /**
     * A collided movement beyond 1.0E-7 squared moves the player, and a withheld position lies within the threshold of
     * the last one sent, so a StatusOnly landing beyond both above the floor flags.
     */
    @Test
    void aTakeoffRenewedByAnAutoJumpOverAGapAboveTheFloorAfterFlightFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            block(f, 0, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            assertFalse(unarmedAfterAClippedLandingAfterFlight(f, 0.001, false), "a landing above the gap was kept");

            pressInTheAirThenTakeOff(f);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /**
     * A withheld position lies within the threshold of the last one sent, so a StatusOnly landing 0.0004 above the
     * floor can be a client within sqrt(1.0E-7) of it, which Entity#move kept in place and grounded.
     */
    @Test
    void aClippedLandingWithinTheThresholdOfAGapAfterFlightMayLeaveTheToggleUnarmed() throws Exception {
        try (var f = new JavaTickFixture()) {
            block(f, 0, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            assertTrue(unarmedAfterAClippedLandingAfterFlight(f, 0.0004, false), "the clipped landing was dropped");

            PredictionResult takeoff = pressInTheAirThenTakeOff(f);
            assertPredicted(takeoff);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** LocalPlayer#updateAutoJump needs a rise above 0.5, so a half slab ahead doesn't fire it. */
    @Test
    void aTakeoffRenewedByAnAutoJumpOntoAHalfSlabFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            block(f, 0, FLOOR_Y, 1, DataTables.defaults().registry().block("minecraft:stone_slab").defaultState());
            assertFalse(unarmedAfterAWalkingPress(f, JUMP_POWER), "an auto-jump onto a half slab was kept");

            pressInTheAirThenTakeOff(f);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** LocalPlayer#updateAutoJump needs headroom above the player, so a ceiling stops it. */
    @Test
    void aTakeoffRenewedByAnAutoJumpUnderACeilingFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            block(f, 0, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            block(f, 0, FLOOR_Y + 2, 0, BlockIds.STONE.defaultState());
            assertFalse(unarmedAfterAWalkingPress(f, 2 - (double) 1.8F), "an auto-jump under a ceiling was kept");

            pressInTheAirThenTakeOff(f);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** Entity#collide's step up onto a half slab ends on the ground, so a judged step can fire updateAutoJump. */
    @Test
    void aJudgedStepUpWhereAnAutoJumpCanFireMayLeaveTheToggleUnarmed() throws Exception {
        try (var f = new JavaTickFixture()) {
            halfSlabStepAhead(f);
            f.place(GROUND, new Vec3(0, STANDING_Y, 0));
            grantFlight(f, false);
            f.input(FORWARD);
            f.pongThrough(f.velocity(new Vec3(0, -0.1, 0.48)));
            double z = 0.48 + WALK_STEP;
            Vec3 step = GROUND.add(0, 0.5, z);
            PredictionResult walk = f.tick(step, true);
            assertPredicted(walk);
            assertFalse(walk.isExempt(), "the step was exempt");
            f.input(JUMP);
            f.tick(step.add(0, JUMP_POWER, z * GROUND_FRICTION), false);
            assertTrue((f.player.packetStateData.jumpTriggerTimes & 1) != 0, "the auto-jump history was dropped");

            PredictionResult takeoff = pressInTheAirThenTakeOff(f);
            assertPredicted(takeoff);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** A flag on the exempt walk after flight proves nothing about updateAutoJump, so the step up ahead keeps it. */
    @Test
    void aFlaggedWalkWhereAnAutoJumpCanFireAfterFlightMayLeaveTheToggleUnarmed() throws Exception {
        try (var f = new JavaTickFixture()) {
            block(f, 0, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            f.place(GROUND.add(0, IMPULSE, 0), Vec3.ZERO);
            grantFlight(f, true);
            f.input(SHIFT);
            assertTrue(f.tick(GROUND, true).isExempt(), "the flight was judged");
            grantFlight(f, false);
            f.input(NONE);
            f.tickEnd();
            f.input(FORWARD);
            Vec3 walk = GROUND.add(0, 0, 2 * WALK_STEP);
            PredictionResult moved = f.tick(walk, true);
            assertTrue(moved.isExempt() && !moved.getFlags().isEmpty(), "the walk was not a flagged exemption");
            f.input(JUMP);
            f.tick(walk.add(0, JUMP_POWER, 2 * WALK_STEP * GROUND_FRICTION), false);
            assertTrue((f.player.packetStateData.jumpTriggerTimes & 1) != 0, "the auto-jump history was dropped");

            pressInTheAirThenTakeOff(f);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** A flight landing in shallow water can fire updateAutoJump at the step up ahead. */
    @Test
    void aFlightLandingInShallowWaterWhereAnAutoJumpCanFireMayLeaveTheToggleUnarmed() throws Exception {
        try (var f = new JavaTickFixture()) {
            int water = DataTables.defaults().registry().with(BlockIds.WATER.defaultState(), "level", "7");
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) block(f, x, FLOOR_Y, z, water);
            }
            block(f, 0, FLOOR_Y, 1, BlockIds.STONE.defaultState());
            f.place(GROUND.add(0, 0.05, 0), new Vec3(0, -0.2, 0));
            grantFlight(f, true);
            f.input(FORWARD);
            f.abilities(false);
            double fly = (double) 0.98F * (double) FLYING_SPEED;
            Vec3 landing = GROUND.add(0, 0, fly);
            f.tick(landing, true);
            f.input(JUMP);
            f.tick(landing.add(0, JUMP_POWER, fly * 0.91F), false);
            assertTrue((f.player.packetStateData.jumpTriggerTimes & 1) != 0, "the auto-jump history was dropped");

            pressInTheAirThenTakeOff(f);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** LocalPlayer#aiStep runs the double-tap toggle before travel, so a toggle can stop flight and land under water. */
    @Test
    void aFlightToggleThatLandsUnderWaterTakesTheKnockback() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 at = flyingDownAFlooredPool(f, true);
            f.pongThrough(f.velocity(new Vec3(0.2, -0.5, 0.2)));
            f.abilities(false);
            f.input(FORWARD_JUMP);
            double swim = (double) 0.98F * (double) (0.02F + (0.1F - 0.02F) * 0.5F);
            PredictionResult landed = f.tick(new Vec3(at.x + 0.2, FLOOR_Y, at.z + 0.2 + swim), true);
            assertEquals(0.0, antiKb(f), "the client took the knockback: " + describe(landed));
        }
    }

    /** A flight stop on an unarmed press is LocalPlayer#aiStep's landing after a travel in flight under water. */
    @Test
    void aFlightLandingUnderWaterTakesTheKnockback() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 at = flyingDownAFlooredPool(f, false);
            f.pongThrough(f.velocity(new Vec3(0.2, -0.5, 0.2)));
            f.abilities(false);
            f.input(FORWARD_JUMP);
            double fly = (double) 0.98F * (double) FLYING_SPEED;
            PredictionResult landed = f.tick(new Vec3(at.x + 0.2, FLOOR_Y, at.z + 0.2 + fly), true);
            assertEquals(0.0, antiKb(f), "the client took the knockback: " + describe(landed));
        }
    }

    /**
     * A double-tap stop that lands under water carries LivingEntity#travelInWater's drag, at a water movement
     * efficiency of 1 lerped halfway from 0.8 toward 0.546 off the ground, into the next move's explosion.
     */
    @Test
    void aFlightToggleThatLandsUnderWaterCarriesItsDragIntoAnExplosion() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 at = flyingDownAFlooredPool(f, true);
            f.pongThrough(f.velocity(new Vec3(0.8, -0.5, 0.8)));
            f.abilities(false);
            f.input(JUMP);
            at = new Vec3(at.x + 0.8, FLOOR_Y, at.z + 0.8);
            PredictionResult landed = f.tick(at, true);
            assertEquals(0.0, antiKb(f), "the client took the velocity: " + describe(landed));
            double drag = (double) (0.8F + (0.54600006F - 0.8F) * 0.5F);
            f.input(NONE);
            f.explosion(new Vec3(0.1, 0, 0.1));
            double carry = 0.8 * drag + 0.1;
            PredictionResult moved = f.tick(at.add(carry, 0, carry), true);
            double violations = f.player.checkManager.getExplosionHandler().violations;
            assertEquals(0.0, violations, "the client took the explosion: " + describe(moved));
        }
    }

    /** Entity#updateSwimming needs the sprint flag, so a wader that never sprinted toggles on an armed press. */
    @Test
    void aTakeoffKeptByAWadingPressFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            block(f, 4, FLOOR_Y, 0, BlockIds.WATER.defaultState());
            armInTheAir(f);
            f.place(POOL, Vec3.ZERO);
            f.input(JUMP);
            // LivingEntity#jumpInLiquid.
            assertPredicted(f.tick(POOL.add(0, 0.04F, 0), false));
            assertTrue((f.player.packetStateData.jumpTriggerTimes & ~1) == 0, "a blocked toggle was kept");

            landThenTakeOff(f);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** LocalPlayer#aiStep toggles on a saddled horse the player controls, which jumpableVehicle() returns. */
    @Test
    void aTakeoffKeptByAPressOnAJumpableHorseFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            armInTheAir(f);
            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, TAKEOFF, 0, 0, 0);
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE)).hasSaddle = true;
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(JUMP);
            f.rideTick();
            assertTrue((f.player.packetStateData.jumpTriggerTimes & ~1) == 0, "a blocked toggle was kept");

            f.input(NONE);
            dismount(f);
            landThenTakeOff(f);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /**
     * Player#isAffectedByFluids is false in flight, so LivingEntity#travel takes travelInAir in water: the flyer moves
     * at Player#getFlyingSpeed and carries the 0.91 air drag, not the water drag, into the next move's explosion. The
     * knockback runs along the input, so the drag's difference cannot hide in the input.
     */
    @Test
    void aFlyerInWaterTakesTheKnockbackAtTheFlyingSpeed() throws Exception {
        try (var f = flying()) {
            floodTheHover(f);
            f.input(FORWARD);
            Vec3 knockback = new Vec3(0, KNOCKBACK.y, 0.45);
            f.pongThrough(f.velocity(knockback));
            Vec3 input = ClientMoveInput.of(f, FORWARD, 1.0F, FLYING_SPEED, f.player.xRot);
            Vec3 taken = knockback.add(input);
            Vec3 at = HOVER.add(taken);
            PredictionResult result = f.tick(at, false);
            assertFalse(result.hasFlag(Strafe.class), describe(result));
            assertPredicted(result);
            assertEquals(0.0, antiKb(f), "the client took the knockback");

            Vec3 explosion = new Vec3(0, 0, 0.1);
            f.explosion(explosion);
            Vec3 cruise = new Vec3(
                    taken.x * 0.91F + explosion.x + input.x, taken.y * 0.6, taken.z * 0.91F + explosion.z + input.z);
            PredictionResult cruising = f.tick(at.add(cruise), false);
            assertFalse(cruising.hasFlag(Strafe.class), describe(cruising));
            assertPredicted(cruising);
            double violations = f.player.checkManager.getExplosionHandler().violations;
            assertEquals(0.0, violations, "the client took the explosion: " + describe(cruising));
        }
    }

    /** A flyer in water still cannot move past the flying speed under a knockback. */
    @Test
    void aFlyerInWaterOutrunningTheFlyingSpeedStillFlags() throws Exception {
        try (var f = flying()) {
            floodTheHover(f);
            f.input(FORWARD);
            f.pongThrough(f.velocity(KNOCKBACK));
            Vec3 input = ClientMoveInput.of(f, FORWARD, 1.0F, FLYING_SPEED * 2, f.player.xRot);
            f.tick(HOVER.add(KNOCKBACK).add(input), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** LocalPlayer#aiStep never crouches a flyer, so shift held in flight leaves the horizontal input unscaled. */
    @Test
    void shiftHeldInFlightKeepsTheFullHorizontalInput() throws Exception {
        try (var f = flying()) {
            f.input(FORWARD_SHIFT);
            Vec3 input = ClientMoveInput.of(f, FORWARD_SHIFT, 1.0F, FLYING_SPEED, f.player.xRot);
            Vec3 at = HOVER.add(input.x, -IMPULSE, input.z);
            assertPredicted(f.tick(at, false));
            f.pongThrough(f.velocity(KNOCKBACK));
            PredictionResult result = f.tick(at.add(KNOCKBACK).add(input.x, -IMPULSE, input.z), false);
            assertFalse(result.hasFlag(NoSneakSlow.class), describe(result));
            assertPredicted(result);
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /**
     * LocalPlayer#aiStep sets crouching before its double-tap toggle starts flight, so a grounded takeoff with shift
     * held since the last tick moves at the sneaking input.
     */
    @Test
    void aGroundedFlightToggleWithShiftHeldMovesAtTheSneakingInput() throws Exception {
        try (var f = armedOnTheGround(true)) {
            takeOffSneaking(f);
            Vec3 sneaking = ClientMoveInput.of(f, FORWARD_JUMP_SHIFT, 0.3F, GROUND_SPEED, f.player.xRot);
            PredictionResult result = f.tick(GROUND.add(sneaking.x, JUMP_POWER, sneaking.z), false);
            assertFalse(result.hasFlag(NoSneakSlow.class), describe(result));
            assertPredicted(result);
        }
    }

    /** The takeoff tick's crouching still bounds the input, so a takeoff at the walking input with shift held flags. */
    @Test
    void aGroundedFlightToggleWithShiftHeldAtTheWalkingInputFlags() throws Exception {
        try (var f = armedOnTheGround(true)) {
            takeOffSneaking(f);
            Vec3 input = ClientMoveInput.of(f, FORWARD_JUMP_SHIFT, 1.0F, GROUND_SPEED, f.player.xRot);
            PredictionResult result = f.tick(GROUND.add(input.x, JUMP_POWER, input.z), false);
            assertTrue(result.hasFlag(NoSneakSlow.class), describe(result));
        }
    }

    /**
     * LocalPlayer#aiStep reads Abilities#flying for crouching before an airborne double-tap stops flight, so the
     * stopping tick's input is unscaled although shift is held.
     */
    @Test
    void anAirborneFlightStopWithShiftHeldKeepsTheFullHorizontalInput() throws Exception {
        try (var f = flying()) {
            f.input(JUMP_SHIFT);
            assertPredicted(f.tick(HOVER, false));
            f.input(SHIFT);
            Vec3 at = HOVER.add(0, -IMPULSE, 0);
            assertPredicted(f.tick(at, false));

            f.abilities(false);
            f.input(FORWARD_JUMP_SHIFT);
            Vec3 input = ClientMoveInput.of(f, FORWARD_JUMP_SHIFT, 1.0F, 0.02F, f.player.xRot);
            PredictionResult result = f.tick(at.add(input.x, -IMPULSE * 0.6, input.z), false);
            assertFalse(f.player.isFlying, "the toggle did not stop flight");
            assertFalse(result.hasFlag(NoSneakSlow.class), describe(result));
            assertPredicted(result);
        }
    }

    /**
     * A join writes LOGIN and player_abilities before the platform player exists to send a ping.
     * ClientPacketListener#handlePlayerAbilities sets flying after handleLogin's MultiPlayerGameMode#setLocalMode.
     */
    @Test
    void aJoinFliesFromTheAbilitiesAfterItsLogin() throws Exception {
        try (var f = new JavaTickFixture()) {
            PlatformPlayer platform = f.fixture.user.getPlayer();
            JavaTickFixture.setField(User.class, f.fixture.user, "player", null);
            ItemUseTestSupport.login(f.player, GameMode.CREATIVE);
            var packet = new ClientboundPlayerAbilities(true, true, true, true, FLYING_SPEED, 0.1F);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_ABILITIES, packet, false);
            f.player.checkManager.getListener(PacketPlayerAbilities.class).onPlayerAbilities(event, f.player, packet);
            event.getTasksAfterSend().forEach(Runnable::run);
            f.fixture.user.bind(platform);
            f.player.sendTransaction();
            f.pongThrough(f.player.lastTransactionSent.get());
            relayTheFloor(f);
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.teleport(HOVER, new RelativeFlag(0));

            assertTrue(f.player.isFlying, "the abilities did not set flying");
            assertPredicted(f.tick(HOVER, false));
        }
    }

    /** A player hovering at {@link #HOVER} that the server lets fly and sets flying. */
    private static JavaTickFixture flying() throws Exception {
        return flying(HOVER, true);
    }

    /** A player at {@code position} that the server lets fly, set flying with {@code flying}. */
    private static JavaTickFixture flying(Vec3 position, boolean flying) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(position, Vec3.ZERO);
        grantFlight(f, flying);
        return f;
    }

    /**
     * A player the server lets fly that presses jump in the air on the tick before it lands, arming
     * Player#jumpTriggerTime without a jump, and lands releasing it or holding it.
     */
    private static JavaTickFixture armedOnTheGround(boolean releaseOnLanding) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(GROUND.add(0, 0.3, 0), new Vec3(0, -0.2, 0));
        grantFlight(f, false);
        f.input(JUMP);
        assertPredicted(f.tick(GROUND.add(0, 0.1, 0), false));
        if (releaseOnLanding) f.input(NONE);
        assertPredicted(f.tick(GROUND, true));
        return f;
    }

    /** A CHANGE_GAME_MODE game event, answered through its ping. */
    private static void gameMode(JavaTickFixture f, int mode) {
        var packet = new ClientboundGameEvent(GameEventType.CHANGE_GAME_MODE, mode);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.GAME_EVENT, packet, false);
        f.player.checkManager.getListener(PacketChangeGameState.class).onGameEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** A player_abilities letting the player fly and setting it flying, answered through its ping. */
    private static void grantFlight(JavaTickFixture f) {
        grantFlight(f, true);
    }

    private static void grantFlight(JavaTickFixture f, boolean flying) {
        grantFlight(f, flying, FLYING_SPEED);
    }

    private static void grantFlight(JavaTickFixture f, boolean flying, float flyingSpeed) {
        var packet = new ClientboundPlayerAbilities(false, flying, true, false, flyingSpeed, 0.1F);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_ABILITIES, packet, false);
        f.player.checkManager.getListener(PacketPlayerAbilities.class).onPlayerAbilities(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.isFlying == flying && f.player.canFly, "the abilities did not apply");
    }

    /**
     * A reconfiguration and a LOGIN answered through its ping, then the chunks and the player_abilities the new
     * backend sends with it.
     */
    private static void switchBackend(JavaTickFixture f) {
        login(f, GameMode.SURVIVAL);
        grantFlight(f);
    }

    /** A reconfiguration and a LOGIN into {@code mode} answered through its ping, then the chunks around spawn. */
    private static void login(JavaTickFixture f, GameMode mode) {
        var type = ClientboundPackets.FINISH_CONFIGURATION;
        var finish = new PacketSendEvent<>(f.player.user, ConnectionPhase.CONFIGURATION, type, new Opaque(type), false);
        new PacketServerRegistries().onFinishConfiguration(finish, f.player, new Opaque(type));
        finish.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(ItemUseTestSupport.login(f.player, mode));
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) f.player.compensatedWorld.ensureValidationChunkLoaded(x, z);
        }
    }

    /** The fixture's floor in the new level a LOGIN starts. */
    private static void relayTheFloor(JavaTickFixture f) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** A RESPAWN into {@code mode} in the same level, answered through its pings. */
    private static void respawn(JavaTickFixture f, GameMode mode) {
        var spawnInfo = new PlayerSpawnInfo(
                ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_end"),
                f.player.compensatedWorld.getLastClientboundDimension().dimension(),
                mode);
        var packet = new ClientboundRespawn(spawnInfo, (byte) 0);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        new PacketPlayerRespawn().onRespawn(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /**
     * From just above the ground: a press in the air arming the toggle, the landing, the server's player_abilities
     * letting the player fly, and a grounded double-tap under a knockback that takes the takeoff jump.
     */
    private static void assertArmedTakeoffUnderAKnockback(JavaTickFixture f) {
        assertArmedTakeoffUnderAKnockback(f, true);
    }

    /** As above, with or without the server's player_abilities after the landing. */
    private static void assertArmedTakeoffUnderAKnockback(JavaTickFixture f, boolean serverAbilities) {
        f.place(GROUND.add(0, 0.3, 0), new Vec3(0, -0.2, 0));
        f.input(JUMP);
        assertPredicted(f.tick(GROUND.add(0, 0.1, 0), false));
        f.input(NONE);
        assertPredicted(f.tick(GROUND, true));
        if (serverAbilities) grantFlight(f, false);

        f.pongThrough(f.velocity(KNOCKBACK));
        f.abilities(true);
        f.input(JUMP);
        assertPredicted(f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false));
        assertEquals(0.0, antiKb(f), "the client took the knockback");
    }

    /**
     * A player flying down through water three blocks deep over the floor, at a water movement efficiency of 1, for
     * two ticks; an armed player pressed jump on the first. Returns its position.
     */
    private static Vec3 flyingDownAFlooredPool(JavaTickFixture f, boolean armed) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++) block(f, x, y, z, BlockIds.WATER.defaultState());
            }
        }
        f.pongThrough(sendAttribute(f, "minecraft:water_movement_efficiency", 0.0, 1.0));
        Vec3 at = new Vec3(0.5, FLOOR_Y + 0.5, 0.5);
        f.place(at, new Vec3(0, -0.3, 0));
        grantFlight(f, true);
        f.input(armed ? JUMP : NONE);
        double fall = -0.3 + (armed ? IMPULSE : 0.0);
        at = at.add(0, fall, 0);
        f.tick(at, false);
        f.input(NONE);
        at = at.add(0, fall * 0.6, 0);
        f.tick(at, false);
        return at;
    }

    /** An update_attributes for the player; the transaction of its trailing ping. */
    private static int sendAttribute(JavaTickFixture f, String name, double base, double added) {
        var modifier = new AttributeModifier("test:modifier", added, AttributeModifier.Operation.ADD_VALUE);
        var packet = new ClientboundUpdateAttributes(
                f.player.entityID, List.of(new AttributeSnapshot(name, base, List.of(modifier))));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, packet, false);
        f.player.packetEntityReplication.onUpdateAttributes(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    /** A tick standing on the floor with shift held, then a grounded double-tap with forward and shift held. */
    private static void takeOffSneaking(JavaTickFixture f) {
        f.input(SHIFT);
        assertPredicted(f.tick(GROUND, true));
        f.abilities(true);
        f.input(FORWARD_JUMP_SHIFT);
    }

    /** Water at the feet of a player hovering at {@link #HOVER}. */
    private static void floodTheHover(JavaTickFixture f) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) block(f, x, (int) HOVER.y, z, BlockIds.WATER.defaultState());
        }
    }

    private static void block(JavaTickFixture f, int x, int y, int z, int state) {
        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), state);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /**
     * A player the server lets fly walks forward from rest on the floor for a tick, then presses jump with forward
     * released and rises by {@code rise}; whether Player#jumpTriggerTime can still be unarmed after the press.
     */
    private static boolean unarmedAfterAWalkingPress(JavaTickFixture f, double rise) {
        f.place(GROUND, new Vec3(0, STANDING_Y, 0));
        grantFlight(f, false);
        f.input(FORWARD);
        assertPredicted(f.tick(GROUND.add(0, 0, WALK_STEP), true));
        f.input(JUMP);
        assertPredicted(f.tick(GROUND.add(0, rise, WALK_STEP + WALK_STEP * (0.6F * 0.91F)), false));
        return (f.player.packetStateData.jumpTriggerTimes & 1) != 0;
    }

    /**
     * As {@link #unarmedAfterAWalkingPress}, on the ticks after the server ends the player's flight, where the
     * simulation exempts the walk.
     */
    private static boolean unarmedAfterAWalkingPressAfterFlight(JavaTickFixture f, double rise) {
        return unarmedAfterAWalkAfterFlight(
                f, GROUND, true, null, new Vec3(0, 0, WALK_STEP), new Vec3(0, rise, WALK_STEP * GROUND_FRICTION));
    }

    /**
     * A player the server lets fly comes to rest at {@code from}: sinking onto the floor there with shift held when
     * {@code onFloor}, hovering otherwise. The server ends the flight and a tick passes with nothing to send. A tick
     * walking forward, after a set_entity_motion of {@code velocity} when there is one, moves the player by
     * {@code walk}, then a press moves it by {@code press}; whether Player#jumpTriggerTime can still be unarmed.
     */
    private static boolean unarmedAfterAWalkAfterFlight(
            JavaTickFixture f, Vec3 from, boolean onFloor, Vec3 velocity, Vec3 walk, Vec3 press) {
        f.place(onFloor ? from.add(0, IMPULSE, 0) : from, Vec3.ZERO);
        grantFlight(f, true);
        f.input(onFloor ? SHIFT : NONE);
        assertTrue(f.tick(from, onFloor).isExempt(), "the flight was judged");
        grantFlight(f, false);
        f.input(NONE);
        f.tickEnd();
        f.input(FORWARD);
        if (velocity != null) f.pongThrough(f.velocity(velocity));
        assertTrue(f.tick(from.add(walk), true).isExempt(), "the walk was judged");
        f.input(JUMP);
        f.tick(from.add(walk).add(press), false);
        return (f.player.packetStateData.jumpTriggerTimes & 1) != 0;
    }

    /**
     * On the ticks after the server ends the player's flight, a hover {@code gap} above the floor against a wall
     * ahead, a tick with nothing to send, a tick walking into the wall that falls onto the floor, reported as
     * StatusOnly or, when {@code sendPosition}, as an unchanged position, then a press; whether
     * Player#jumpTriggerTime can still be unarmed after the press.
     */
    private static boolean unarmedAfterAClippedLandingAfterFlight(JavaTickFixture f, double gap, boolean sendPosition) {
        Vec3 rest = new Vec3(0.5, FLOOR_Y + gap, 0.7);
        f.place(rest, Vec3.ZERO);
        grantFlight(f, true);
        f.input(NONE);
        assertTrue(f.tick(rest, false).isExempt(), "the flight was judged");
        grantFlight(f, false);
        f.tickEnd();
        f.input(FORWARD);
        if (sendPosition) {
            assertTrue(f.tick(rest, true).isExempt(), "the landing was judged");
        } else {
            f.statusTick(true, true);
        }
        f.input(JUMP);
        f.tick(rest.add(0, JUMP_POWER, 0), false);
        return (f.player.packetStateData.jumpTriggerTimes & 1) != 0;
    }

    /** A half slab on the floor ahead, and a step up a block and a half high past it. */
    private static void halfSlabStepAhead(JavaTickFixture f) {
        int slab = DataTables.defaults().registry().block("minecraft:stone_slab").defaultState();
        block(f, 0, FLOOR_Y, 1, slab);
        block(f, 0, FLOOR_Y, 2, BlockIds.STONE.defaultState());
        block(f, 0, FLOOR_Y + 1, 2, slab);
    }

    /**
     * A release and a press in the air without player_abilities, the landing, and a grounded double-tap under a
     * knockback that takes the takeoff jump; the takeoff's prediction.
     */
    private static PredictionResult pressInTheAirThenTakeOff(JavaTickFixture f) {
        f.place(TAKEOFF.add(0, 0.6, 0), new Vec3(0, -0.2, 0));
        f.input(NONE);
        assertPredicted(f.tick(TAKEOFF.add(0, 0.4, 0), false));
        f.input(JUMP);
        assertPredicted(f.tick(TAKEOFF.add(0, 0.4 + FALL, 0), false));
        f.input(NONE);
        assertPredicted(f.tick(TAKEOFF, true));
        return takeOffUnderAKnockback(f);
    }

    /** A player the server lets fly arms the toggle with a press in the air, then releases it. */
    private static void armInTheAir(JavaTickFixture f) {
        f.place(TAKEOFF.add(0, 0.6, 0), new Vec3(0, -0.2, 0));
        grantFlight(f, false);
        f.input(JUMP);
        assertPredicted(f.tick(TAKEOFF.add(0, 0.4, 0), false));
        f.input(NONE);
        assertPredicted(f.tick(TAKEOFF.add(0, 0.4 + FALL, 0), false));
    }

    /** A landing with no keys held, then a grounded double-tap under a knockback with the takeoff jump. */
    private static PredictionResult landThenTakeOff(JavaTickFixture f) {
        f.place(TAKEOFF.add(0, 0.3, 0), new Vec3(0, -0.2, 0));
        f.input(NONE);
        assertPredicted(f.tick(TAKEOFF.add(0, 0.1, 0), false));
        assertPredicted(f.tick(TAKEOFF, true));
        return takeOffUnderAKnockback(f);
    }

    private static PredictionResult takeOffUnderAKnockback(JavaTickFixture f) {
        f.pongThrough(f.velocity(KNOCKBACK));
        f.abilities(true);
        f.input(JUMP);
        return f.tick(TAKEOFF.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false);
    }

    /** set_passengers empties the horse; the client accepts the dismount teleports it is sent. */
    private static void dismount(JavaTickFixture f) {
        List<Integer> before = new ArrayList<>();
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports) {
            before.add(teleport.getTeleportId());
        }
        f.setPassengers(HORSE, List.of());
        f.pongThrough(f.player.lastTransactionSent.get());
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports) {
            if (before.contains(teleport.getTeleportId())) continue;
            Vec3 target = teleport.getLocation();
            var packet = new ServerboundAcceptTeleportation(
                    teleport.getTeleportId(),
                    new Vec3d(target.x, target.y, target.z),
                    teleport.getFinalYaw(),
                    teleport.getFinalPitch());
            var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
            new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
        }
        assertFalse(f.player.inVehicle(), "the dismount did not apply");
    }

    private static double antiKb(JavaTickFixture f) {
        return f.player.checkManager.getKnockbackHandler().violations;
    }

    private static void assertPredicted(PredictionResult result) {
        assertTrue(result.getOffset() < 1.0E-5, describe(result));
    }
}
