package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ScaffoldingBlock#getCollisionShape gives its stable top to a context above the block that is not descending, and
 * EntityCollisionContext takes descending from Entity#isDescending of the moving entity. A boat's is its own shared
 * crouching flag, so a rider's shift key leaves the boat resting on scaffolding.
 */
class BoatOnScaffoldingTest {
    private static final int BOAT = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, true);
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, true);

    @Test
    @DisplayName("a rider's shift leaves a boat resting on scaffolding")
    void aRidersShiftLeavesTheBoatOnScaffolding() throws Exception {
        try (var f = mount()) {
            for (int tick = 0; tick < 3; tick++) assertVehicleExact(f.rideTick(SEAT, true), tick);
            f.input(SHIFT);
            for (int tick = 3; tick < 6; tick++) assertVehicleExact(f.rideTick(SEAT, true), tick);
        }
    }

    @Test
    @DisplayName("a boat with its own crouching flag falls through scaffolding")
    void aCrouchingBoatFallsThroughScaffolding() throws Exception {
        try (var f = mount()) {
            assertVehicleExact(f.rideTick(SEAT, true), 0);
            f.player.compensatedEntities.getEntity(BOAT).actionSharedFlags = 2;
            // AbstractBoat#floatBoat adds the boat's 0.04F gravity to a resting velocity outside water.
            assertVehicleExact(f.rideTick(SEAT.add(0, -0.04F, 0), false), 1);
        }
    }

    private static JavaTickFixture mount() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1);
        var registry = DataTables.defaults().registry();
        int scaffolding =
                registry.with(registry.with(BlockIds.SCAFFOLDING.defaultState(), "distance", "0"), "bottom", "false");
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), scaffolding);
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.input(NONE);
        f.tickEnd();
        return f;
    }
}
