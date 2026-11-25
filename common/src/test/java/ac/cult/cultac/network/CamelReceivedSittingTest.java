package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.combat.PlayerHitboxes;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.impl.combat.ReachCheck.MeasuredAttack;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.checks.impl.vehicle.VehicleGround;
import ac.cult.cultac.network.codec.ObservedPacketValues;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.testing.CodecFixture;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A vanilla 26.3 client mounting a seated camel (CultAC issue #104). Camel#LAST_POSE_CHANGE_TICK is synched with
 * EntityDataSerializers.LONG (serializer 2) and is negative while the camel sits; the server sends it with the SITTING
 * pose. Camel#refuseToMove holds while the camel sits and through its 52-tick stand-up, and Camel#tickRidden stands it
 * up on the client the first root tick that reads the controller's forward zza.
 */
class CamelReceivedSittingTest {
    private static final int CAMEL = 8518;
    private static final int TARGET = 1;
    /** The capture's set_time after the mount. */
    private static final long GAME_TIME = 1789319L;
    /** A camel that sat down long before the mount. */
    private static final long SAT_DOWN = -(GAME_TIME - 1000L);
    /** Entity#DATA_POSE and Camel#LAST_POSE_CHANGE_TICK in the 26.3 metadata layout. */
    private static final int POSE = 6;

    private static final int LAST_POSE_CHANGE_TICK = 20;
    private static final int POSE_SERIALIZER = 20;
    private static final int LONG_SERIALIZER = 2;
    private static final Vec3 START = new Vec3(0.874267578125, FLOOR_Y, 0.337890625);
    /** The capture's camel and rider rotations from the mount until the stand-up ends. */
    private static final float CAMEL_YAW = -56.25F;

    private static final float RIDER_YAW = -37.5F;
    /** Camel#getRiddenSpeed: movement_speed 0.09 plus 0.1 for a sprinting controller with no dash cooldown. */
    private static final float SPEED = 0.09F + 0.1F;
    /** LivingEntity#moveRelative for the ridden input's 0.98F on 0.6-friction ground. */
    private static final double GROUND_INPUT = (double) 0.98F * (SPEED * (0.21600002F / (0.6F * 0.6F * 0.6F)));

    private static final ServerboundPlayerInput IDLE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    /** The delta LivingEntity#travelInAir leaves a standing camel: one tick of gravity, 0.08, and its 0.98 drag. */
    private static final Vec3 RESTING = new Vec3(0, -0.0783739241897089, 0);

    /**
     * The capture's ticks after the mount. The camel's own Entity#move reports the ground only when it moved down into
     * it, and LivingEntity#travelInAir moves before its gravity: the camel mounts with no velocity and is off the ground
     * for that tick, and again for the tick after each set_entity_motion of none, which ClientPacketListener
     * #handleSetEntityMotion stores in the camel through Entity#lerpMotion. The server sends one such motion for its own
     * camel, whose LivingEntity#travelRidden keeps no delta under a client's rider, and one with most of the vehicle
     * corrections of the stand-up. ClientPacketListener#handleMoveVehicle answers each correction at once with the
     * camel as it is, with no tick and without turning a camel that is within 1.0E-5F of the correction. Forward and
     * sprint are pressed fifteen ticks in, the camel stands up the next tick, and it stays in place through the 52
     * ticks of the stand-up.
     */
    @Test
    void aReceivedSeatedCamelStandsUpBeforeItWalks() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
            f.place(START, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, START, CAMEL_YAW, 0, 0);
            PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
            camel.hasSaddle = true;
            camel.onGround = true;
            receiveSitting(f);
            assertTrue(camel.isCamelSitting());
            f.setPassengers(CAMEL, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(IDLE);
            f.tickEnd();

            assertExact(ride(f, START, RIDER_YAW, CAMEL_YAW, false));
            assertExact(ride(f, START, RIDER_YAW, CAMEL_YAW, true));
            f.entityMotion(CAMEL, Vec3.ZERO);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertExact(ride(f, START, RIDER_YAW, CAMEL_YAW, false));
            for (int tick = 0; tick < 12; tick++) assertExact(ride(f, START, RIDER_YAW, CAMEL_YAW, true));
            f.input(FORWARD_SPRINT);
            f.command(PlayerCommandAction.START_SPRINTING);
            // The root ticked on the idle keys before LocalPlayer#aiStep read the pressed ones.
            assertExact(ride(f, START, RIDER_YAW, CAMEL_YAW, true));

            int standUp = 0;
            standUp += correctedTick(f, true, RESTING, true);
            standUp += correctedTick(f, true, Vec3.ZERO, true);
            standUp += correctedTick(f, false, Vec3.ZERO, false);
            standUp += tick(f, false);
            standUp += tick(f, true);
            standUp += correctedTick(f, true, RESTING, true);
            standUp += correctedTick(f, true, Vec3.ZERO, true);
            standUp += correctedTick(f, false, Vec3.ZERO, false);
            standUp += correctedTick(f, false, Vec3.ZERO, false);
            standUp += correctedTick(f, false, Vec3.ZERO, false);
            f.input(IDLE);
            f.command(PlayerCommandAction.STOP_SPRINTING);
            standUp += correctedTick(f, false, Vec3.ZERO, false);
            standUp += tick(f, false);
            while (standUp < 52) standUp += tick(f, true);

            f.input(FORWARD_SPRINT);
            f.command(PlayerCommandAction.START_SPRINTING);
            assertExact(ride(f, START, 0F, 0F, true));
            assertExact(ride(f, new Vec3(START.x, START.y, START.z + GROUND_INPUT), 0F, 0F, true));
            assertEquals(0.0, f.player.checkManager.getCheck(VehicleGround.class).violations,
                    "the captured seated-camel ground sequence was flagged");
            assertEquals(0.0, f.player.checkManager.getCheck(VehicleOffset.class).violations);
            assertEquals(0.0, f.player.checkManager.getCheck(BadPacketsVehicle.class).violations);
            assertEquals(0, f.player.getSetbackTeleportUtil().vehicleTeleports().queuedVehicleTeleportCount());
        }
    }

    /**
     * The capture's first PlayerHitboxes attack, moved onto the fixture floor: the driver of the camel that stood up
     * hits a player from the standing seat. Camel#getPassengerAttachmentPoint seats the driver at
     * Camel#getBodyAnchorAnimationYOffset, 2.375 less 0.375 for a standing adult camel.
     */
    @Test
    void theDriverOfAReceivedSeatedCamelHitsFromTheStandingSeat() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.observer = new PlayerObserver() {};
            f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
            Vec3 camelAt = onFloor(-537.2741824192484, 1844.8599019384894, -538, 1844);
            float yaw = -221.28593F;
            f.place(camelAt.add(0, 2.0, 0), Vec3.ZERO);
            f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, camelAt, yaw, 0, 0);
            PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
            camel.hasSaddle = true;
            camel.onGround = true;
            receiveSitting(f);
            f.setPassengers(CAMEL, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(FORWARD_SPRINT);
            drive(f, camelAt, yaw, 54.082863F);
            f.input(IDLE);
            for (int tick = 0; tick < 60; tick++) drive(f, camelAt, yaw, 54.082863F);
            f.player.compensatedEntities.addEntity(
                    TARGET,
                    EntityTypeIds.PLAYER,
                    onFloor(-538.6999999880791, 1843.300000011921, -538, 1844),
                    -2.1536286F,
                    0,
                    0);
            PlayerReach reach = f.player.checkManager.getCheck(PlayerReach.class);
            reach.setEnabled(true);
            PlayerHitboxes hitboxes = f.player.checkManager.getCheck(PlayerHitboxes.class);
            hitboxes.setEnabled(true);

            var attack =
                    new ServerboundInteract(TARGET, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            f.player.checkManager.dispatchReceiveHandlers(f.event(ServerboundPackets.INTERACT, attack));
            drive(f, camelAt, yaw, 55.357773F);

            List<MeasuredAttack> measured = reach.measuredAttacks();
            assertEquals(1, measured.size());
            assertEquals(0.0, hitboxes.violations);
            assertEquals(0.0, reach.violations);
        }
    }

    /**
     * The camel's SITTING pose and negative LAST_POSE_CHANGE_TICK as set_entity_data wire bytes, read by both clientbound
     * decoders and applied from the packet codec.
     */
    private static void receiveSitting(JavaTickFixture f) throws Exception {
        var data = ProtocolData.load(ProtocolVersion.V26_3);
        var runtime = ProtocolRuntime.create(data, OfflineCultTestBootstrap.catalog());
        var codec = new CodecFixture(runtime);
        codec.phase(ConnectionPhase.PLAY);
        int id = data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:set_entity_data");
        var observed = new ObservedPacketValues(
                ProtocolCodecs.decoder(),
                ProtocolVersion.V26_3,
                () -> f.player.user.getCultConnection().platform().registryNames(),
                f.player.getWorldRegistries()::painting,
                () -> new int[] {-64, 384});
        var bytes = Unpooled.buffer();
        try {
            Wire.writeVarInt(bytes, CAMEL);
            bytes.writeByte(POSE);
            Wire.writeVarInt(bytes, POSE_SERIALIZER);
            Wire.writeVarInt(bytes, EntityPose.SITTING.ordinal());
            bytes.writeByte(LAST_POSE_CHANGE_TICK);
            Wire.writeVarInt(bytes, LONG_SERIALIZER);
            Wire.writeVarLong(bytes, SAT_DOWN);
            bytes.writeByte(255);
            var observedDecoded = (EntityMetadata) observed.read(
                    bytes.duplicate(),
                    runtime.binding(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND, id)
                            .context());
            assertEquals(SAT_DOWN, observedDecoded.packedItems().get(1).value());
            var decoded = (EntityMetadata) codec.read(PacketDirection.CLIENTBOUND, id, bytes);
            assertEquals(EntityPose.SITTING, decoded.packedItems().get(0).value());
            assertEquals(SAT_DOWN, decoded.packedItems().get(1).value());
            f.player.compensatedEntities.updateEntityMetadata(CAMEL, decoded.packedItems());
        } finally {
            bytes.release();
        }
    }

    private static Vec3 onFloor(double x, double z, double originX, double originZ) {
        return new Vec3(x - originX, FLOOR_Y, z - originZ);
    }

    /** One client tick of the camel in place; 1, the tick it counts. */
    private static int tick(JavaTickFixture f, boolean onGround) {
        assertExact(ride(f, START, RIDER_YAW, CAMEL_YAW, onGround));
        return 1;
    }

    /**
     * One client tick of the camel in place that the server answers before the tick's end with a vehicle correction
     * at the camel's position, turned to the rider, and a set_entity_motion of {@code motion}. The client handles
     * both after its tick and answers the correction with the unturned camel and {@code answerOnGround}, the ground
     * of the move it just made.
     */
    private static int correctedTick(JavaTickFixture f, boolean onGround, Vec3 motion, boolean answerOnGround) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, RIDER_YAW, 0F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(START, CAMEL_YAW, 0F, onGround);
        f.serverMoveVehicle(START, RIDER_YAW, 0F);
        f.entityMotion(CAMEL, motion);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        assertExact(result);
        f.pongThrough(f.player.lastTransactionSent.get());
        f.moveVehicle(START, CAMEL_YAW, 0F, answerOnGround);
        return 1;
    }

    /** One client tick: the rider's Rot, the camel's move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to, float riderYaw, float camelYaw, boolean onGround) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, riderYaw, 0F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), camelYaw, 0F, onGround, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }

    private static void drive(JavaTickFixture f, Vec3 to, float yaw, float pitch) {
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), yaw, pitch / 2, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
    }

    private static void assertExact(PredictionResult result) {
        StringBuilder report = new StringBuilder(describe(result));
        for (PredictionResult.Flag flag : result.getFlags()) {
            report.append(' ').append(flag.getVerbose().getString());
        }
        assertTrue(exact(result), report.toString());
    }
}
