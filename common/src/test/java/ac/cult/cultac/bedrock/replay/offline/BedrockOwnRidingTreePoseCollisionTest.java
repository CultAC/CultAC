package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.Test;

/**
 * The pose and entity collision queries leave out the player's own riding tree by its root, as the movement's solid
 * actors do: a boat blocks a player standing in it, and not one riding a strider on a pig that boat carries.
 */
public class BedrockOwnRidingTreePoseCollisionTest {
    private static final int BOAT = 5901;
    private static final int PIG = 5902;
    private static final int STRIDER = 5903;
    private static final Vec3 AT = new Vec3(316.5, 120, -34.5);

    @Test
    public void aBoatOutsideThePlayersRidingTreeBlocksItsStandingPose() {
        withBoat(false, player -> {
            assertFalse(player.mayPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, AT.x, AT.y, AT.z));
            assertEquals(1, player.entityCollisionBoxes().size());
        });
    }

    @Test
    public void aBoatAtTheRootOfThePlayersRidingTreeDoesNotBlockItsStandingPose() {
        withBoat(true, player -> {
            assertSame(player.compensatedEntities.getEntity(BOAT), player.compensatedEntities.rootOfSelf());
            assertTrue(player.mayPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, AT.x, AT.y, AT.z));
            assertTrue(player.entityCollisionBoxes().isEmpty());
        });
    }

    private static void withBoat(boolean riding, java.util.function.Consumer<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.compensatedWorld.ensureValidationChunkLoaded(316 >> 4, -35 >> 4);
            var entities = player.compensatedEntities;
            // The boat's box crosses the standing box at head height.
            entities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, AT.add(0, 1.2, 0), 0, 0, 0);
            entities.getEntity(BOAT).bedrockCollidable = true;
            if (riding) {
                entities.addEntity(PIG, EntityTypeIds.PIG, AT, 0, 0, 0);
                entities.addEntity(STRIDER, EntityTypeIds.STRIDER, AT, 0, 0, 0);
                PacketEntity pig = entities.getEntity(PIG), strider = entities.getEntity(STRIDER);
                pig.mount(entities.getEntity(BOAT));
                strider.mount(pig);
                entities.getSelf().mount(strider);
            }
            player.x = player.lastX = AT.x;
            player.y = player.lastY = AT.y;
            player.z = player.lastZ = AT.z;
            test.accept(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }
}
