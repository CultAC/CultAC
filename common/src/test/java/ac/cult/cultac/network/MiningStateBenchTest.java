package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.WorldBookkeepingBenchTest.interleaved;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.ClientMiningState;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.LinkedHashMap;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The mining state, the eye query and the player boxes, each old form beside the new one, reps interleaved. CULT_BENCH=1. */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class MiningStateBenchTest {
    static volatile Object escape;

    @Test
    void miningAndBoxes() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_3);
            f.water(new BlockPos(-1, FLOOR_Y, -1), new BlockPos(1, FLOOR_Y + 2, 1));
            CultPlayer player = f.player;
            player.x = 4.3;
            player.y = FLOOR_Y + 0.25;
            player.z = 5.7;
            var inputs = new ClientMiningState.Inputs();
            var out = new SimpleCollisionBox();
            var body = new SimpleCollisionBox(0.2, FLOOR_Y, 0.2, 0.8, FLOOR_Y + 1.8, 0.8, false);
            var feet = new Vec3(0.5, FLOOR_Y, 0.5);
            var bodies = new LinkedHashMap<String, LongUnaryOperator>();
            bodies.put("mining state record and boxes", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) {
                    var state = ClientMiningStateEquivalenceTest.referenceState(player);
                    escape = state;
                    seen += state.haste();
                }
                return seen;
            });
            bodies.put("mining state primitives", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) seen += inputs.read(player).haste;
                return seen;
            });
            bodies.put("eye query copied box", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    if (ClientMiningStateEquivalenceTest.referenceEyesInWater(player, body, feet, FLOOR_Y + 0.5)) seen++;
                return seen;
            });
            bodies.put("eye query bounds", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    if (ClientFluidQueries.eyesInWater(player, body, feet, FLOOR_Y + 0.5)) seen++;
                return seen;
            });
            bodies.put("player box fresh", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) {
                    var box = GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
                    escape = box;
                    if (box.maxY > 0) seen++;
                }
                return seen;
            });
            bodies.put("player box into", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    if (GetBoundingBox.getCollisionBoxForPlayer(out, player, player.x, player.y, player.z).maxY > 0)
                        seen++;
                return seen;
            });
            interleaved(1, bodies);
        }
    }
}
