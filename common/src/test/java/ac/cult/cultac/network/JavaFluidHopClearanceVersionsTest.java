package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.math.Vec3;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JavaFluidHopClearanceVersionsTest {
    static Stream<Arguments> cases() {
        return JavaPistonReleaseCases.releases()
                .flatMap(a -> Stream.of(
                                "clear",
                                "liquid",
                                "ceiling",
                                "edge_liquid",
                                "touching_entity",
                                "touching_ghast",
                                "touching_still_ghast")
                        .filter(kind -> !kind.endsWith("ghast")
                                || ((ClientVersion) a.get()[1]).isNewerThanOrEquals(ClientVersion.V_1_21_6))
                        .map(kind -> Arguments.of(a.get()[0], a.get()[1], kind)));
    }

    /** LivingEntity's jumpOutOfFluid requires Entity#isFree: no collision and no liquid in the offset box. */
    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("cases")
    void aFluidHopUsesItsOwnMovesHorizontalCollisionAndClearance(String release, ClientVersion version, String kind)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            int water = BlockIds.WATER.defaultState();
            for (int x = -1; x <= 0; x++)
                for (int z = -1; z <= 1; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), water);
                    if (kind.equals("liquid"))
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 1, z), water);
                    if (kind.equals("ceiling"))
                        f.player.compensatedWorld.updateBlock(
                                new BlockPos(x, FLOOR_Y + 3, z), BlockIds.STONE.defaultState());
                }
            for (int z = -1; z <= 1; z++)
                f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, z), BlockIds.STONE.defaultState());
            // The offset box's maxX is exactly 1. 26.3's BlockMatcher includes that cell; older ceil loops exclude it.
            if (kind.equals("edge_liquid"))
                f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y + 1, 0), water);
            // A shulker above the wall, its minX face exactly at the offset box's maxX. getEntityCollisions inflates
            // the box by 1.0E-7, so an entity only touching it still blocks the hop.
            if (kind.equals("touching_entity"))
                f.player.compensatedEntities.addEntity(
                        100, EntityTypeIds.SHULKER, new Vec3(1.5, FLOOR_Y + 1, 0.5), 0, 0, 0);
            // HappyGhast#canBeCollidedWith admits a player below the ghast's top only while the ghast stays still.
            if (kind.endsWith("ghast")) {
                f.player.compensatedEntities.addEntity(
                        100, EntityTypeIds.HAPPY_GHAST, new Vec3(3, FLOOR_Y + 1, 0.5), 0, 0, 0);
                ((PacketEntityHappyGhast) f.player.compensatedEntities.getEntity(100)).staysStill =
                        kind.equals("touching_still_ghast");
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.place(new Vec3(0.69, FLOOR_Y + 0.5, 0.5), new Vec3(0.05, 0.14, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var moved = f.tick(new Vec3(1 - (double) (0.6F / 2), FLOOR_Y + 0.5 + 0.14, 0.5), false);
            assertTrue(exact(moved), describe(moved));
            var carry = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            boolean hop = kind.equals("clear")
                    || kind.equals("touching_ghast")
                    || kind.equals("edge_liquid") && version.isOlderThan(ClientVersion.V_26_3);
            assertEquals(hop, carry.stream().anyMatch(v -> v.y == (double) 0.3F), carry.toString());
            assertTrue(hop || carry.stream().noneMatch(v -> v.y > 0.29), carry.toString());
        }
    }
}
