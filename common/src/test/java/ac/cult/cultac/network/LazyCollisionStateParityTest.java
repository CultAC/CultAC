package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTags;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Collisions#getCollisionBoxes without an actor equals the scan given JavaCollisionState#current up front. */
class LazyCollisionStateParityTest {
    private static final BlockDefinition[] BLOCKS = {
        BlockIds.POWDER_SNOW, BlockIds.STONE, BlockIds.SCAFFOLDING, BlockIds.SNOW, BlockIds.LADDER, BlockIds.SOUL_SAND
    };

    @Test
    void lazyActorMatchesEagerActor() throws Exception {
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            int y0 = JavaTickFixture.FLOOR_Y;
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    BlockDefinition block = BLOCKS[Math.floorMod(x * 7 + z * 3, BLOCKS.length)];
                    player.compensatedWorld.updateBlock(new BlockPos(x, y0, z), block.defaultState());
                    if ((x + z) % 2 == 0)
                        player.compensatedWorld.updateBlock(
                                new BlockPos(x, y0 + 1, z), BlockIds.POWDER_SNOW.defaultState());
                }
            }
            int walker = -1;
            for (EntityTypes.Type type : EntityTypes.defaults().types())
                if (EntityTags.POWDER_SNOW_WALKABLE_MOBS.test(type.id())) walker = type.id();
            assertTrue(walker >= 0);
            player.compensatedEntities.addEntity(900, walker, new Vec3(0.5, y0 + 2, 0.5), 0, 0, 0);

            int compared = 0;
            int actorMattered = 0;
            for (int state = 0; state < 4; state++) {
                player.isSneaking = (state & 1) != 0;
                if ((state & 2) != 0)
                    player.compensatedEntities.getSelf().mount(player.compensatedEntities.getEntity(900));
                else player.compensatedEntities.getSelf().eject();
                for (double bottom : new double[] {y0 + 0.5, y0 + 1.0, y0 + 2.0, y0 + 2.5, Double.NaN}) {
                    for (double qx = -3.2; qx <= 3.2; qx += 0.7) {
                        for (double qy = y0 - 0.4; qy <= y0 + 2.2; qy += 0.45) {
                            SimpleCollisionBox query =
                                    new SimpleCollisionBox(qx, qy, qx * 0.5, qx + 0.6, qy + 1.8, qx * 0.5 + 0.6);
                            for (boolean onlyCheck : new boolean[] {false, true}) {
                                List<SimpleCollisionBox> eager = onlyCheck ? null : new ArrayList<>();
                                List<SimpleCollisionBox> lazy = onlyCheck ? null : new ArrayList<>();
                                boolean eagerHit = Collisions.getCollisionBoxes(
                                        player,
                                        query.copy(),
                                        eager,
                                        onlyCheck,
                                        bottom,
                                        JavaCollisionState.current(player));
                                boolean lazyHit =
                                        Collisions.getCollisionBoxes(player, query.copy(), lazy, onlyCheck, bottom);
                                String where = "state=" + state + " bottom=" + bottom + " query=" + query;
                                assertEquals(eagerHit, lazyHit, where);
                                assertEquals(String.valueOf(eager), String.valueOf(lazy), where);
                                compared++;
                                if (!onlyCheck) {
                                    List<SimpleCollisionBox> none = new ArrayList<>();
                                    Collisions.getCollisionBoxes(player, query.copy(), none, false, bottom, null);
                                    if (!String.valueOf(none).equals(String.valueOf(eager))) actorMattered++;
                                }
                            }
                        }
                    }
                }
            }
            assertTrue(compared > 1000);
            assertTrue(actorMattered > 0, "no query depended on the actor");
        }
    }
}
