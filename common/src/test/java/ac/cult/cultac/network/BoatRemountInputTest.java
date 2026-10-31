package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AbstractBoat#tick runs controlBoat on the boat's own inputLeft/Right/Up/Down before ClientLevel#tickPassenger runs
 * LocalPlayer#rideTick. That rideTick's AbstractBoat#setInput is their only writer, and nothing clears them on a
 * dismount, so a boat's first tick under its rider uses the keys of the last tick the client steered that boat, or none
 * if it never did, whatever keys the player sent on foot. At yaw 0 on stone a still boat holding the forward key moves
 * exactly 0.04F along +Z, floatBoat's gravity landing back on the floor; without it the boat does not move.
 */
class BoatRemountInputTest {
    private static final int BOAT = 100;
    private static final int OTHER_BOAT = 101;
    private static final Vec3 BOAT_AT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 OTHER_BOAT_AT = new Vec3(-3.5, FLOOR_Y, 0.5);
    private static final Vec3 ON_FOOT = new Vec3(4.5, FLOOR_Y, 0.5);
    /** AbstractBoat#controlBoat's forward acceleration at yaw 0: Mth.cos(0) * 0.04F. */
    private static final Vec3 THRUST = new Vec3(0, 0, 0.04F);

    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    /** Opposing keys: no impulse on foot, yet controlBoat adds 0.04F and takes 0.005F. */
    private static final ServerboundPlayerInput FORWARD_BACKWARD =
            new ServerboundPlayerInput(true, true, false, false, false, false, false);

    @Test
    void aRemountedBoatStillHoldsTheForwardKeyItsLastRideEndedWith() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            board(f, BOAT, BOAT_AT);
            f.input(FORWARD);
            assertExact(f.rideTick(BOAT_AT, true));
            dismount(f, BOAT);
            walk(f, NONE);
            mount(f, BOAT);
            assertExact(f.rideTick(BOAT_AT.add(THRUST), true));
        }
    }

    @Test
    void aRemountedBoatIgnoresTheKeysThePlayerPressedOnFoot() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            board(f, BOAT, BOAT_AT);
            assertExact(f.rideTick(BOAT_AT, true));
            dismount(f, BOAT);
            walk(f, FORWARD_BACKWARD);
            mount(f, BOAT);
            assertExact(f.rideTick(BOAT_AT, true));
        }
    }

    @Test
    void eachBoatKeepsTheKeysOfItsOwnLastRide() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            f.player.compensatedEntities.addEntity(OTHER_BOAT, EntityTypeIds.OAK_BOAT, OTHER_BOAT_AT, 0, 0, 0);
            board(f, BOAT, BOAT_AT);
            f.input(FORWARD);
            assertExact(f.rideTick(BOAT_AT, true));
            dismount(f, BOAT);
            mount(f, OTHER_BOAT);
            // The other boat was never steered: it holds no keys, though the player still holds forward.
            f.input(NONE);
            assertExact(f.rideTick(OTHER_BOAT_AT, true));
            dismount(f, OTHER_BOAT);
            mount(f, BOAT);
            assertExact(f.rideTick(BOAT_AT.add(THRUST), true));
        }
    }

    /** Puts the player beside a new boat at {@code at} and on it as its only passenger. */
    private static void board(JavaTickFixture f, int boat, Vec3 at) {
        f.place(at, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(boat, EntityTypeIds.OAK_BOAT, at, 0, 0, 0);
        f.player.xRot = 0;
        f.player.yRot = 0;
        mount(f, boat);
    }

    private static void mount(JavaTickFixture f, int boat) {
        f.setPassengers(boat, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
    }

    private static void dismount(JavaTickFixture f, int boat) {
        f.setPassengers(boat, List.of());
        f.pongThrough(f.player.lastTransactionSent.get());
        assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
    }

    /**
     * The server's dismount teleport onto the floor, then a client tick there with {@code keys} held. The teleport zeroed
     * the delta movement, so that tick's Entity#move is still and finds no collision below: the player is not on ground.
     */
    private static void walk(JavaTickFixture f, ServerboundPlayerInput keys) {
        f.teleport(ON_FOOT, new RelativeFlag(0));
        f.input(keys);
        assertExact(f.tick(ON_FOOT, false));
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }

    /** Observe-only mode, so a dismount sends no resync teleport. */
    private static AutoCloseable observeOnly() throws ReflectiveOperationException {
        BaseConfigManager manager = CultAPI.INSTANCE.getConfigManager();
        Field field = BaseConfigManager.class.getDeclaredField("observeOnly");
        field.setAccessible(true);
        boolean original = field.getBoolean(manager);
        field.setBoolean(manager, true);
        return () -> field.setBoolean(manager, original);
    }
}
