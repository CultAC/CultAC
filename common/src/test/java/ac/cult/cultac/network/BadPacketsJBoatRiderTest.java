package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#tick sends use_item from handleKeybinds with the rider's yaw, then ClientLevel#tickEntities runs
 * AbstractBoat#tick, which decays deltaRotation by the ground friction, steers it and turns the boat, and the rider's
 * AbstractBoat#positionRider, which adds the same deltaRotation to the rider's yaw before clampRotation. Only then does
 * LocalPlayer#sendChanges send the rider's Rot and the boat's move, so a boat still turning after its keys are released
 * turns the rotation the tick sends away from the one its use_item carried.
 */
class BadPacketsJBoatRiderTest {
    private static final int BOAT = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, false);
    private static final ServerboundPlayerInput RELEASED =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    // The reported rider and boat looks before the flagged use_item.
    private static final float BOAT_YAW = -66.75605f;
    private static final float RIDER_YAW = -71.855606f;
    private static final float PITCH = 19.679043f;
    private static final int RELEASE_TICK = 4;
    private static final int TICKS = 16;
    // AbstractBoat#getGroundFriction averages the friction of the nine stone blocks under a boat centered on one.
    private static final float GROUND_FRICTION = groundFriction(9, 0.6F);

    @Test
    void useItemsWhileTheReleasedBoatKeepsTurningTheRiderMatchTheTickRotation() throws Exception {
        Run run = drive(0, true);

        assertTrue(run.turnedUses > 0, "no use_item came in a tick the boat turned its rider");
        assertEquals(0, run.violations);
    }

    @Test
    void aMouseTurnBeforeTheUseItemIsTurnedByTheBoatToo() throws Exception {
        Run run = drive(3, true);

        assertEquals(0, run.violations);
    }

    @Test
    void aTickRotationWithoutTheBoatsTurnFlagsTheUseItem() throws Exception {
        Run run = drive(0, false);

        assertTrue(run.turnedUses > 0, "no use_item came in a tick the boat turned its rider");
        assertEquals(run.turnedUses, run.violations);
    }

    private record Run(double violations, int turnedUses) {}

    /**
     * Steers left until {@link #RELEASE_TICK}, then sends a use_item every tick the released boat turns, turned by
     * {@code mouseTurn} before handleKeybinds. The tick's Rot carries positionRider's turn when {@code riderTurned},
     * else the use yaw.
     */
    private static Run drive(float mouseTurn, boolean riderTurned) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(SEAT, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, BOAT_YAW, 0, 0);
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
            BadPacketsJ check = f.player.checkManager.getListener(BadPacketsJ.class);

            float deltaRotation = 0;
            float boatYaw = BOAT_YAW;
            float yaw = RIDER_YAW;
            int sequence = 0;
            int turnedUses = 0;
            for (int tick = 0; tick < TICKS; tick++) {
                if (tick == 0) f.input(LEFT);
                if (tick == RELEASE_TICK) f.input(RELEASED);
                boolean use = tick > RELEASE_TICK + 1;
                if (use) {
                    yaw += mouseTurn;
                    var packet = new ServerboundUseItem(Hand.MAIN_HAND, ++sequence, yaw, PITCH);
                    f.listener.onUseItem(f.event(ServerboundPackets.USE_ITEM, packet), f.player, packet);
                }
                // AbstractBoat#floatBoat, then controlBoat with the keys LocalPlayer#rideTick set last tick.
                deltaRotation *= GROUND_FRICTION;
                if (tick > 0 && tick <= RELEASE_TICK) deltaRotation--;
                boatYaw += deltaRotation;
                float turned = clampRotation(yaw + deltaRotation, boatYaw);
                if (use && turned != yaw) turnedUses++;
                rot(f, use && !riderTurned ? yaw : turned);
                yaw = turned;
                moveBoat(f, boatYaw);
                f.tickEnd();
            }
            assertNotEquals(0, deltaRotation, "the boat stopped turning");
            return new Run(check.getViolations(), turnedUses);
        }
    }

    private static void rot(JavaTickFixture f, float yaw) {
        var packet = new ServerboundMovePlayer(0, 0, 0, yaw, PITCH, f.player.onGround, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }

    private static void moveBoat(JavaTickFixture f, float boatYaw) {
        var packet = new ServerboundMoveVehicle(new Vec3d(SEAT.x, SEAT.y, SEAT.z), boatYaw, 0, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, packet), f.player, packet);
    }

    // AbstractBoat#getGroundFriction's float sum over the touching blocks, divided by their count.
    private static float groundFriction(int blocks, float friction) {
        float sum = 0;
        for (int i = 0; i < blocks; i++) sum += friction;
        return sum / blocks;
    }

    // AbstractBoat#clampRotation's yaw for a player passenger, in Mth's float operations.
    private static float clampRotation(float yaw, float boatYaw) {
        float delta = (yaw - boatYaw) % 360.0F;
        if (delta >= 180.0F) delta -= 360.0F;
        if (delta < -180.0F) delta += 360.0F;
        float target = delta < -105.0F ? -105.0F : Math.min(delta, 105.0F);
        return yaw + target - delta;
    }
}
