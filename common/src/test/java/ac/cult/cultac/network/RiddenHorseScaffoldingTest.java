package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A horse standing on scaffolding. Entity#collide uses CollisionContext.of(horse), whose isDescending is the horse's
 * own Entity#isShiftKeyDown (shared flag 1). The rider's shift key, LocalPlayer#isShiftKeyDown, belongs only to the
 * player's own context, so a rider holding shift to dismount leaves ScaffoldingBlock's stable shape under the horse.
 */
class RiddenHorseScaffoldingTest {
    private static final int HORSE = 100;
    private static final Vec3 START = new Vec3(0.5D, FLOOR_Y + 1.0D, 0.5D);
    /** LivingEntity#travelInAir: a grounded horse's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;

    private static final ServerboundPlayerInput IDLE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);

    @Test
    void aRiderHoldingShiftLeavesTheHorseStandingOnScaffolding() throws Exception {
        try (var f = new JavaTickFixture()) {
            scaffolding(f);
            mount(f);
            f.input(IDLE);
            assertVehicleExact(f.rideTick(START, true), 0);
            f.input(SHIFT);
            assertVehicleExact(f.rideTick(START, true), 1);
        }
    }

    /** A horse whose own shared flag 1 is set descends: ScaffoldingBlock gives it no collision. */
    @Test
    void aHorseWithItsOwnShiftFlagDescendsThroughScaffolding() throws Exception {
        try (var f = new JavaTickFixture()) {
            scaffolding(f);
            mount(f).actionSharedFlags = 2;
            f.input(IDLE);
            assertVehicleExact(f.rideTick(START.add(0.0D, GROUNDED_Y, 0.0D), false), 0);
        }
    }

    /** Scaffolding resting on the fixture's stone floor: distance 0, not a bottom piece. */
    private static void scaffolding(JavaTickFixture f) {
        var registry = DataTables.defaults().registry();
        int state = registry.with(BlockIds.SCAFFOLDING.defaultState(), "distance", "0");
        state = registry.with(state, "bottom", "false");
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), state);
        }
    }

    /** A saddled horse grounded at {@link #START} with the player riding it. */
    private static PacketEntityHorse mount(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, START, 0, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.onGround = true;
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0.0D, GROUNDED_Y, 0.0D));
        return horse;
    }
}
