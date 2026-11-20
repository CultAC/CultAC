package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaMathSiblingCarryBoundTest {
    /**
     * Each tick keeps one other-table sibling, its travel from the selected travel's carried start, else the best by
     * candidate order, so the carried states stay bounded.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11"})
    void hiddenVelocityOnBothTablesKeepsTheCarryBounded(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int x = -8; x <= 8; x++) {
                for (int z = 1; z <= 8; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, 63, z), BlockIds.AIR.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
            Vec3 position = new Vec3(0.5, 64, 1.299999);
            f.place(position, new Vec3(0, -0.0784000015258789, 0.05));
            f.player.yRot = 3.7F;
            f.player.compensatedEntities.addEntity(102, EntityTypeIds.OAK_BOAT, position, 0, 0, 0);
            Vec3 boat = position.add(0, 0, -1.06);
            f.player.compensatedEntities.getEntity(102).setPositionRaw(
                    GetBoundingBox.getBoundingBoxFromPosAndSize(boat.x, boat.y, boat.z, 1.375F, 0.5625F));
            f.player.compensatedEntities.sentSelfPassengers(List.of(103), Integer.MAX_VALUE);
            f.input(new ServerboundPlayerInput(true, false, false, false, false, true, false));
            for (int tick = 0; tick < 16; tick++) {
                boolean vanilla = tick % 2 == 0;
                if (!vanilla) f.player.trigHandler.toggleShitMath();
                PredictionResult held;
                try {
                    held = f.tick(position, true);
                } finally {
                    if (!vanilla) f.player.trigHandler.toggleShitMath();
                }
                assertTrue(exact(held), "tick " + tick + " " + describe(held));
                var carry = assertInstanceOf(
                        JavaPredictionCarry.class,
                        f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry());
                assertTrue(carry.states().size() <= 8, "tick " + tick + " carried " + carry.states().size());
            }
        }
    }
}
