package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.ClientFluidHeights;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** A strider the rider stops steering still runs Strider#floatStrider each client tick it spends in lava. */
class JavaStriderPassiveFloatTest {
    private static final int STRIDER = 100;
    private static final float AIR_INPUT = (float) ((double) 0.175F * 0.55F) * 0.1F;

    /**
     * flag-30: the rider scrolls off the warped fungus on a stick for one tick in deep lava, then back. The passive
     * Strider#tick decays the delta movement in LivingEntity#aiStep and then buoys it, so the regained travel rises.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPassiveTickInLavaBuoysTheStriderBeforeControlReturns(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    for (int y = FLOOR_Y; y <= FLOOR_Y + 4; y++)
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.LAVA.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 0.75D, 0.5);
            f.place(position, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(STRIDER, EntityTypeIds.STRIDER, position, 0, 0, 0);
            var strider = (PacketEntityStrider) f.player.compensatedEntities.getEntity(STRIDER);
            strider.hasSaddle = true;
            strider.commitFluidHeights(ClientFluidHeights.capture(f.player,
                    GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, 0.9F, 1.7F)));
            f.player.getInventory().inventory.setHeldItem(
                    ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
            f.setPassengers(STRIDER, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            strider.onGround = false;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
            Vec3 velocity = new Vec3(0, 0.02D, 0);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);

            for (int tick = 0; tick < 3; tick++) {
                Vec3 movement = controlledMove(version, velocity);
                position = position.add(movement);
                var result = f.rideTick(position, false);
                assertTrue(exact(result), release + " controlled tick " + tick + ": " + describe(result));
                velocity = new Vec3(movement.x * 0.91F, (movement.y - 0.08D) * 0.98F, movement.z * 0.91F)
                        .scale(0.5D)
                        .add(0, 0.05D, 0);
            }

            // A scroll between ticks: MultiPlayerGameMode#tick reports the slot before the passive tick.
            select(f, 1);
            f.rideTick();
            // LivingEntity#aiStep: not interpolating and not simulated, so 0.98 then the 0.003 threshold.
            Vec3 decayed = threshold(velocity.scale(0.98D));
            // Strider#tick -> floatStrider: still under the lava surface, so halve and add 0.05Y.
            velocity = decayed.scale(0.5D).add(0, 0.05D, 0);
            assertEquals(Set.of(velocity), f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels(),
                    release);
            assertEquals(velocity, strider.deltaMovement, release);

            select(f, 0);
            position = position.add(controlledMove(version, velocity));
            var regained = f.rideTick(position, false);
            assertTrue(exact(regained), release + " regained: " + describe(regained));
        }
    }

    /** A strider standing above a lava surface it cannot sink into is grounded by floatStrider, not buoyed. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPassiveTickAboveTheLavaSurfaceGroundsTheStrider(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.LAVA.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            // Above the lava, sinking; the controlled tick ends above the block's 0.5 column with air above it.
            Vec3 position = new Vec3(0.5, FLOOR_Y + 0.95D, 0.5);
            f.place(position, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(STRIDER, EntityTypeIds.STRIDER, position, 0, 0, 0);
            var strider = (PacketEntityStrider) f.player.compensatedEntities.getEntity(STRIDER);
            strider.hasSaddle = true;
            strider.commitFluidHeights(ClientFluidHeights.capture(f.player,
                    GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, 0.9F, 1.7F)));
            f.player.getInventory().inventory.setHeldItem(
                    ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
            f.setPassengers(STRIDER, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            strider.onGround = false;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
            Vec3 velocity = new Vec3(0, -0.1D, 0);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);

            Vec3 movement = controlledMove(version, velocity);
            position = position.add(movement);
            // Before 26.1 Strider#floatStrider reads the start's dry fluid cache; from 26.1 the move's
            // checkFallDamage refreshes it, so floatStrider already grounds the strider on this tick.
            boolean groundedWhileControlled = version.isNewerThanOrEquals(ClientVersion.V_26_1);
            var controlled = f.rideTick(position, groundedWhileControlled);
            assertTrue(exact(controlled), release + " controlled: " + describe(controlled));
            velocity = new Vec3(movement.x * 0.91F, (movement.y - 0.08D) * 0.98F, movement.z * 0.91F);
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
            strider.onGround = false;

            select(f, 1);
            f.rideTick();
            // In lava after the passive Entity#baseTick, above the surface: grounded, the velocity only decayed.
            velocity = threshold(velocity.scale(0.98D));
            assertEquals(Set.of(velocity), f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels(),
                    release);
            assertTrue(strider.onGround, release);
            assertEquals(DesyncStatus.TRUE, f.player.checkManager.getSimulationProcessor().getLastOnGround(), release);
        }
    }

    private static Vec3 controlledMove(ClientVersion version, Vec3 velocity) {
        // Before 1.21.5 LivingEntity#aiStep also drags a ridden root by 0.98 (!isEffectiveAi).
        if (version.isOlderThan(ClientVersion.V_1_21_5)) velocity = velocity.scale(0.98D);
        return threshold(velocity).add(0, 0, AIR_INPUT);
    }

    private static Vec3 threshold(Vec3 velocity) {
        return new Vec3(
                Math.abs(velocity.x) < 0.003D ? 0 : velocity.x,
                Math.abs(velocity.y) < 0.003D ? 0 : velocity.y,
                Math.abs(velocity.z) < 0.003D ? 0 : velocity.z);
    }

    private static void select(JavaTickFixture f, int slot) {
        var packet = new ServerboundSetCarriedItem(slot);
        f.listener.onSetCarriedItem(f.event(ServerboundPackets.SET_CARRIED_ITEM, packet), f.player, packet);
    }
}
