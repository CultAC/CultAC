package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.Collection;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleMovePlayer answers a player_position's ID while handling it, before the client handles any
 * ping sent after it. An acknowledgment still owed once such a ping is answered was withheld and can never be claimed.
 */
class WithheldTeleportAcknowledgmentTest {
    private static final Vec3 TARGET = new Vec3(0.5, FLOOR_Y, 3.5);

    @Test
    void withheldAcknowledgmentsDoNotAccumulate() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int id = 1; id <= 50; id++) {
                int leading = f.player.sendTrailingTransaction();
                addTeleport(f, leading, id, false);
                f.pongThrough(leading);
                // The ping after the teleport, answered with no acknowledgment before it.
                f.pongThrough(f.player.sendTrailingTransaction());
            }
            assertTrue(acknowledgments(f).size() <= 1, "owed acknowledgments: " + acknowledgments(f).size());
        }
    }

    @Test
    void anAnsweredAcknowledgmentIsConsumed() throws Exception {
        try (var f = new JavaTickFixture()) {
            int leading = f.player.sendTrailingTransaction();
            addTeleport(f, leading, 5, true);
            f.pongThrough(leading);
            assertTrue(f.player.getSetbackTeleportUtil().takeAuthoredTeleportAcknowledgment(5));
            assertEquals(0, acknowledgments(f).size());
        }
    }

    @Test
    void aReplyAfterAWithheldAuthoredAcknowledgmentBelongsToTheNextOccurrence() throws Exception {
        try (var f = new JavaTickFixture()) {
            int authored = f.player.sendTrailingTransaction();
            addTeleport(f, authored, 7, true);
            f.pongThrough(authored);
            f.pongThrough(f.player.sendTrailingTransaction());

            int backend = f.player.sendTrailingTransaction();
            addTeleport(f, backend, 7, false);
            f.pongThrough(backend);
            assertFalse(f.player.getSetbackTeleportUtil().takeAuthoredTeleportAcknowledgment(7));
        }
    }

    private static void addTeleport(JavaTickFixture f, int transaction, int id, boolean authored) {
        f.player.getSetbackTeleportUtil()
                .javaTeleports().addSentTeleport(TARGET, Vec3.ZERO, transaction, new RelativeFlag(0), false, id, 0, 0, 0, 0, authored);
    }

    private static Collection<?> acknowledgments(JavaTickFixture f) throws Exception {
        Field field = SetbackTeleportUtil.class.getDeclaredField("javaTeleportAcknowledgments");
        field.setAccessible(true);
        return (Collection<?>) field.get(f.player.getSetbackTeleportUtil());
    }
}
