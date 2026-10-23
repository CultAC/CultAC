package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A vanilla 26.3 client mounting a seated camel (CultAC issue #96, first capture). Camel#refuseToMove holds while the
 * camel sits and through the 52 ticks of its stand-up transition: Camel#getRiddenInput is zero and Camel#travel clears
 * the grounded camel's horizontal velocity. Camel#tickRidden stands it up on the client, at the client's game time,
 * the first root tick that reads the controller's forward zza; the server never sends that pose change.
 */
class CamelStandUpTest {
    private static final int CAMEL = 124;
    /** The capture's set_time before the stand-up. */
    private static final long GAME_TIME = 1090559L;
    /** Camel DASH and LAST_POSE_CHANGE_TICK in the 26.3 metadata layout. */
    private static final int DASH = 19;

    private static final int LAST_POSE_CHANGE_TICK = 20;
    private static final Vec3 START = new Vec3(0.35498046875, FLOOR_Y, 0.969970703125);
    /** Camel#getRiddenSpeed: movement_speed 0.09 plus 0.1 for a sprinting controller with no dash cooldown. */
    private static final float SPEED = 0.09F + 0.1F;
    /** LivingEntity#travelInAir: a grounded camel's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;

    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    /** LivingEntity#moveRelative for the ridden input's 0.98F on 0.6-friction ground. */
    private static final double GROUND_INPUT = (double) 0.98F * (SPEED * (0.21600002F / (0.6F * 0.6F * 0.6F)));

    /** Camel#executeRidersJump at full charge from rest: 1.4285F times the 0.42 jump strength, less the grounded fall. */
    private static final double DASH_CLIMB = 0.52157000337422;
    /** Camel#executeRidersJump at full charge: 22.2222F times the 0.09 movement speed along the look direction. */
    private static final double DASH_PUSH = 22.2222F * (double) 0.09F;

    private static final ServerboundPlayerInput IDLE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    /**
     * The capture's ticks after the mount: forward and sprint are pressed nine ticks in, the camel stands up the next
     * tick and stays in place for 52 ticks, then walks.
     */
    @Test
    void aSeatedCamelStandsUpBeforeItWalks() throws Exception {
        try (var f = new JavaTickFixture()) {
            mount(f, -(GAME_TIME - 1000L), Vec3.ZERO);

            for (int tick = 0; tick < 8; tick++) assertExact(ride(f, START));
            f.input(FORWARD_SPRINT);
            startSprinting(f);
            // The root ticked on the idle keys before LocalPlayer#aiStep read the pressed ones.
            assertExact(ride(f, START));
            for (int poseTime = 0; poseTime < 52; poseTime++) assertExact(ride(f, START));

            double velocity = GROUND_INPUT;
            double z = START.z + velocity;
            assertExact(ride(f, new Vec3(START.x, START.y, z)));
            velocity = velocity * GROUND_FRICTION + GROUND_INPUT;
            z += velocity;
            assertExact(ride(f, new Vec3(START.x, START.y, z)));
        }
    }

    /** A grounded camel ten ticks into standing up carries no horizontal velocity into its move. */
    @Test
    void aCamelStandingUpOnTheGroundDropsItsHorizontalVelocity() throws Exception {
        try (var f = new JavaTickFixture()) {
            mount(f, GAME_TIME - 10L, new Vec3(0.0, 0.0, 0.2));

            assertExact(ride(f, START));
        }
    }

    /**
     * Camel#canJump is false while the camel refuses to move, so LocalPlayer#jumpableVehicle sends no riding jump: a
     * seated camel takes neither the dash's climb nor its push along the look direction.
     */
    @Test
    void aSeatedCamelTakesNoRidingJump() throws Exception {
        try (var f = new JavaTickFixture()) {
            mount(f, -(GAME_TIME - 1000L), Vec3.ZERO);
            command(f, PlayerCommandAction.START_JUMPING_WITH_HORSE, 90);
            // LocalPlayer#aiStep sends the riding jump after the camel's tick, so it arms the next one.
            assertExact(ride(f, START));

            assertNotExact(ride(f, new Vec3(START.x, START.y + DASH_CLIMB, START.z + DASH_PUSH), false));
        }
    }

    @Test
    void aSeatedCamelDoesNotClimbOnARidingJump() throws Exception {
        try (var f = new JavaTickFixture()) {
            mount(f, -(GAME_TIME - 1000L), Vec3.ZERO);
            command(f, PlayerCommandAction.START_JUMPING_WITH_HORSE, 90);
            // LocalPlayer#aiStep sends the riding jump after the camel's tick, so it arms the next one.
            assertExact(ride(f, START));

            assertNotExact(ride(f, new Vec3(START.x, START.y + DASH_CLIMB, START.z), false));
        }
    }

    /**
     * AbstractHorse#tickRidden runs the armed dash before Camel#travel clears a refusing grounded camel's horizontal
     * velocity: a camel seated between the riding jump and its next tick keeps only the dash's climb.
     */
    @Test
    void aCamelSeatedAfterARidingJumpKeepsOnlyTheClimb() throws Exception {
        try (var f = new JavaTickFixture()) {
            mount(f, GAME_TIME - 1000L, Vec3.ZERO);
            command(f, PlayerCommandAction.START_JUMPING_WITH_HORSE, 90);
            // LocalPlayer#aiStep sends the riding jump after the camel's tick, so it arms the next one.
            assertExact(ride(f, START));
            f.player.compensatedEntities.updateEntityMetadata(
                    CAMEL,
                    List.of(new EntityMetadata.Entry(LAST_POSE_CHANGE_TICK, -GAME_TIME, ByteBuffer.allocate(0))));

            assertExact(ride(f, new Vec3(START.x, START.y + DASH_CLIMB, START.z), false));
        }
    }

    /** The saddled camel at {@code START}, its pose changed at {@code lastPoseChangeTick}, carrying {@code carried}. */
    private static void mount(JavaTickFixture f, long lastPoseChangeTick, Vec3 carried) {
        f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
        f.place(START, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, START, 0, 0, 0);
        PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
        camel.hasSaddle = true;
        camel.onGround = true;
        f.player.compensatedEntities.updateEntityMetadata(
                CAMEL,
                List.of(
                        new EntityMetadata.Entry(DASH, false, ByteBuffer.allocate(0)),
                        new EntityMetadata.Entry(LAST_POSE_CHANGE_TICK, lastPoseChangeTick, ByteBuffer.allocate(0))));
        f.setPassengers(CAMEL, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(IDLE);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(carried.x, GROUNDED_Y, carried.z));
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    private static void startSprinting(JavaTickFixture f) {
        command(f, PlayerCommandAction.START_SPRINTING, 0);
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action, int data) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, data);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }

    /** One client tick facing +z: the rider's Rot, the camel's grounded move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to) {
        return ride(f, to, true);
    }

    private static PredictionResult ride(JavaTickFixture f, Vec3 to, boolean onGround) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, 0F, 0F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), 0F, 0F, onGround, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }

    private static void assertExact(PredictionResult result) {
        StringBuilder report = new StringBuilder(describe(result));
        for (PredictionResult.Flag flag : result.getFlags()) {
            report.append(' ').append(flag.getVerbose().getString());
        }
        assertTrue(exact(result), report.toString());
    }

    private static void assertNotExact(PredictionResult result) {
        assertFalse(exact(result), describe(result));
    }
}
