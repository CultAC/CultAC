package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.RiddenWaterFloatVelocity;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaRiddenFloatVersionsTest {
    /** LivingEntity adds ridden floating in 1.21.11; both helper names add the promoted 0.04F after fluid travel. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void riddenFloatingUsesTheReleaseGateAndFloatLiteral(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(at, Vec3.ZERO);
            var context = f.tick(at, true).getSimulationContext();
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.HORSE, at, 0, 0, 0);
            JavaTickFixture.setField(
                    context.getClass(), context, "vehicle", f.player.compensatedEntities.getEntity(100));
            int water =
                    DataTables.defaults().registry().block("minecraft:water").defaultState();
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), water);
            var floating = ClientFluidQueries.floatWhileRidden(f.player, context, true, 1);
            boolean introduced = version.isNewerThanOrEquals(ClientVersion.V_1_21_11);
            assertEquals(introduced, floating);
            JavaTickFixture.setField(
                    context.getWorldData().getClass(), context.getWorldData(), "couldFloatWhileRidden", introduced);
            JavaTickFixture.setField(
                    context.getWorldData().getClass(), context.getWorldData(), "canFloatWhileRidden", introduced);
            Set<Vec3> actual = RiddenWaterFloatVelocity.apply(
                    f.player.checkManager.getSimulationProcessor().getLastPrediction(), Set.of(Vec3.ZERO));
            assertEquals(introduced ? Set.of(new Vec3(0, (double) 0.04F, 0)) : Set.of(Vec3.ZERO), actual);
        }
    }
}
