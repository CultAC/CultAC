package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.grim.grimac.api.storage.verbose.Verbose;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

@CheckData(
        name = "MultiActionsC",
        stableKey = "cult.multiactions.inventory_click_while_moving",
        description = "Clicked in inventory while moving")
public class MultiActionsC extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("sprinting={bool}, sneaking={bool}, input={bool}");

    public MultiActionsC(CultPlayer player) {
        super(player);
    }

    @Contract(pure = true)
    public static boolean isVerboseSprinting(@NotNull CultPlayer player) {
        return player.isSprinting && (!player.isSwimming || !player.packetStateData.packetPlayerOnGround);
    }

    @Contract(pure = true)
    public static boolean isVerboseInput(@NotNull CultPlayer player) {
        return player.checkManager
                .getListener(ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAutoJump.class)
                .hasManualMovementInput();
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick packet) {
        if (player.serverOpenedInventoryThisTick) return;

        boolean sprinting = isVerboseSprinting(player);
        boolean input = isVerboseInput(player);
        if (!sprinting && !input) return;

        if (flag(V.write(verbose()).bool(sprinting).bool(false).bool(input)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
