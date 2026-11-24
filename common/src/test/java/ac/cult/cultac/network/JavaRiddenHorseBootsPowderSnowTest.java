package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A ridden horse wearing leather boots on powder snow level with the ground (CultAC issue #243).
 * PowderSnowBlock#canEntityWalkOnPowderSnow reads the FEET slot of any living entity, which the client holds from the
 * server's set_equipment, so the snow gives the booted horse a full block's collision and it walks on top. Without
 * boots it sinks into the snow.
 */
class JavaRiddenHorseBootsPowderSnowTest {
    private static final int HORSE = 100;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final float SPEED = 0.225F;
    /** LivingEntity#travelInAir on 0.6-friction ground; powder snow keeps the default friction. */
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    /** LivingEntity#travelInAir: a grounded horse's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;
    /** LivingEntity#moveRelative for the ridden input's 0.98F along +z on 0.6-friction ground. */
    private static final double GROUND_INPUT = (double) 0.98F * (SPEED * (0.21600002F / (0.6F * 0.6F * 0.6F)));

    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    /** The booted horse rides across the snow at y 64, on the ground, as the captured client did. */
    @Test
    void aBootedHorseWalksOnPowderSnow() throws Exception {
        try (var f = new JavaTickFixture()) {
            snowPit(f);
            mount(f);
            f.equip(HORSE, EquipmentSlot.FEET, OfflineCultTestBootstrap.item("minecraft:leather_boots"));

            double velocity = 0.0;
            double z = START.z;
            for (int tick = 0; tick < 4; tick++) {
                velocity = velocity * GROUND_FRICTION + GROUND_INPUT;
                z += velocity;
                assertVehicleExact(ride(f, new Vec3(START.x, FLOOR_Y, z), true), tick);
            }
        }
    }

    /**
     * Once set_equipment empties the FEET slot, the snow is empty to the horse: its next move sinks by the grounded
     * velocity, and staying on top flags.
     */
    @Test
    void aHorseWhoseBootsAreTakenSinksIntoPowderSnow() throws Exception {
        for (boolean sinks : new boolean[] {true, false}) {
            try (var f = new JavaTickFixture()) {
                snowPit(f);
                mount(f);
                f.equip(HORSE, EquipmentSlot.FEET, OfflineCultTestBootstrap.item("minecraft:leather_boots"));
                double velocity = GROUND_INPUT;
                double z = START.z + velocity;
                assertVehicleExact(ride(f, new Vec3(START.x, FLOOR_Y, z), true), 0);

                f.equip(HORSE, EquipmentSlot.FEET, SimItemStack.EMPTY);
                z += velocity * GROUND_FRICTION + GROUND_INPUT;
                if (sinks) {
                    assertVehicleExact(ride(f, new Vec3(START.x, FLOOR_Y + GROUNDED_Y, z), false), 1);
                } else {
                    var onTop = ride(f, new Vec3(START.x, FLOOR_Y, z), true);
                    assertFalse(exact(onTop), describe(onTop));
                }
            }
        }
    }

    /** Powder snow on the y 63 layer over x -3..3, z -3..8, with stone under it. */
    private static void snowPit(JavaTickFixture f) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 2, z), BlockIds.STONE.defaultState());
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y - 1, z), BlockIds.POWDER_SNOW.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
    }

    /** The saddled horse at rest on the snow at {@link #START}, facing +z, ridden with forward held. */
    private static void mount(JavaTickFixture f) {
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, START, 0, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.movementSpeedAttribute = SPEED;
        horse.onGround = true;
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        // AbstractHorse#tick runs before LocalPlayer#rideTick hands it this tick's keys.
        f.input(FORWARD);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, GROUNDED_Y, 0));
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    /** One client tick: the rider's Rot, the horse's move and the tick end. */
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
}
