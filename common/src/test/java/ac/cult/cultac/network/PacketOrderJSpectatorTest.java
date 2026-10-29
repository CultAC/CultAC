package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.packetorder.PacketOrderJ;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * In 26.3 a spectator's Minecraft#startAttack sends MultiPlayerGameMode#spectate for an entity hit result and
 * MultiPlayerGameMode#spectatorNoAction, an empty target, for a block or a miss. Minecraft#startUseItem then sends
 * use_item_on for the block through MultiPlayerGameMode#useItemOn's startPrediction, and the CONSUME that
 * performUseItemOn returns for a spectator ends startUseItem there; an entity hit result goes through
 * MultiPlayerGameMode#interact.
 */
class PacketOrderJSpectatorTest {
    private static final Vec3 STAND = new Vec3(0.5, FLOOR_Y + 0.72, 0.5);
    private static final BlockPos CLICKED = new BlockPos(0, FLOOR_Y - 1, 0);
    private static final int SPECTATED = 9001;

    @Test
    void aUseOnTheBlockAfterASpectatorClickWithNoTargetDoesNotFlag() throws Exception {
        assertEquals(0, violations(OptionalInt.empty()));
    }

    @Test
    void aUseOnABlockAfterSpectatingAnEntityFlags() throws Exception {
        assertEquals(1, violations(OptionalInt.of(SPECTATED)));
    }

    /** One tick of the capture: spectator_action with {@code target}, use_item_on and the tick end. */
    private static double violations(OptionalInt target) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.gamemode = GameMode.SPECTATOR;
            f.player.setExperimentalChecks(true);
            f.place(STAND, Vec3.ZERO);
            f.tickEnd();
            var spectate = new ServerboundSpectatorAction(target);
            f.listener.onSpectatorAction(f.event(ServerboundPackets.SPECTATOR_ACTION, spectate), f.player, spectate);
            var use = new ServerboundUseItemOn(
                    Hand.MAIN_HAND, CLICKED, Direction.UP, new Vec3d(0.734, 1.0, 0.357), false, false, 29);
            f.listener.onUseItemOn(f.event(ServerboundPackets.USE_ITEM_ON, use), f.player, use);
            f.tickEnd();
            return f.player.checkManager.getListener(PacketOrderJ.class).getViolations();
        }
    }
}
