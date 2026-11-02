package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** A pig the rider stops steering still takes Entity#baseTick's water current each client tick it spends in water. */
class JavaPigPassiveCurrentTest {
    private static final int PIG = 100;
    /** LivingEntity#travelInWater: moveRelative's 0.02F along the rider's yaw of 0, towards +Z. */
    private static final float WATER_INPUT = 0.02F;
    /** Pig#getWaterSlowDown. */
    private static final float WATER_FRICTION = 0.8F;
    /** EntityFluidInteraction normalizes a non-player's current, then scales water's by 0.014: here along +X. */
    private static final Vec3 CURRENT = new Vec3(0.014D, 0, 0);
    /** LivingEntity#getFluidFallingAdjustedMovement after the floor stopped the fall: gravity over 16. */
    private static final double FLOOR_FALL = -0.08D / 16.0D;

    /**
     * The rider scrolls off the carrot on a stick for one tick in flowing water, then back. The passive tick's
     * Entity#baseTick adds the current before LivingEntity#aiStep decays the delta movement, so the regained travel
     * keeps it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPassiveTickInFlowingWaterKeepsTheCurrentBeforeControlReturns(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var registry = DataTables.defaults().registry();
            // One-deep water whose level rises towards +X, so every block flows along +X alone.
            for (int x = -8; x <= 8; x++) {
                String level = Integer.toString(Math.max(1, Math.min(7, x + 4)));
                int water = registry.with(BlockIds.WATER.defaultState(), "level", level);
                for (int z = -8; z <= 8; z++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), water);
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.player.xRot = 0.0F;
            f.player.compensatedEntities.addEntity(PIG, EntityTypeIds.PIG, position, 0, 0, 0);
            var pig = (PacketEntityRideable) f.player.compensatedEntities.getEntity(PIG);
            pig.hasSaddle = true;
            f.player.getInventory().inventory.setHeldItem(
                    ItemUtil.modelItems().stack("minecraft:carrot_on_a_stick", 1));
            f.setPassengers(PIG, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            Vec3 velocity = new Vec3(0, FLOOR_FALL, 0);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);

            for (int tick = 0; tick < 3; tick++) {
                Vec3 movement = controlledMove(version, velocity);
                position = position.add(movement);
                var result = f.rideTick(position, true);
                assertTrue(exact(result), release + " controlled tick " + tick + ": " + describe(result));
                velocity = new Vec3(movement.x * WATER_FRICTION, FLOOR_FALL, movement.z * WATER_FRICTION);
            }

            // A scroll between ticks: MultiPlayerGameMode#tick reports the slot before the passive tick.
            select(f, 1);
            f.rideTick();
            // Entity#baseTick's current, then LivingEntity#aiStep: not interpolating and not simulated, so 0.98 and
            // the 0.003 threshold.
            velocity = threshold(velocity.add(CURRENT).scale(0.98D));
            assertEquals(
                    Set.of(velocity),
                    f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels(),
                    release);
            assertEquals(velocity, pig.deltaMovement, release);

            select(f, 0);
            position = position.add(controlledMove(version, velocity));
            var regained = f.rideTick(position, true);
            assertTrue(exact(regained), release + " regained: " + describe(regained));
        }
    }

    /** A steered tick on the floor: the base-tick current, aiStep, the water input, and the floor clips the fall. */
    private static Vec3 controlledMove(ClientVersion version, Vec3 velocity) {
        velocity = velocity.add(CURRENT);
        // Before 1.21.5 LivingEntity#aiStep also drags a ridden root by 0.98 (!isEffectiveAi).
        if (version.isOlderThan(ClientVersion.V_1_21_5)) velocity = velocity.scale(0.98D);
        Vec3 travel = threshold(velocity).add(0, 0, WATER_INPUT);
        return new Vec3(travel.x, 0, travel.z);
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
