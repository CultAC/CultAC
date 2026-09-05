package ac.cult.cultac.checks.impl.multiactions;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

@CheckData(name = "MultiActionsC", stableKey = "cult.multiactions.inventory_click_while_moving", description = "Clicked in inventory while moving")
public class MultiActionsC extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("sprinting={bool}, sneaking={bool}, input={bool}");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    public MultiActionsC(CultPlayer player) {
        super(player);
    }

    @Contract(pure = true)
    public static boolean isVerboseSprinting(@NotNull CultPlayer player) {
        return player.isSprinting && (!player.isSwimming || !player.packetStateData.packetPlayerOnGround);
    }

    @Contract(pure = true)
    public static boolean isVerboseSneaking(@NotNull CultPlayer player) {
        return player.isSneaking && player.getClientVersion().getProtocolVersion() < 573; // PE ClientVersion.V_1_15
    }

    @Contract(pure = true)
    public static boolean isVerboseInput(@NotNull CultPlayer player) {
        return supportsEndTick(player) && player.packetStateData.knownInput.moving();
    }

    static boolean supportsEndTick(@NotNull CultPlayer player) {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21_2);
    }


    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClickPacket packet) {
        if (player.serverOpenedInventoryThisTick) return;

        boolean sprinting = isVerboseSprinting(player);
        boolean sneaking = isVerboseSneaking(player);
        boolean input = isVerboseInput(player);
        if (!sprinting && !sneaking && !input) return;

        if (flag(V.write(verbose()).bool(sprinting).bool(sneaking).bool(input)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
