package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A 26.3 player with no gravity sneaks in the air at a tiny sneaking_speed, so each travel moves less than
 * LocalPlayer#sendPosition's 2.0E-4 and the client withholds its position for several ticks. Entity#move still moves
 * it: no collided vertical movement lets the setPos guard drop the move. Entity#getInputVector's rotation differs
 * between the vanilla and OptiFine trig tables, so each table's hidden travel ends elsewhere until the next report
 * shows which one the client used.
 */
class JavaWithheldTableTravelTest {
    private static final ServerboundPlayerInput CROUCH =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final float SNEAKING_SPEED = 0.001F;
    // Player#getFlyingSpeed off the ground for a player that is not sprinting.
    private static final float AIR_SPEED = 0.02F;

    @ParameterizedTest
    @CsvSource({
        "true, -33.7, true, false",
        "false, -33.7, true, false",
        "true, 30.0, true, true",
        "false, 30.0, true, true",
        "true, 151.13, false, true",
        "false, 151.13, false, true"
    })
    void eachTablesHiddenTravelStaysPossibleUntilTheReport(boolean vanilla, float yaw, boolean forward, boolean left)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            var keys = new ServerboundPlayerInput(forward, !forward, left, false, false, true, false);
            attribute(f, "minecraft:gravity", 0.0D);
            attribute(f, "minecraft:sneaking_speed", SNEAKING_SPEED);
            f.player.xRot = yaw;
            Vec3 position = new Vec3(0.5, FLOOR_Y + 1, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(CROUCH);
            PredictionResult idle = f.tick(position, false);
            assertTrue(JavaTickFixture.exact(idle), "idle: " + JavaTickFixture.describe(idle));
            f.input(keys);
            Vec3 step = step(f, keys, yaw, vanilla);
            assertTrue(step.length() < 2.0E-4 / 4, "four travels stay withheld: " + step);
            assertFalse(step.equals(step(f, keys, yaw, !vanilla)), "the tables must travel apart");
            // LocalPlayer#sendPosition withholds a move within 2.0E-4 of the last sent position.
            int withheld = (int) Math.ceil(2.0E-4 / step.length()) - 1;
            for (int report = 0; report < 3; report++) {
                for (int tick = 0; tick < withheld; tick++) {
                    position = position.add(step);
                    f.tickEnd();
                }
                position = position.add(step);
                PredictionResult result = f.tick(position, false);
                assertTrue(
                        JavaTickFixture.exact(result),
                        (vanilla ? "vanilla" : "fast") + " report " + report + ": " + JavaTickFixture.describe(result));
                assertEquals(position.x, f.player.x, 0.0);
                assertEquals(position.z, f.player.z, 0.0);
            }
        }
    }

    /** One travel's move: LivingEntity#aiStep's threshold zeroes the carried velocity, leaving the input. */
    private static Vec3 step(JavaTickFixture f, ServerboundPlayerInput keys, float yaw, boolean vanilla) {
        if (!vanilla) f.player.trigHandler.toggleShitMath();
        try {
            return ClientMoveInput.of(f, keys, SNEAKING_SPEED, AIR_SPEED, yaw);
        } finally {
            if (!vanilla) f.player.trigHandler.toggleShitMath();
        }
    }

    private static void attribute(JavaTickFixture f, String attribute, double base) {
        var packet = new ClientboundUpdateAttributes(
                f.player.entityID, List.of(new AttributeSnapshot(attribute, base, List.of())));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, packet, false);
        f.player.packetEntityReplication.onUpdateAttributes(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
