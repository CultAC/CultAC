package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FishingHook#pullEntity adds (owner - hook) * 0.1 from the client's own hook. The first FishingHook#tick that sees
 * DATA_HOOKED_ENTITY enters HOOKED_IN_ENTITY without moving the hook, and only the next one puts it at the entity's X,
 * Y(0.8) and Z. A pull before then reads wherever the client flew the hook.
 */
class JavaFishingHookPullTest {
    private static final int OWNER = 80, HOOK = 81;
    private static final Vec3 PLAYER_AT = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Vec3 OWNER_AT = new Vec3(6.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Vec3 HOOK_AT = new Vec3(2.5, JavaTickFixture.FLOOR_Y + 1, 0.5);

    @Test
    void aHookThatFollowedThePlayerPullsFromItsAttachmentExactly() throws Exception {
        try (var f = new JavaTickFixture()) {
            var hook = hookedOnPlayer(f);
            hook.onMovement(f.player);
            hook.onMovement(f.player);
            f.player.compensatedEntities.fishingPulls.finishClientTick(f.player);

            var source = hook.pullSource(f.player);
            assertNull(source.range());
            Vec3 attachment = PLAYER_AT.add(0, (double) f.player.getBukkitHeight() * 0.8, 0);
            assertEquals(List.of(attachment), source.positions());
        }
    }

    @Test
    void aHookThatHasNotFollowedYetPullsFromWhereverItMayHaveFlown() throws Exception {
        try (var f = new JavaTickFixture()) {
            var hook = hookedOnPlayer(f);
            hook.onMovement(f.player);

            var source = hook.pullSource(f.player);
            assertNotNull(source.range());
            Vec3 centre = source.positions().getFirst();
            var region = source.range().copy().offset(centre);
            Vec3 attachment = PLAYER_AT.add(0, (double) f.player.getBukkitHeight() * 0.8, 0);
            assertTrue(region.isIntersected(point(HOOK_AT)), "where the server last put the hook: " + region);
            assertTrue(region.isIntersected(point(attachment)), "through the hooked player: " + region);
        }
    }

    private static PacketEntityHook hookedOnPlayer(JavaTickFixture f) {
        f.place(PLAYER_AT, Vec3.ZERO);
        f.addRemotePlayer(OWNER, OWNER_AT, GameMode.SURVIVAL);
        f.player.compensatedEntities.addEntity(HOOK, EntityTypeIds.FISHING_BOBBER, HOOK_AT, 0, 0, OWNER);
        var hook = assertInstanceOf(PacketEntityHook.class, f.player.compensatedEntities.getEntity(HOOK));
        hook.setAttached(f.player, f.player.entityID);
        return hook;
    }

    private static SimpleCollisionBox point(Vec3 at) {
        return new SimpleCollisionBox(at.x, at.y, at.z, at.x, at.y, at.z);
    }
}
