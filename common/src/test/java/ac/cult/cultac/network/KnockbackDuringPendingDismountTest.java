package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

/**
 * A velocity or explosion the server sends for the local player after emptying its vehicle and before the client
 * confirms that dismount: ClientPacketListener#handleSetEntityPassengersPacket ejects the player first, so
 * handleSetEntityMotion or handleExplosion changes the player's own delta movement for its next free tick.
 */
class KnockbackDuringPendingDismountTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int VEHICLE = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 KNOCKBACK = new Vec3(0.21113349203442588, -0.0783739241897089, -0.3397424159189404);
    private static final Vec3 EXPLOSION = new Vec3(0.4244531692259865, 0.6226195093495551, -0.033498661089047324);

    @Test
    void aVelocitySentBeforeTheClientConfirmsTheDismountMovesThePlayer() throws Exception {
        try (var f = new JavaTickFixture()) {
            dismountThenKnockback(f);
            // Entity#move takes the horizontal knockback; the floor clips its downward part.
            PredictionResult result = f.tick(new Vec3(SEAT.x + KNOCKBACK.x, FLOOR_Y, SEAT.z + KNOCKBACK.z), true);
            assertTrue(exact(result), describe(result));
            assertEquals(0.0, f.player.checkManager.getKnockbackHandler().violations, "the client took the velocity");
        }
    }

    /** ClientPacketListener#handleExplosion pushes the player the client ejected first. */
    @Test
    void anExplosionSentBeforeTheClientConfirmsTheDismountMovesThePlayer() throws Exception {
        try (var f = new JavaTickFixture()) {
            dismountThen(f, () -> f.explode(EXPLOSION));
            PredictionResult result = f.tick(SEAT.add(EXPLOSION), false);
            assertTrue(exact(result), describe(result));
        }
    }

    /** The velocity is required from its proof, so a client that stays put is flagged. */
    @Test
    void stayingPutAfterTheVelocityFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            dismountThenKnockback(f);
            f.tick(SEAT, true);
            assertTrue(
                    f.player.checkManager.getKnockbackHandler().violations > 0,
                    "AntiKB violations " + f.player.checkManager.getKnockbackHandler().violations);
        }
    }

    /** Seated on an armor stand, then set_passengers empties it and a velocity follows before any ping returns. */
    private static void dismountThenKnockback(JavaTickFixture f) {
        dismountThen(f, () -> f.velocity(KNOCKBACK));
    }

    /** Seated on an armor stand, then set_passengers empties it and {@code impulse} follows before any ping returns. */
    private static void dismountThen(JavaTickFixture f, IntSupplier impulse) {
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.ARMOR_STAND, SEAT, 0, 0, 0);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(NONE);
        f.rideTick();
        f.rideTick();

        List<Integer> before = teleportIds(f);
        f.setPassengers(VEHICLE, List.of());
        assertTrue(f.player.compensatedEntities.vehicles.hasPendingServerDismount());
        int proof = impulse.getAsInt();
        assertNotEquals(-1, proof, "the impulse was taken for the vehicle's");

        f.pongThrough(proof);
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports) {
            if (before.contains(teleport.getTeleportId())) continue;
            Vec3 target = teleport.getLocation();
            var packet = new ServerboundAcceptTeleportation(
                    teleport.getTeleportId(),
                    new Vec3d(target.x, target.y, target.z),
                    teleport.getFinalYaw(),
                    teleport.getFinalPitch());
            var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
            new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
            assertFalse(event.isCancelled(), "the dismount teleport acceptance was rejected");
        }
        assertFalse(f.player.compensatedEntities.vehicles.hasPlayerPassengerState());
    }

    private static List<Integer> teleportIds(JavaTickFixture f) {
        var ids = new ArrayList<Integer>();
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports) {
            ids.add(teleport.getTeleportId());
        }
        return ids;
    }
}
