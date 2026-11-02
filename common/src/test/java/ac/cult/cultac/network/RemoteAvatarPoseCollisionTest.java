package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPositionWorlds;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AbstractBoat#canCollideWith admits a pushable remote player, and EntityGetter#getEntityCollisions gives its bounding
 * box, which Avatar#getDefaultDimensions sizes by its pose: 1.5 tall crouching, 1.8 standing.
 */
class RemoteAvatarPoseCollisionTest {
    private static final Vec3 REMOTE = new Vec3(0.5, FLOOR_Y, 0.5);
    /** A boat ending 0.1125 short of the remote player's west face, its bottom between 1.5 and 1.8 above its feet. */
    private static final SimpleCollisionBox BOAT =
            new SimpleCollisionBox(-1.2875, FLOOR_Y + 1.6, -0.1875, 0.0875, FLOOR_Y + 1.6 + 0.5625, 1.1875);

    private static final Vec3 MOVE = new Vec3(0.2, -0.04, 0);

    @Test
    void aCrouchingRemotePlayerLetsABoatPassAboveIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity boat = setUp(f, EntityPose.CROUCHING);
            List<SimpleCollisionBox> colliders = colliders(f, boat);

            assertTrue(colliders.isEmpty(), colliders.toString());
            assertEquals(MOVE.x, collide(f, boat, colliders).x, 0);
        }
    }

    @Test
    void aStandingRemotePlayerStopsTheBoat() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity boat = setUp(f, EntityPose.STANDING);
            List<SimpleCollisionBox> colliders = colliders(f, boat);

            assertEquals(1, colliders.size(), colliders.toString());
            assertEquals(FLOOR_Y + (double) 1.8F, colliders.getFirst().maxY, 1e-9);
            assertEquals(REMOTE.x - (double) 0.6F / 2 - BOAT.maxX, collide(f, boat, colliders).x, 1e-12);
        }
    }

    private static PacketEntity setUp(JavaTickFixture f, EntityPose pose) {
        f.addRemotePlayer(100, REMOTE, GameMode.SURVIVAL);
        PacketEntity remote = f.player.compensatedEntities.getEntity(100);
        remote.onPose(pose, 0);
        remote.onSizeHandled(0);
        f.player.compensatedEntities.addEntity(
                101, EntityTypeIds.OAK_BOAT, new Vec3(-0.6, FLOOR_Y + 1.6, 0.5), 0, 0, 0);
        return f.player.compensatedEntities.getEntity(101);
    }

    private static List<SimpleCollisionBox> colliders(JavaTickFixture f, PacketEntity boat) {
        SimpleCollisionBox region = BOAT.copy().expandToCoordinate(MOVE.x, MOVE.y, MOVE.z);
        return EntityPositionWorlds.colliders(f.player, boat, Collisions.feet(BOAT), region);
    }

    private static Vec3 collide(JavaTickFixture f, PacketEntity boat, List<SimpleCollisionBox> colliders) {
        return Collisions.collideEntity(
                f.player, BOAT.copy(), MOVE, false, 0, JavaCollisionState.of(f.player, boat, 0), colliders);
    }
}
