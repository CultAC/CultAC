package ac.cult.cultac.checks.impl.packetorder;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

import java.util.ArrayDeque;

@CheckData(name = "PacketOrderK", stableKey = "cult.packetorder.inventory_open_order", description = "Opened, clicked, or closed inventory in the wrong packet order", experimental = true)
public class PacketOrderK extends Check implements PostPredictionListener {
    // Shape index == KIND_* constant value.
    private static final Verbose V = Verbose
            .of("open, clicking={bool}, closing={bool}")
            .or("click")
            .or("close");

    static final int KIND_OPEN = 0;
    static final int KIND_CLICK = 1;
    static final int KIND_CLOSE = 2;

    public PacketOrderK(final CultPlayer player) {
        super(player);
    }

    private final ArrayDeque<FlagData> flags = new ArrayDeque<>();

    private Verbose.Writer write(int kind, boolean clicking, boolean closing) {
        Verbose.Writer writer = V.write(verbose(), kind);
        if (kind == KIND_OPEN) writer.bool(clicking).bool(closing);
        return writer;
    }


    @CultPacketHandler
    public void onClientCommand(PacketReceiveEvent event, CultPlayer player, ServerboundClientCommandPacket packet) {
        // The 26.2 enum has no OPEN_INVENTORY_ACHIEVEMENT (removed in 1.12)
        if (!packet.getAction().name().equals("OPEN_INVENTORY_ACHIEVEMENT")) return;

        if (player.packetOrderProcessor.isClickingInInventory() || player.packetOrderProcessor.isClosingInventory()) {
            boolean clicking = player.packetOrderProcessor.isClickingInInventory();
            boolean closing = player.packetOrderProcessor.isClosingInventory();
            if (!player.canSkipTicks()) {
                flag(write(KIND_OPEN, clicking, closing));
            } else {
                flags.add(new FlagData(KIND_OPEN, clicking, closing));
            }
        }
    }


    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClickPacket packet) {
        onClickOrClose(event, player, KIND_CLICK);
    }


    @CultPacketHandler
    public void onContainerClose(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClosePacket packet) {
        onClickOrClose(event, player, KIND_CLOSE);
    }

    private void onClickOrClose(PacketReceiveEvent event, CultPlayer player, int kind) {
        if (player.packetOrderProcessor.isOpeningInventory()) {
            if (!player.canSkipTicks()) {
                if (flag(write(kind, false, false))
                        && shouldModifyPackets() && kind == KIND_CLICK) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else {
                flags.add(new FlagData(kind, false, false));
            }
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!player.canSkipTicks()) return;

        if (player.isTickingReliablyFor(3)) {
            for (FlagData data : flags) {
                flag(write(data.kind(), data.clicking(), data.closing()));
            }
        }

        flags.clear();
    }

    private record FlagData(int kind, boolean clicking, boolean closing) {}
}
