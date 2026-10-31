package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SynchedEntityData#assignValues calls AbstractNautilus#onSyncedDataUpdated for every DASH entry it receives, changed
 * or not, while SynchedEntityData#set calls it only for a changed value. After the nautilus's first tick the callback
 * sets a spent dash cooldown to 40, and LocalPlayer#aiStep releases a riding jump only with no cooldown left.
 */
class NautilusDashCooldownTest {
    private static final int NAUTILUS = 124;
    /** AbstractNautilus DASH in the 26.3 metadata layout. */
    private static final int DASH = 20;
    /** AbstractNautilus#executeRidersJump and AbstractNautilus#onSyncedDataUpdated's dash cooldown. */
    private static final int DASH_COOLDOWN = 40;
    /** PlayerRideableJumping#getPlayerJumpPendingScale for a full jump bar. */
    private static final int FULL_JUMP = 90;

    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 ON_FOOT = new Vec3(4.5, FLOOR_Y, 0.5);

    @Test
    void aRepeatedDashEntryRestartsTheSpentCooldown() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntityNautilus nautilus = spawn(f);
            metadata(f, false);
            f.tick(ON_FOOT, true);
            assertEquals(0, nautilus.dashCooldown);

            metadata(f, false);
            assertEquals(DASH_COOLDOWN, nautilus.dashCooldown);
            mount(f);
            jump(f);
            assertEquals(0.0F, nautilus.pendingJumpScale, "the dash was armed during the cooldown");
        }
    }

    /** AbstractNautilus#onSyncedDataUpdated skips DASH on the nautilus's first tick: the spawn's entry sets none. */
    @Test
    void aDashEntryBeforeTheFirstTickSetsNoCooldown() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntityNautilus nautilus = spawn(f);
            metadata(f, false);
            assertEquals(0, nautilus.dashCooldown);

            mount(f);
            jump(f);
            assertEquals(1.0F, nautilus.pendingJumpScale);
        }
    }

    /**
     * A nautilus spawned mid-dash takes no cooldown from its spawn entry, but AbstractNautilus#tick then ends the dash
     * through setDashing(false), a changed value, whose callback sets the spent cooldown before the tick runs it down.
     */
    @Test
    void endingASpawnedDashRestartsTheCooldown() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntityNautilus nautilus = spawn(f);
            metadata(f, true);
            assertEquals(0, nautilus.dashCooldown);

            f.tick(ON_FOOT, true);
            assertFalse(nautilus.dashing);
            assertEquals(DASH_COOLDOWN - 1, nautilus.dashCooldown);
        }
    }

    /** ClientLevel#tickEntities runs AbstractNautilus#tick for every nautilus, so an unridden one's cooldown expires. */
    @Test
    void anUnriddenNautilusRunsDownItsCooldown() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntityNautilus nautilus = spawn(f);
            f.tick(ON_FOOT, true);
            metadata(f, true);
            assertEquals(DASH_COOLDOWN, nautilus.dashCooldown);

            for (int tick = 0; tick < DASH_COOLDOWN; tick++) f.tick(ON_FOOT, true);
            assertFalse(nautilus.dashing);
            assertEquals(0, nautilus.dashCooldown);
            mount(f);
            jump(f);
            assertEquals(1.0F, nautilus.pendingJumpScale);
        }
    }

    /** The saddled nautilus at {@code START} beside the player on foot. */
    private static PacketEntityNautilus spawn(JavaTickFixture f) {
        f.place(ON_FOOT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(
                NAUTILUS, EntityTypes.defaults().byKey("minecraft:nautilus").id(), START, 0, 0, 0);
        PacketEntityNautilus nautilus = (PacketEntityNautilus) f.player.compensatedEntities.getEntity(NAUTILUS);
        nautilus.hasSaddle = true;
        return nautilus;
    }

    /** The server's nautilus DASH, delivered. */
    private static void metadata(JavaTickFixture f, boolean dashing) {
        f.player.compensatedEntities.updateEntityMetadata(
                NAUTILUS, List.of(new EntityMetadata.Entry(DASH, dashing, ByteBuffer.allocate(0))));
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void mount(JavaTickFixture f) {
        f.setPassengers(NAUTILUS, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** The rider releases a full riding jump; the client tick ends and arms the nautilus's next tick, or does not. */
    private static void jump(JavaTickFixture f) {
        f.command(PlayerCommandAction.START_JUMPING_WITH_HORSE, FULL_JUMP);
        f.tickEnd();
    }
}
