package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The move a Java setback forwards to the server in place of the movement it rejects. Entity#checkFallDamage applies
 * the fall so far and resets it on a ground claim, so only the correction tick's own Entity#move may put the player on
 * the ground there, whatever ground state the engine started its last tick from.
 */
class SetbackServerMoveGroundTest {
    private static final Vec3 HIGH = new Vec3(.5, FLOOR_Y + 10, .5);
    private static final Vec3 FALLING = new Vec3(0, -.5, 0);

    @Test
    void aCorrectionTickInMidairReachesTheServerOffTheGround() throws Exception {
        try (var f = fallingFromAGroundStateTheClientClaimed()) {
            SetBackData setback = violationSetback(f);

            assertTrue(setback.getTeleportData().getLocation().y > FLOOR_Y + 8, "the correction tick fell short");
            assertFalse(setback.isExpectedOnGround());
            assertFalse(serverMove(f).onGround(), "the server received a ground claim in midair");
        }
    }

    @Test
    void aSetbackWithoutACorrectionTickReachesTheServerOffTheGround() throws Exception {
        try (var f = fallingFromAGroundStateTheClientClaimed()) {
            f.pongThrough(f.player.sendTrailingTransaction());
            f.player.getSetbackTeleportUtil().executeNonSimulatingSetback();

            assertFalse(f.player.getSetbackTeleportUtil().getRequiredSetBack().isExpectedOnGround());
            assertFalse(serverMove(f).onGround(), "the server received a ground claim in midair");
        }
    }

    /** The control: a correction tick that lands claims the ground it landed on. */
    @Test
    void aCorrectionTickThatLandsReachesTheServerOnTheGround() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(.5, FLOOR_Y + .5, .5), new Vec3(0, -.3, 0));
            f.tick(new Vec3(.5, FLOOR_Y + .2, .5), false);

            SetBackData setback = violationSetback(f);

            assertEquals(FLOOR_Y, setback.getTeleportData().getLocation().y, 1.0E-12);
            assertTrue(setback.isExpectedOnGround());
            assertTrue(serverMove(f).onGround(), "the server lost the landing");
        }
    }

    /**
     * A fall whose last tick started from a ground state the client claimed: an exempt tick adopts the claim, and a
     * client can claim the ground in midair.
     */
    private static JavaTickFixture fallingFromAGroundStateTheClientClaimed() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(HIGH, FALLING);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        f.tick(HIGH.add(FALLING), false);
        return f;
    }

    private static SetBackData violationSetback(JavaTickFixture f) {
        // The client has the chunk it stands in once it answers a ping sent after it.
        f.pongThrough(f.player.sendTrailingTransaction());
        SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
        // The client's next movement arrives and this setback rejects it; the correction tick stands in for it.
        teleports.movementReceived();
        assertTrue(teleports.executeViolationSetback());
        return teleports.getRequiredSetBack();
    }

    private static ServerboundMovePlayer serverMove(JavaTickFixture f) {
        ServerboundMovePlayer move = f.player.getSetbackTeleportUtil().takePendingServerMove();
        assertNotNull(move, "the setback forwarded no move");
        return move;
    }
}
