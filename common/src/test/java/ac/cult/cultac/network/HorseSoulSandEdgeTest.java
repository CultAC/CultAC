package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A vanilla 26.3 client riding a horse at the edge of soul sand (CultAC issue #76): the captured ground, rotations and
 * vehicle moves. Its feet stay at y 64 on the full blocks while its box overhangs the 0.875-tall soul sand, so
 * Entity#checkSupportingBlock finds a full block. Entity#getBlockSpeedFactor reads air at the feet, then the block at
 * Entity#getBlockPosBelowThatAffectsMyMovement: the main supporting block's column, not the column under the horse's
 * center.
 */
class HorseSoulSandEdgeTest {
    private static final int HORSE = 100;
    /** The captured horse's movement_speed: a move from rest covers 0.98F of it. */
    private static final float SPEED = 0.14814077F;
    /** LivingEntity#travelInAir on a 0.6-friction block. */
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    /** LivingEntity#travelInAir: a grounded horse's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;
    /** The top of honey's 0.9375-tall shape on the y 63 layer. */
    private static final double HONEY_TOP = FLOOR_Y - 0.0625;
    /**
     * LivingEntity#moveRelative for the ridden input's 0.98F along the horse's facing on 0.6-friction ground:
     * getFrictionInfluencedSpeed scales movement_speed by 0.21600002F over the friction cubed.
     */
    private static final double GROUND_INPUT = (double) 0.98F * (SPEED * (0.21600002F / (0.6F * 0.6F * 0.6F)));
    /** The same in the air, where getFlyingSpeed with a controlling player is movement_speed * 0.1F. */
    private static final double AIR_INPUT = (double) 0.98F * (SPEED * 0.1F);
    /** LivingEntity#travelInAir's friction after an airborne start. */
    private static final double AIR_FRICTION = 0.91F;
    /** Honey's speed factor, which Entity#move applies to the horizontal velocity. */
    private static final double HONEY_SPEED_FACTOR = 0.4F;

    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    /** Ticks seq 3139 and 3184. The stone at x 251 supports the horse, so its factor is 1.0. */
    @Test
    void aHorseOnGrassAlongSoulSandKeepsItsSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            ground(f, BlockIds.SOUL_SAND);
            Vec3 before = new Vec3(251.5866402041141, 64.0, 164.9410660631421);
            Vec3 start = new Vec3(251.9057245669415, 64.0, 164.92119323886106);
            // Its center is over the full block at x 251, which also supports it.
            mount(f, start, 265.88745F, carried(start.subtract(before), 1.0F));

            assertExact(ride(f, 265.68613F, 26.236082F, new Vec3(252.22471060116337, 64.0, 164.89941287885532)));
            assertExact(ride(f, 265.5519F, 26.974188F, new Vec3(252.54361741212884, 64.0, 164.87625793187456)));
        }
    }

    /**
     * A horse facing +x falls onto the edge of the soul sand. The landing move's verticalCollisionBelow makes the stone
     * at x 251 its supporting block on the landing tick itself, so Entity#getBlockSpeedFactor is 1.0 after every move.
     * Each move adds the ridden input to the velocity LivingEntity#travelInAir carries: 0.91F after the airborne starts,
     * 0.6F * 0.91F after the grounded one.
     */
    @Test
    void aHorseLandingBesideSoulSandKeepsItsSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            ground(f, BlockIds.SOUL_SAND);
            mount(f, new Vec3(251.5, 64.25, 164.9), 270F, new Vec3(0.3, -0.1, 0), false);

            double velocity = 0.3 + AIR_INPUT;
            double x = 251.5 + velocity;
            assertExact(ride(f, 270F, 0F, new Vec3(x, 64.15, 164.9), false));
            velocity = velocity * AIR_FRICTION + AIR_INPUT;
            x += velocity;
            assertExact(ride(f, 270F, 0F, new Vec3(x, 64.0, 164.9), true));
            velocity = velocity * AIR_FRICTION + GROUND_INPUT;
            x += velocity;
            assertExact(ride(f, 270F, 0F, new Vec3(x, 64.0, 164.9), true));
            velocity = velocity * GROUND_FRICTION + GROUND_INPUT;
            x += velocity;
            assertExact(ride(f, 270F, 0F, new Vec3(x, 64.0, 164.9), true));
        }
    }

    /**
     * A horse on honey with its center over air at x 251. Honey's 0.9375-tall shape, inset 1/16, supports it from
     * x 252, so Entity#getBlockSpeedFactor reads air at its feet, then honey's 0.4 in the supporting block's column.
     * Each move adds the ridden input to the velocity scaled by honey's factor and the ground friction.
     */
    @Test
    void aHorseOnHoneyOverAGapKeepsHoneysSlowdown() throws Exception {
        try (var f = new JavaTickFixture()) {
            honeyFromX252(f);
            mount(f, new Vec3(251.9, HONEY_TOP, 160.5), 0F, new Vec3(0, GROUNDED_Y, 0));

            double velocity = 0.0;
            double z = 160.5;
            for (int tick = 0; tick < 4; tick++) {
                velocity = velocity * HONEY_SPEED_FACTOR * GROUND_FRICTION + GROUND_INPUT;
                z += velocity;
                assertExact(ride(f, 0F, 0F, new Vec3(251.9, HONEY_TOP, z)));
            }
        }
    }

    /**
     * Ticks seq 6043 (the step up from soul sand), 6114 and 6252. Stepping up makes the stone at z 168 the supporting
     * block, so its factor is 1.0.
     */
    @Test
    void aHorseSteppingFromSoulSandOntoGrassKeepsItsSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            ground(f, BlockIds.SOUL_SAND);
            Vec3 before = new Vec3(255.47316479028396, 63.875, 167.0035395179787);
            Vec3 start = new Vec3(255.50211240720833, 63.875, 167.18699414555957);
            // Its feet are inside the soul sand's cell, whose own 0.4 factor applies.
            mount(f, start, 351.44006F, carried(start.subtract(before), 0.4F));

            assertExact(ride(f, 355.06326F, 33.34872F, new Vec3(255.52093204776958, 64.0, 167.37169968434037)));
            assertExact(ride(f, 358.68658F, 33.48292F, new Vec3(255.534547781, 64.0, 167.61768845154788)));
            assertExact(ride(f, 359.0221F, 33.48292F, new Vec3(255.5444733085273, 64.0, 167.89715491232602)));
        }
    }

    /**
     * The captured y 63 layer: {@code slow} under x 252..256, z 164..167 and full blocks around it. Stone stands in
     * for the captured grass_block: both have friction 0.6 and speed factor 1.
     */
    private static void ground(JavaTickFixture f, BlockDefinition slow) {
        for (int chunkX = 14; chunkX <= 17; chunkX++) {
            for (int chunkZ = 9; chunkZ <= 11; chunkZ++) {
                f.player.compensatedWorld.ensureValidationChunkLoaded(chunkX, chunkZ);
            }
        }
        for (int x = 246; x <= 262; x++) {
            for (int z = 158; z <= 174; z++) {
                boolean underSlow = x >= 252 && x <= 256 && z >= 164 && z <= 167;
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y - 1, z), (underSlow ? slow : BlockIds.STONE).defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** Honey under x 252..262, z 158..174 and air west of it. */
    private static void honeyFromX252(JavaTickFixture f) {
        for (int chunkX = 14; chunkX <= 17; chunkX++) {
            for (int chunkZ = 9; chunkZ <= 11; chunkZ++) {
                f.player.compensatedWorld.ensureValidationChunkLoaded(chunkX, chunkZ);
            }
        }
        for (int x = 252; x <= 262; x++) {
            for (int z = 158; z <= 174; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y - 1, z), BlockIds.HONEY_BLOCK.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /**
     * The grounded horse's velocity after it moved {@code moved} unobstructed: Entity#move scales it by the block
     * speed factor, then LivingEntity#travelInAir by the ground friction.
     */
    private static Vec3 carried(Vec3 moved, float speedFactor) {
        return new Vec3(moved.x * speedFactor * GROUND_FRICTION, GROUNDED_Y, moved.z * speedFactor * GROUND_FRICTION);
    }

    private static void mount(JavaTickFixture f, Vec3 start, float yaw, Vec3 velocity) {
        mount(f, start, yaw, velocity, true);
    }

    /** The saddled horse at {@code start} carrying {@code velocity}, ridden with forward and sprint held. */
    private static void mount(JavaTickFixture f, Vec3 start, float yaw, Vec3 velocity, boolean onGround) {
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, start, yaw, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.movementSpeedAttribute = SPEED;
        horse.onGround = onGround;
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        // AbstractHorse#tick runs before LocalPlayer#rideTick hands it this tick's keys.
        f.input(FORWARD_SPRINT);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(onGround));
    }

    private static PredictionResult ride(JavaTickFixture f, float yaw, float pitch, Vec3 to) {
        return ride(f, yaw, pitch, to, true);
    }

    /** One client tick: the rider's Rot, the horse's move at half the pitch and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, float yaw, float pitch, Vec3 to, boolean onGround) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), yaw, pitch * 0.5F, onGround, true);
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
}
