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
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Before 1.21.4 (1.21.2 and 1.21.3 share a protocol) a passenger keeps its last ground state, so the first Entity#rideTick after mounting from the ground
 * can run LivingEntity#jumpFromGround. Its LivingEntity#getJumpPower scales by Entity#getBlockJumpFactor, which reads
 * the block at Entity#blockPosition and then Entity#getBlockPosBelowThatAffectsMyMovement, 0.500001 below the feet.
 * With no dismount resync written, the first free move starts from the velocity that ride tick left.
 */
class JavaPassengerJumpFactorTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final int VEHICLE = 100;
    /** LivingEntity#travelInAir's gravity and drag on a standing player, which its next move collides away. */
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    /** On a bottom slab at the floor, above the block the floor normally has. */
    private static final Vec3 ON_SLAB = new Vec3(0.5, FLOOR_Y + 0.5, 0.5);

    @Test
    void honeyUnderTheSlabHalvesTheRideTickJump() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_2);
                var ignored = JavaTickFixture.observeOnly()) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), BlockIds.HONEY_BLOCK.defaultState());
            jumpAndDismount(f);
            // LivingEntity#getJumpPower: 0.42 by the honey's 0.5 jump factor, then gravity and the 0.98 drag.
            double rise = (0.42F * 0.5F - 0.08) * 0.98F;
            PredictionResult move = f.tick(position(f).add(0, rise, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    @Test
    void stoneUnderTheSlabLeavesTheFullRideTickJump() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_2);
                var ignored = JavaTickFixture.observeOnly()) {
            jumpAndDismount(f);
            double rise = (0.42F - 0.08) * 0.98F;
            PredictionResult move = f.tick(position(f).add(0, rise, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        var registry = DataTables.defaults().registry();
        int slab = registry.with(registry.block("minecraft:stone_slab").defaultState(), "type", "bottom");
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), slab);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.input(NONE);
        return f;
    }

    /** Stands on the slab, mounts, rides one tick with the jump key held, and is dismounted without a resync. */
    private static void jumpAndDismount(JavaTickFixture f) {
        f.place(ON_SLAB, GROUNDED);
        f.player.onGround = f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        PredictionResult standing = f.tick(ON_SLAB, true);
        assertTrue(exact(standing), describe(standing));
        f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.ARMOR_STAND, ON_SLAB, 0, 0, 0);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        f.input(JUMP);
        f.rideTick();
        f.setPassengers(VEHICLE, List.of());
        f.pongThrough(f.player.lastTransactionSent.get());
        assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
        f.input(NONE);
    }

    private static Vec3 position(JavaTickFixture f) {
        return new Vec3(f.player.x, f.player.y, f.player.z);
    }
}
