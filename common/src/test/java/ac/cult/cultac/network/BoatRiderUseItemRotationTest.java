package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 26.3 Minecraft#tick sends use_item from handleKeybinds, then ClientLevel#tickEntities turns the boat by its
 * deltaRotation and AbstractBoat#positionRider turns the rider by the same deltaRotation, and only then does
 * LocalPlayer#sendChanges send the rider's Rot. A boat keeps turning its rider while deltaRotation decays after the keys
 * are released, so the use_item yaw is the Rot's yaw before that turn (issue #88).
 */
class BoatRiderUseItemRotationTest {
    private static final int BOAT = 100;
    private static final int OTHER_RIDER = 101;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, false);
    private static final ServerboundPlayerInput RELEASED =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int RELEASE_TICK = 8;
    private static final int TICKS = 30;
    private static final float LOOK_YAW = 30;
    private static final float LOOK_PITCH = 10;

    @Test
    void aUseItemBeforeTheBoatTurnsItsRiderIsNotFlagged() throws Exception {
        Run run = drive(true, false, null);

        assertTrue(run.turnedUses > 0, "the boat turned no judged use_item");
        assertEquals(0, run.violations);
    }

    @Test
    void aUseItemAtTheTurnedYawIsFlagged() throws Exception {
        Run run = drive(true, true, null);

        assertTrue(run.turnedUses > 0, "the boat turned no judged use_item");
        assertEquals(run.turnedUses, run.violations);
    }

    @Test
    void aRiderBehindTheBoatsControllerIsNotTurnedByItsDeltaRotation() throws Exception {
        Run run = drive(false, false, null);

        assertTrue(run.turnedUses > 0, "the rider turned no judged use_item");
        assertEquals(run.turnedUses, run.violations);
    }

    /** Before 26.3, LocalPlayer#tick sent the Rot before Entity#rideTick positioned the rider. */
    @Test
    void aPre263RotCarriesTheUseItemYaw() throws Exception {
        Run run = drive(true, true, ClientVersion.V_26_2);

        assertTrue(run.turnedUses > 0, "the boat turned no judged use_item");
        assertEquals(0, run.violations);
    }

    private record Run(double violations, int turnedUses) {}

    /**
     * Steers left until {@link #RELEASE_TICK}, then uses an item every tick while the boat keeps turning the rider the
     * way vanilla does, at the yaw before the tick's turn or, {@code atTurnedYaw}, at the yaw the Rot then carries. A
     * rider behind the controller sends the same look while the boat, which it cannot move, stands still.
     */
    private static Run drive(boolean controls, boolean atTurnedYaw, ClientVersion version) throws Exception {
        float[] turns = deltaRotations();
        try (var f = board(controls)) {
            if (version != null) setClientVersion(f.player, version);
            BadPacketsJ check = f.player.checkManager.getListener(BadPacketsJ.class);
            check.violations = 0;
            float boatYaw = 0;
            // Entity#turn, then AbstractBoat#onPassengerTurned, before the first tick.
            float yaw = clampRotation(LOOK_YAW, boatYaw);
            int turnedUses = 0;
            int sequence = 0;
            for (int tick = 0; tick < TICKS; tick++) {
                float useYaw = yaw;
                steer(f, tick);
                if (controls) boatYaw += turns[tick];
                yaw = clampRotation(yaw + turns[tick], boatYaw);
                if (tick > RELEASE_TICK) {
                    var use = new ServerboundUseItem(Hand.MAIN_HAND, ++sequence, atTurnedYaw ? yaw : useYaw, LOOK_PITCH);
                    f.listener.onUseItem(f.event(ServerboundPackets.USE_ITEM, use), f.player, use);
                    if (yaw != useYaw) turnedUses++;
                }
                rot(f, yaw, LOOK_PITCH);
                if (controls) moveBoat(f, boatYaw);
                f.tickEnd();
            }
            return new Run(check.violations, turnedUses);
        }
    }

    /** The boat's deltaRotation at each tick of {@link #drive}, which neither the rider's nor the boat's yaw changes. */
    private static float[] deltaRotations() throws Exception {
        float[] turns = new float[TICKS];
        try (var f = board(true)) {
            for (int tick = 0; tick < TICKS; tick++) {
                steer(f, tick);
                rot(f, f.player.xRot, f.player.yRot);
                moveBoat(f, 0);
                f.tickEnd();
                turns[tick] = f.player.boatData.deltaRotation;
            }
        }
        return turns;
    }

    /** A 26.3 client on a boat at yaw 0 on stone, its first passenger when it {@code controls} the boat. */
    private static JavaTickFixture board(boolean controls) throws Exception {
        var f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, 0, 0, 0);
        if (controls) {
            f.setPassengers(BOAT, List.of(f.player.entityID));
        } else {
            f.player.compensatedEntities.addEntity(OTHER_RIDER, EntityTypeIds.ZOMBIE, SEAT, 0, 0, 0);
            f.setPassengers(BOAT, List.of(OTHER_RIDER, f.player.entityID));
        }
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        return f;
    }

    // LocalPlayer#sendChanges sends the keys that changed before the Rot.
    private static void steer(JavaTickFixture f, int tick) {
        if (tick == 0) f.input(LEFT);
        if (tick == RELEASE_TICK) f.input(RELEASED);
    }

    private static void rot(JavaTickFixture f, float yaw, float pitch) {
        var packet = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, f.player.onGround, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }

    private static void moveBoat(JavaTickFixture f, float boatYaw) {
        var packet = new ServerboundMoveVehicle(new Vec3d(SEAT.x, SEAT.y, SEAT.z), boatYaw, 0, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, packet), f.player, packet);
    }

    private static void setClientVersion(CultPlayer player, ClientVersion version) throws ReflectiveOperationException {
        Field field = CultPlayer.class.getDeclaredField("resolvedClientVersion");
        field.setAccessible(true);
        field.set(player, version);
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
