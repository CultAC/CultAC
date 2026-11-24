package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsF;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
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
 * A spectator's Minecraft#startAttack sends MultiPlayerGameMode#spectatorNoAction, an empty target, for a block or a
 * miss, and Minecraft#startUseItem then sends use_item_on for that block in the same tick; an entity hit result sends
 * MultiPlayerGameMode#spectate and goes through MultiPlayerGameMode#interact.
 */
class MultiActionsFSpectatorTest {
    private static final Vec3 STAND = new Vec3(0.5, FLOOR_Y + 0.72, 0.5);
    private static final BlockPos CLICKED = new BlockPos(0, FLOOR_Y - 1, 0);
    private static final int SPECTATED = 9001;

    @Test
    void aBlockUseAfterASpectatorClickWithNoTargetDoesNotFlag() throws Exception {
        assertEquals(0, violations(OptionalInt.empty()));
    }

    @Test
    void aBlockUseAfterSpectatingAnEntityFlags() throws Exception {
        assertEquals(1, violations(OptionalInt.of(SPECTATED)));
    }

    /** One tick of the capture with a block held: spectator_action with {@code target}, use_item_on, tick end. */
    private static double violations(OptionalInt target) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.gamemode = GameMode.SPECTATOR;
            f.player.setExperimentalChecks(true);
            f.player.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item("minecraft:stone"));
            f.place(STAND, Vec3.ZERO);
            f.tickEnd();
            var spectate = new ServerboundSpectatorAction(target);
            CheckManagerListener.receiveGeneric(f.event(ServerboundPackets.SPECTATOR_ACTION, spectate), f.player);
            var use = new ServerboundUseItemOn(
                    Hand.MAIN_HAND, CLICKED, Direction.UP, new Vec3d(0.612, 1.0, 0.167), false, false, 29);
            f.listener.onUseItemOn(f.event(ServerboundPackets.USE_ITEM_ON, use), f.player, use);
            f.tickEnd();
            return f.player.checkManager.getCheck(MultiActionsF.class).getViolations();
        }
    }
}
