package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 26.3 client rides a nautilus against a climbable. AbstractNautilus keeps LivingEntity#onClimbable, so out of water
 * LivingEntity#travelInAir runs LivingEntity#handleOnClimbable, which holds the fall at 0.15F, and
 * LivingEntity#handleRelativeFrictionAndCalculateMovement sets 0.2 after a horizontal collision. In water
 * AbstractNautilus#travelInWater replaces LivingEntity#travelInWater, which has the only fluid climbing 0.2.
 */
class JavaRiddenClimbTest {
    private static final int VEHICLE = 100;
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, true);
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, true);
    // (0.2 - 0.08) * 0.98F: the climbing tick's 0.2 after LivingEntity#travelInAir's gravity and drag.
    private static final Vec3 CLIMBING = new Vec3(0, 0.11760000228882461, 0);
    private static final Vec3 SEAT = at(219.5625, 66.3485466190203, 201.55334596279184);
    private static final Vec3[] RIDE = {
        at(219.5625, 66.4660207264139, 201.55330080395333),
        at(219.5625, 66.58362072870273, 201.55330080395333),
        at(219.5625, 66.62046873166292, 201.55330080395333),
        at(219.5625, 66.57817977374087, 201.55330080395333),
        at(219.5625, 66.45833659264478, 201.55330080395333),
        at(219.5625, 66.30833658668432, 201.55330080395333),
        at(219.5625, 66.15833658072386, 201.55330080395333),
        at(219.5625, 66.00833657476339, 201.55330080395333)
    };
    // Against the north-facing ladder's plate at z = 13/16, the nautilus being 0.875 wide, a block over the floor.
    private static final Vec3 AT_LADDER = new Vec3(0.5, FLOOR_Y + 1, 0.8125 - 0.4375);

    /**
     * The last climbing tick into the wall, the 0.2 it leaves, then the fall the vine holds at 0.15F. Positions come
     * from a 26.3 client's capture, moved next to the fixture's floor.
     */
    @Test
    void aNautilusClimbsAndSlidesDownAVine() throws Exception {
        try (var f = onAVine()) {
            for (int tick = 0; tick < RIDE.length; tick++) {
                assertVehicleExact(f.rideTick(RIDE[tick], false), tick);
            }
        }
    }

    /**
     * LivingEntity#handleOnClimbable stops the slide only for a Player, so the nautilus slides at 0.15F while its rider
     * holds shift; LocalPlayer#sendChanges sends that player_input before the tick's move_vehicle.
     */
    @Test
    void aNautilusSlidesDownAVineWhileItsRiderSneaks() throws Exception {
        try (var f = onAVine()) {
            for (int tick = 0; tick < RIDE.length; tick++) {
                if (tick == 5) f.input(SHIFT);
                assertVehicleExact(f.rideTick(RIDE[tick], false), tick);
            }
        }
    }

    /**
     * A nautilus driven into a waterlogged ladder stays where the plate stops it: AbstractNautilus#travelInWater applies
     * no gravity.
     */
    @Test
    void aNautilusInWaterStaysAtALadder() throws Exception {
        try (var f = inWaterAtALadder()) {
            for (int tick = 0; tick < 2; tick++) {
                assertVehicleExact(f.rideTick(AT_LADDER, false), tick);
            }
        }
    }

    @Test
    void aNautilusInWaterDoesNotClimbALadder() throws Exception {
        try (var f = inWaterAtALadder()) {
            assertVehicleExact(f.rideTick(AT_LADDER, false), 0);
            PredictionResult climbed = f.rideTick(AT_LADDER.add(0, 0.1, 0), false);
            assertEquals(0.1, climbed.getOffset(), 1.0E-5, describe(climbed));
        }
    }

    /** The capture's vine against a stone wall, the saddled nautilus leaving its last climbing tick into it. */
    private static JavaTickFixture onAVine() throws Exception {
        var f = new JavaTickFixture();
        int vine = BlockProps.EAST.with(BlockIds.VINE.defaultState(), true);
        for (int y = FLOOR_Y; y < FLOOR_Y + 4; y++) {
            for (int z = -1; z <= 1; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, z), vine);
                f.player.compensatedWorld.updateBlock(new BlockPos(1, y, z), BlockIds.STONE.defaultState());
            }
        }
        mount(f, SEAT, -91.29712F, 3.6133614F, CLIMBING);
        f.input(NONE);
        return f;
    }

    /** A saddled nautilus in water with a waterlogged ladder ahead of it, driven forward from rest. */
    private static JavaTickFixture inWaterAtALadder() throws Exception {
        var f = new JavaTickFixture();
        f.water(new BlockPos(-2, FLOOR_Y, -2), new BlockPos(2, FLOOR_Y + 3, 2));
        int ladder = BlockProps.WATERLOGGED.with(BlockIds.LADDER.defaultState(), true);
        for (int y = FLOOR_Y; y < FLOOR_Y + 4; y++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), ladder);
        }
        mount(f, AT_LADDER, 0.0F, 0.0F, Vec3.ZERO);
        return f;
    }

    /** Seats the player on a saddled nautilus at {@code seat} holding forward, the nautilus moving at {@code motion}. */
    private static void mount(JavaTickFixture f, Vec3 seat, float yaw, float pitch, Vec3 motion) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.xRot = f.player.lastTickXRot = yaw;
        f.player.yRot = f.player.lastTickYRot = pitch;
        f.place(seat.add(0, 1, 0), Vec3.ZERO);
        f.player.compensatedEntities.addEntity(
                VEHICLE, EntityTypes.defaults().byKey("minecraft:nautilus").id(), seat, 0, yaw, 0);
        var nautilus = (PacketEntityNautilus) f.player.compensatedEntities.getEntity(VEHICLE);
        nautilus.hasSaddle = true;
        nautilus.isTame = true;
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(FORWARD);
        f.player.boatData.useNextInputForBoatTick();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(motion);
    }

    private static Vec3 at(double x, double y, double z) {
        return new Vec3(x - 219, y, z - 201);
    }
}
