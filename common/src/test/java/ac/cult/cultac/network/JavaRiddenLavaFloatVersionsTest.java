package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
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

class JavaRiddenLavaFloatVersionsTest {
    private static final int HORSE = 100;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    /**
     * A ridden horse coasts out of lava, across a structure void, into a water source. Entity#baseTick saw lava only,
     * so LivingEntity#travelInFluid runs travelInLava, and its Entity#move's LivingEntity#checkFallDamage refreshes
     * the fluid interaction at the post-move box, which now reaches 0.889 deep into the water. From 26.3
     * floatInLiquidWhileRidden(ENTITY_FLOATABLE) follows travelInLava and reads that refreshed height; earlier releases
     * float only after travelInWater.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aLavaTravelFloatsOnTheWaterItsMoveReached(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            int voidState = DataTables.defaults()
                    .registry()
                    .block("minecraft:structure_void")
                    .defaultState();
            for (int z = -2; z <= 2; z++) {
                for (int x = -3; x <= 0; x++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.LAVA.defaultState());
                f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, z), voidState);
                for (int x = 2; x <= 4; x++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.WATER.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            // The 1.3965-wide box reaches x 1.89825 in the lava, then past x 2.19 into the water.
            Vec3 start = new Vec3(1.2, FLOOR_Y, 0.5);
            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, start, 0, 0, 0);
            PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
            horse.hasSaddle = true;
            horse.isTame = true;
            horse.onGround = true;
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            f.tickEnd();
            var processor = f.player.checkManager.getSimulationProcessor();
            processor.seedStartingVelocity(new Vec3(0.3, -0.0784000015258789, 0));
            processor.setLastOnGround(DesyncStatus.TRUE);

            // Through 1.21.4 LivingEntity#aiStep scales a client entity's velocity by 0.98 before travel.
            double coast = version.isOlderThan(ClientVersion.V_1_21_5) ? 0.3 * 0.98 : 0.3;
            var result = ride(f, start.add(coast, 0, 0));
            assertTrue(exact(result), release + " " + describe(result));
            // travelInLava: the deep-lava 0.5 drag, then gravity / 4.
            double lavaY = 0.0 * 0.5 - 0.08 / 4.0;
            double floated = lavaY + (double) 0.04F;
            boolean floats = version.isNewerThanOrEquals(ClientVersion.V_26_3);
            var carried = processor.getValidPlayerStartingVels();
            double expected = floats ? floated : lavaY;
            double wrong = floats ? lavaY : floated;
            assertTrue(
                    carried.stream().anyMatch(v -> Math.abs(v.y - expected) < 1e-12),
                    release + " expected " + expected + " in " + carried);
            assertFalse(
                    carried.stream().anyMatch(v -> Math.abs(v.y - wrong) < 1e-12),
                    release + " must not carry " + wrong + " in " + carried);
        }
    }

    /** One client tick: the rider's Rot, the horse's move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), 0, 0, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }
}
