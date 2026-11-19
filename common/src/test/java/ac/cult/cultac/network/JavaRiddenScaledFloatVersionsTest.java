package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaRiddenScaledFloatVersionsTest {
    private static final int HORSE = 100;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    /**
     * A ridden adult horse at scale 0.1 rests 0.189 deep in a water source. Its eye height is 1.52F * 0.1F = 0.152, so
     * Entity#getFluidJumpThreshold returns 0, not 0.4, and from 1.21.11 LivingEntity's ridden-floating helper adds
     * 0.04F after travelInWater. The stationary tick then carries -0.005 + 0.04F, and the next move rises by it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aMountWhoseEyeIsBelowTheThresholdFloatsInShallowWater(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.WATER.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
            // A source's surface is at 8/9: the 0.16-tall box sits 0.188889 deep.
            Vec3 start = new Vec3(0.5, FLOOR_Y + 0.7, 0.5);
            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, start, 0, 0, 0);
            PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
            horse.hasSaddle = true;
            horse.isTame = true;
            horse.scale = 0.1F;
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            f.tickEnd();
            var processor = f.player.checkManager.getSimulationProcessor();
            processor.seedStartingVelocity(Vec3.ZERO);
            processor.setLastOnGround(DesyncStatus.FALSE);

            var result = ride(f, start);
            assertTrue(exact(result), release + " " + describe(result));
            // travelInWater: 0.8 drag, then getFluidFallingAdjustedMovement's gravity / 16.
            double waterY = 0.0 * 0.8 - 0.08 / 16.0;
            double floated = waterY + (double) 0.04F;
            boolean floats = version.isNewerThanOrEquals(ClientVersion.V_1_21_11);
            double expected = floats ? floated : waterY;
            double wrong = floats ? waterY : floated;
            var carried = processor.getValidPlayerStartingVels();
            assertTrue(
                    carried.stream().anyMatch(v -> Math.abs(v.y - expected) < 1e-12),
                    release + " expected " + expected + " in " + carried);
            assertFalse(
                    carried.stream().anyMatch(v -> Math.abs(v.y - wrong) < 1e-12),
                    release + " must not carry " + wrong + " in " + carried);

            // Through 1.21.4 LivingEntity#aiStep scales a client entity's velocity by 0.98 before travel.
            double rise = version.isOlderThan(ClientVersion.V_1_21_5) ? expected * 0.98 : expected;
            var next = ride(f, start.add(0, rise, 0));
            assertTrue(exact(next), release + " " + describe(next));
        }
    }

    /** One client tick: the rider's Rot, the horse's move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), 0, 0, false, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }
}
