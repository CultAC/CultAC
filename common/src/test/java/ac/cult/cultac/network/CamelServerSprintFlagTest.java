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
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A vanilla 26.3 client strafing a camel after it stopped sprinting (CultAC issue #96, second capture, tick seq 7929),
 * shifted by (-160, -8, -160) onto the fixture's floor. The server's echo of the stop is acknowledged before the next
 * tick: Minecraft#runTick applies it ahead of the camel's travel, so Camel#getRiddenSpeed has no sprint bonus.
 */
class CamelServerSprintFlagTest {
    private static final int CAMEL = 124;
    private static final int DASH = 19;
    private static final int LAST_POSE_CHANGE_TICK = 20;
    private static final long GAME_TIME = 1093579L;
    /** LivingEntity#travelInAir: a grounded camel's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;

    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final Vec3 REST = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final int OTHER_CAMEL = 125;
    private static final Vec3 OTHER_REST = new Vec3(5.5, FLOOR_Y, 0.5);
    private static final int DRIVER = 300;
    /** Camel#getRiddenSpeed: movement_speed 0.09, plus 0.1 for a sprinting controller with no dash cooldown. */
    private static final float WALK = 0.09F;

    private static final float SPRINT = 0.09F + 0.1F;
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_LEFT_SPRINT =
            new ServerboundPlayerInput(true, false, true, false, false, false, true);
    private static final ServerboundPlayerInput RIGHT_SPRINT =
            new ServerboundPlayerInput(false, false, false, true, false, false, true);

    @Test
    void theServersSprintStopHoldsThroughTheNextCamelTravel() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 before = new Vec3(4.68418015415574, FLOOR_Y, 5.06837591517217);
            Vec3 start = new Vec3(4.51755849908798, FLOOR_Y, 4.99541130541238);
            mount(f, start, carried(start.subtract(before)));
            sharedFlags(f, (byte) 0);

            assertExact(
                    ride(f, 502.96863F, 142.96863F, 44.017624F, new Vec3(4.46178671554094, FLOOR_Y, 4.92901112445836)));
        }
    }

    /**
     * The server's sprint stop reaches a rider still holding forward and sprint: LocalPlayer#aiStep sprints again in
     * the same tick and LocalPlayer#sendIsSprintingIfNeeded sends nothing, so only one travel lacks the bonus.
     */
    @Test
    void aRiderHoldingSprintSprintsAgainAfterTheServersStop() throws Exception {
        try (var f = new JavaTickFixture()) {
            mountAtRest(f, FORWARD_SPRINT, true);
            sharedFlags(f, (byte) 0);

            double velocity = forwardInput(WALK);
            double z = REST.z + velocity;
            assertExact(ride(f, 0F, 0F, 0F, new Vec3(REST.x, REST.y, z)));
            velocity = velocity * GROUND_FRICTION + forwardInput(SPRINT);
            z += velocity;
            assertExact(ride(f, 0F, 0F, 0F, new Vec3(REST.x, REST.y, z)));
        }
    }

    /**
     * The server's sprint start reaches a rider strafing without forward: one travel has the bonus, then
     * LocalPlayer#aiStep stops sprinting and LocalPlayer#sendIsSprintingIfNeeded sends nothing.
     */
    @Test
    void aStrafingRiderStopsAgainAfterTheServersStart() throws Exception {
        try (var f = new JavaTickFixture()) {
            mountAtRest(f, LEFT, false);
            sharedFlags(f, (byte) 0x08);

            double velocity = strafeInput(SPRINT);
            double x = REST.x + velocity;
            assertExact(ride(f, 0F, 0F, 0F, new Vec3(x, REST.y, REST.z)));
            velocity = velocity * GROUND_FRICTION + strafeInput(WALK);
            x += velocity;
            assertExact(ride(f, 0F, 0F, 0F, new Vec3(x, REST.y, REST.z)));
        }
    }

    /**
     * LocalPlayer#aiStep sets a rider's sprint flag only with forward impulse and clears it without one, so a rider
     * strafing without forward ends every tick not sprinting, whatever it sent.
     */
    @Test
    void aStrafingRidersSprintStartGivesNoBonus() throws Exception {
        try (var f = new JavaTickFixture()) {
            mountAtRest(f, LEFT, true);

            double velocity = strafeInput(WALK);
            double x = REST.x + velocity;
            assertExact(ride(f, 0F, 0F, 0F, new Vec3(x, REST.y, REST.z)));
            velocity = velocity * GROUND_FRICTION + strafeInput(WALK);
            x += velocity;
            assertExact(ride(f, 0F, 0F, 0F, new Vec3(x, REST.y, REST.z)));
        }
    }

    /**
     * A back-seat rider has its flag cleared by LocalPlayer#aiStep. Until the client applies the passenger list that
     * moves it to the front, its ticks stay back-seat ticks, so the first travel it controls has no bonus.
     */
    @Test
    void aBackSeatRiderMovedToTheFrontKeepsItsClearedFlagUntilTheClientSeesTheMove() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
            f.place(REST, Vec3.ZERO);
            standingCamel(f, CAMEL, REST).hasSaddle = true;
            f.player.compensatedEntities.addEntity(DRIVER, EntityTypeIds.ZOMBIE, REST, 0, 0, 0);
            f.input(FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();

            f.setPassengers(CAMEL, List.of(DRIVER, f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.rideTick();
            f.rideTick();

            f.setPassengers(CAMEL, List.of(f.player.entityID));
            f.rideTick();
            assertFalse(f.player.isClientSprinting());

            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0.0, GROUNDED_Y, 0.0));
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            double velocity = forwardInput(WALK);
            double z = REST.z + velocity;
            assertExact(ride(f, 0F, 0F, 0F, new Vec3(REST.x, REST.y, z)));
            velocity = velocity * GROUND_FRICTION + forwardInput(SPRINT);
            z += velocity;
            assertExact(ride(f, 0F, 0F, 0F, new Vec3(REST.x, REST.y, z)));
        }
    }

    /**
     * A rider of a camel it does not control has its flag cleared by LocalPlayer#aiStep and sends no STOP_SPRINTING.
     * Moved onto a saddled camel with forward and sprint held, its first travel reads the cleared flag; aiStep sprints
     * again that tick and LocalPlayer#sendIsSprintingIfNeeded sends nothing, so the next travel has the bonus.
     */
    @Test
    void aRiderMovedOffAnUnsaddledCamelSprintsAgainWithoutACommand() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
            f.place(REST, Vec3.ZERO);
            standingCamel(f, CAMEL, REST).hasSaddle = true;
            standingCamel(f, OTHER_CAMEL, OTHER_REST);
            f.input(FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();

            f.setPassengers(OTHER_CAMEL, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.rideTick();
            f.rideTick();

            f.setPassengers(OTHER_CAMEL, List.of());
            f.setPassengers(CAMEL, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0.0, GROUNDED_Y, 0.0));
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);

            double velocity = forwardInput(WALK);
            double z = REST.z + velocity;
            assertExact(ride(f, 0F, 0F, 0F, new Vec3(REST.x, REST.y, z)));
            velocity = velocity * GROUND_FRICTION + forwardInput(SPRINT);
            z += velocity;
            assertExact(ride(f, 0F, 0F, 0F, new Vec3(REST.x, REST.y, z)));
        }
    }

    /** The grounded, standing camel {@code id} at {@code at}. */
    private static PacketEntityCamel standingCamel(JavaTickFixture f, int id, Vec3 at) {
        f.player.compensatedEntities.addEntity(id, EntityTypeIds.CAMEL, at, 0, 0, 0);
        PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(id);
        camel.onGround = true;
        f.player.compensatedEntities.updateEntityMetadata(
                id,
                List.of(
                        new EntityMetadata.Entry(DASH, false, ByteBuffer.allocate(0)),
                        new EntityMetadata.Entry(LAST_POSE_CHANGE_TICK, GAME_TIME - 1000L, ByteBuffer.allocate(0))));
        return camel;
    }

    /** LivingEntity#moveRelative of the full forward zza at {@code speed} on 0.6-friction ground. */
    private static double forwardInput(float speed) {
        return (double) 0.98F * (speed * (0.21600002F / (0.6F * 0.6F * 0.6F)));
    }

    /** LivingEntity#moveRelative of AbstractHorse#getRiddenInput's halved xxa at {@code speed}. */
    private static double strafeInput(float speed) {
        return (double) (0.98F * 0.5F) * (speed * (0.21600002F / (0.6F * 0.6F * 0.6F)));
    }

    /** The saddled, standing camel at rest at {@code REST}, ridden on {@code keys}, the rider sprinting or not. */
    private static void mountAtRest(JavaTickFixture f, ServerboundPlayerInput keys, boolean sprinting) {
        f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
        f.place(REST, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, REST, 0, 0, 0);
        PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
        camel.hasSaddle = true;
        camel.onGround = true;
        f.player.compensatedEntities.updateEntityMetadata(
                CAMEL,
                List.of(
                        new EntityMetadata.Entry(DASH, false, ByteBuffer.allocate(0)),
                        new EntityMetadata.Entry(LAST_POSE_CHANGE_TICK, GAME_TIME - 1000L, ByteBuffer.allocate(0))));
        f.setPassengers(CAMEL, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(keys);
        if (sprinting) command(f, PlayerCommandAction.START_SPRINTING);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0.0, GROUNDED_Y, 0.0));
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    /** The grounded camel's velocity after it moved {@code moved} unobstructed: LivingEntity#travelInAir's friction. */
    private static Vec3 carried(Vec3 moved) {
        return new Vec3(moved.x * GROUND_FRICTION, GROUNDED_Y, moved.z * GROUND_FRICTION);
    }

    /**
     * The saddled, standing camel at {@code start} carrying {@code carried}. The rider sprints, then LocalPlayer#aiStep
     * reads right alone and stops sprinting: the tick's STOP_SPRINTING follows its move.
     */
    private static void mount(JavaTickFixture f, Vec3 start, Vec3 carried) {
        f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
        f.place(start, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, start, 142.56598F, 0, 0);
        PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
        camel.hasSaddle = true;
        camel.onGround = true;
        f.player.compensatedEntities.updateEntityMetadata(
                CAMEL,
                List.of(
                        new EntityMetadata.Entry(DASH, false, ByteBuffer.allocate(0)),
                        new EntityMetadata.Entry(LAST_POSE_CHANGE_TICK, GAME_TIME - 1000L, ByteBuffer.allocate(0))));
        f.setPassengers(CAMEL, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(FORWARD_LEFT_SPRINT);
        command(f, PlayerCommandAction.START_SPRINTING);
        f.tickEnd();
        f.input(RIGHT_SPRINT);
        command(f, PlayerCommandAction.STOP_SPRINTING);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(carried);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }

    /** The server's shared flags for the player, acknowledged by the client. */
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

    /** One client tick: the rider's Rot, the camel's grounded move at half the pitch and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, float riderYaw, float camelYaw, float pitch, Vec3 to) {
        PredictionResult previous =
                f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, riderYaw, pitch, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), camelYaw, pitch * 0.5F, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != previous, "the vehicle move was not predicted");
        return result;
    }

    private static void assertExact(PredictionResult result) {
        StringBuilder report = new StringBuilder(describe(result));
        for (PredictionResult.Flag flag : result.getFlags()) {
            report.append(' ').append(flag.getVerbose().getString());
        }
        assertTrue(exact(result), report.toString());
    }
}
