package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A ridden root whose shared flag 7 says it is fall flying, as /summon with FallFlying and an elytra leaves it (CultAC
 * issue #244). LivingEntity#travel glides any living entity with LivingEntity#isFallFlying through travelFallFlying,
 * which ignores the ridden input, and AbstractHorse#tickRidden turns the horse to the rider's yaw and half its pitch
 * first. The client glides until the server's set_entity_data clears the flag.
 */
class JavaRiddenGlideTest {
    private static final int VEHICLE = 100;
    private static final Vec3 START = new Vec3(0.5, 84.0, 0.5);
    private static final float SPEED = 0.225F;

    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    /** The captured glide from rest: forward held, the rider looking along +z with level pitch. */
    @Test
    void aFallFlyingHorseGlides() throws Exception {
        try (var f = glidingHorse()) {
            Vec3 velocity = Vec3.ZERO;
            Vec3 position = START;
            for (int tick = 0; tick < 4; tick++) {
                velocity = glide(threshold(velocity), 0F, 0F);
                position = position.add(velocity);
                assertVehicleExact(ride(f, position, 0F, 0F, 0F), tick);
            }
        }
    }

    /** The glide reads the pitch AbstractHorse#tickRidden gives the horse: half the rider's, not the one it reports. */
    @Test
    void theGlideTakesHalfTheRidersPitch() throws Exception {
        for (float reported : new float[] {15F, -45F}) {
            try (var f = glidingHorse()) {
                Vec3 velocity = Vec3.ZERO;
                Vec3 position = START;
                for (int tick = 0; tick < 3; tick++) {
                    velocity = glide(threshold(velocity), 0F, reported);
                    position = position.add(velocity);
                    var result = ride(f, position, 0F, 30F, reported);
                    if (reported == 15F) {
                        assertVehicleExact(result, tick);
                        continue;
                    }
                    assertFalse(exact(result), describe(result));
                    break;
                }
            }
        }
    }

    /** After set_entity_data clears the flag the horse travels in the air with the ridden input, and a glide flags. */
    @Test
    void theHorseStopsGlidingWhenTheServerClearsTheFlag() throws Exception {
        for (boolean keepsGliding : new boolean[] {false, true}) {
            try (var f = glidingHorse()) {
                Vec3 velocity = glide(Vec3.ZERO, 0F, 0F);
                Vec3 position = START.add(velocity);
                assertVehicleExact(ride(f, position, 0F, 0F, 0F), 0);
                velocity = glide(threshold(velocity), 0F, 0F);
                position = position.add(velocity);
                assertVehicleExact(ride(f, position, 0F, 0F, 0F), 1);

                sharedFlags(f, (byte) 0);
                if (keepsGliding) {
                    var glided = ride(f, position.add(glide(threshold(velocity), 0F, 0F)), 0F, 0F, 0F);
                    assertFalse(exact(glided), describe(glided));
                    continue;
                }
                // LivingEntity#travelInAir: the threshold, then moveRelative's airborne input along +z.
                Vec3 moved = threshold(velocity).add(0, 0, (double) 0.98F * (SPEED * 0.1F));
                assertVehicleExact(ride(f, position.add(moved), 0F, 0F, 0F), 2);
            }
        }
    }

    /**
     * A gliding nautilus that starts a tick on a ladder runs LivingEntity#travelInAir and then stopFallFlying, which
     * clears its flag on the client: below the ladder it falls without gliding although the server sent no new flags.
     */
    @Test
    void aNautilusStopsGlidingOnALadder() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 1, 0), BlockIds.LADDER.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
            // Against the north-facing ladder's plate at z = 13/16, the nautilus being 0.875 wide.
            Vec3 seat = new Vec3(0.5, FLOOR_Y + 1, 0.8125 - 0.4375);
            f.player.compensatedEntities.addEntity(
                    VEHICLE, EntityTypes.defaults().byKey("minecraft:nautilus").id(), seat, 0, 0, 0);
            var nautilus = (PacketEntityNautilus) f.player.compensatedEntities.getEntity(VEHICLE);
            nautilus.hasSaddle = true;
            nautilus.isTame = true;
            nautilus.onGround = false;
            mount(f, NONE);
            atRest(f);

            // LivingEntity#handleOnClimbable keeps the fall above -0.15F on the ladder.
            double fallen = -0.0784000015258789;
            assertVehicleExact(f.rideTick(seat, false), 0);
            Vec3 below = seat.add(0, fallen, 0);
            assertVehicleExact(f.rideTick(below, false), 1);
            assertVehicleExact(f.rideTick(below.add(0, (fallen - 0.08) * 0.98F, 0), false), 2);
        }
    }

    /** A saddled horse at rest in the air at {@link #START}, fall flying with an elytra, ridden with forward held. */
    private static JavaTickFixture glidingHorse() throws Exception {
        var f = new JavaTickFixture();
        f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.HORSE, START, 0, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.movementSpeedAttribute = SPEED;
        horse.onGround = false;
        mount(f, FORWARD);
        f.tickEnd();
        atRest(f);
        return f;
    }

    /** Equips the root with an elytra, sets its fall flying flag, seats the player on it and holds {@code input}. */
    private static void mount(JavaTickFixture f, ServerboundPlayerInput input) {
        f.player.compensatedEntities.updateEntityEquipment(
                VEHICLE,
                List.of(new InventoryPackets.EquipmentEntry(
                        EquipmentSlot.CHEST, OfflineCultTestBootstrap.item("minecraft:elytra"))));
        sharedFlags(f, (byte) 0x80);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(input);
    }

    private static void atRest(JavaTickFixture f) {
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
    }

    private static void sharedFlags(JavaTickFixture f, byte flags) {
        f.player.compensatedEntities.updateEntityMetadata(
                VEHICLE,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_SHARED_FLAGS, flags, ByteBuffer.allocate(0))));
    }

    /** LivingEntity#aiStep's 0.003 threshold on each axis of a non-player's deltaMovement. */
    private static Vec3 threshold(Vec3 velocity) {
        return new Vec3(
                Math.abs(velocity.x) < 0.003 ? 0 : velocity.x,
                Math.abs(velocity.y) < 0.003 ? 0 : velocity.y,
                Math.abs(velocity.z) < 0.003 ? 0 : velocity.z);
    }

    /** LivingEntity#updateFallFlyingMovement for the horse's yaw and pitch, with a horse's 0.08 gravity. */
    private static Vec3 glide(Vec3 movement, float yaw, float pitch) {
        float xRotRad = pitch * (float) (Math.PI / 180.0);
        float yRotRad = -yaw * (float) (Math.PI / 180.0);
        float yCos = cos(yRotRad);
        float ySin = sin(yRotRad);
        float xCos = cos(xRotRad);
        float xSin = sin(xRotRad);
        Vec3 look = new Vec3(ySin * xCos, -xSin, yCos * xCos);
        double lookHorLength = Math.sqrt(look.x * look.x + look.z * look.z);
        double moveHorLength = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        double liftForce = Math.cos(xRotRad) * Math.cos(xRotRad);
        movement = movement.add(0.0, 0.08 * (-1.0 + liftForce * 0.75), 0.0);
        if (movement.y < 0.0 && lookHorLength > 0.0) {
            double convert = movement.y * -0.1 * liftForce;
            movement = movement.add(look.x * convert / lookHorLength, convert, look.z * convert / lookHorLength);
        }
        if (xRotRad < 0.0F && lookHorLength > 0.0) {
            double convert = moveHorLength * -sin(xRotRad) * 0.04;
            movement =
                    movement.add(-look.x * convert / lookHorLength, convert * 3.2, -look.z * convert / lookHorLength);
        }
        if (lookHorLength > 0.0) {
            movement = movement.add(
                    (look.x / lookHorLength * moveHorLength - movement.x) * 0.1,
                    0.0,
                    (look.z / lookHorLength * moveHorLength - movement.z) * 0.1);
        }
        return movement.multiply(0.99F, 0.98F, 0.99F);
    }

    private static float sin(float angle) {
        return VanillaMath.sin(ClientVersion.V_26_3, angle);
    }

    private static float cos(float angle) {
        return VanillaMath.cos(ClientVersion.V_26_3, angle);
    }

    /** One client tick: the rider's Rot, the horse's move with the rotation it reports, and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to, float yaw, float riderPitch, float vehiclePitch) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, riderPitch, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), yaw, vehiclePitch, false, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }
}
