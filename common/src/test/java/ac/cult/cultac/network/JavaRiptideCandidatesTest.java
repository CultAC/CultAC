package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JavaRiptideCandidatesTest {
    private static final Vec3 FALL = new Vec3(0, (0.0 - 0.08) * 0.98F, 0);

    /** handleKeybinds lifts the player above a low shulker before that shulker's earlier entity tick can push it. */
    @Test
    void theGroundedReleasePrecedesAnEarlierShulkersPeekMove() throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            entities.addEntity(100, EntityTypeIds.SHULKER, new Vec3(0.5, FLOOR_Y, 0.5), 0, 0, 0);
            entities.appendSelfToClientTickOrder();
            var shulker = (PacketEntityShulker) entities.getEntity(100);
            shulker.onShapeData(Direction.WEST, (byte) 0);
            Vec3 start = new Vec3(1.3, FLOOR_Y, 0.5);
            f.place(start, FALL);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            assertTrue(exact(f.tick(start, true)));
            release(f, start, true);
            f.player.yRot = -90;
            shulker.onShapeData(null, (byte) 30);
            var result = f.tick(start.add(0, 1.1999999F + 3 + FALL.y, 0), false);
            assertTrue(exact(result), describe(result));
        }
    }

    /** An unproven release retains both the no-spin and spin outcomes, each with its own exact travel world. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aPossibleReleaseCanSpinOrRemainDry(boolean grounded) throws Exception {
        for (boolean spins : new boolean[] {false, true}) {
            try (var f = new JavaTickFixture()) {
                Vec3 start = new Vec3(0.5, grounded ? FLOOR_Y : FLOOR_Y + 5, 0.5);
                release(f, start, false);
                Vec3 lift = new Vec3(0, spins && grounded ? 1.1999999F : 0, 0);
                Vec3 travel = spins ? FALL.add(0, 0, 3) : grounded ? Vec3.ZERO : FALL;
                var result = f.tick(start.add(lift).add(travel), grounded && !spins);
                assertTrue(exact(result), describe(result));
                assertEquals(spins ? 3 : 0, result.getSimulationContext().getRiptideLevel());
                assertEquals(lift.y, result.getSimulationContext().getPreTravelMovement().y, 1.0E-12);
                assertEquals(
                        false,
                        result.getSimulationContext().getWorldData().isInWater());
                assertEquals(
                        false,
                        result.getSimulationContext().getWorldData().isInLava());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aConfirmedReleaseCannotIgnoreThePush(boolean grounded) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(0.5, grounded ? FLOOR_Y : FLOOR_Y + 5, 0.5);
            release(f, start, true);
            var result = f.tick(grounded ? start : start.add(FALL), grounded);
            assertFalse(exact(result), describe(result));
        }
    }

    private static void release(JavaTickFixture f, Vec3 at, boolean certain) {
        f.place(at, FALL);
        f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.player.packetStateData.riptideLevel = 3;
        f.player.packetStateData.riptideReleaseCertain = certain;
        f.player.riptideSpinAttackTicks = 20;
    }
}
