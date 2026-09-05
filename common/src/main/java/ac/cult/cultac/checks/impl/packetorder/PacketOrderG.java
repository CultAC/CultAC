package ac.cult.cultac.checks.impl.packetorder;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;

@CheckData(name = "PacketOrderG", stableKey = "cult.packetorder.hotbar_inventory_manage_order", description = "Managed hotbar or inventory while performing another conflicting action", experimental = true)
public class PacketOrderG extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of(
            "action={str}, attacking={bool}, releasing={bool}, rightClicking={bool}, picking={bool}, digging={bool}");

    static final int ACTION_OPEN_INVENTORY = 0;
    static final int ACTION_SWAP = 1;
    static final int ACTION_DROP = 2;

    public PacketOrderG(CultPlayer player) {
        super(player);
    }

    private final ArrayDeque<FlagData> flags = new ArrayDeque<>();

    static String actionName(int action) {
        return switch (action) {
            case ACTION_OPEN_INVENTORY -> "openInventory";
            case ACTION_SWAP -> "swap";
            case ACTION_DROP -> "drop";
            default -> "unknown";
        };
    }

    private static int action(@Nullable ServerboundPlayerActionPacket.Action action) {
        return action == null ? ACTION_OPEN_INVENTORY
                : action == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND ? ACTION_SWAP : ACTION_DROP;
    }


    @CultPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerActionPacket packet) {
        final ServerboundPlayerActionPacket.Action action = packet.getAction();
        if (action != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
                && action != ServerboundPlayerActionPacket.Action.DROP_ITEM

                && action != ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
        ) return;

        onAction(event, player, action);
    }


    @CultPacketHandler
    public void onClientCommand(PacketReceiveEvent event, CultPlayer player, ServerboundClientCommandPacket packet) {
        // The 26.2 enum has no OPEN_INVENTORY_ACHIEVEMENT (removed in 1.12)
        if (packet.getAction().name().equals("OPEN_INVENTORY_ACHIEVEMENT")) {
            onAction(event, player, null);
        }
    }

    private void onAction(PacketReceiveEvent event, CultPlayer player, @Nullable ServerboundPlayerActionPacket.Action action) {
        if (player.packetOrderProcessor.isAttackingOrStabbing()
                || player.packetOrderProcessor.isReleasing()
                || player.packetOrderProcessor.isRightClicking()
                || player.packetOrderProcessor.isPicking()
                || player.packetOrderProcessor.isDigging()) {
            int actionKind = action(action);
            boolean attacking = player.packetOrderProcessor.isAttackingOrStabbing();
            boolean releasing = player.packetOrderProcessor.isReleasing();
            boolean rightClicking = player.packetOrderProcessor.isRightClicking();
            boolean picking = player.packetOrderProcessor.isPicking();
            boolean digging = player.packetOrderProcessor.isDigging();
            if (!player.canSkipTicks()) {
                if (flag(V.write(verbose())
                        .str(actionName(actionKind))
                        .bool(attacking)
                        .bool(releasing)
                        .bool(rightClicking)
                        .bool(picking)
                        .bool(digging)) && shouldModifyPackets() && canCancel(action)) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else {
                flags.add(new FlagData(actionKind, attacking, releasing, rightClicking, picking, digging));
            }
        }
    }


    private boolean canCancel(@Nullable ServerboundPlayerActionPacket.Action action) {
        return action != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
                && ((action != ServerboundPlayerActionPacket.Action.DROP_ITEM
                && action != ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS)
                || player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8));
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!player.canSkipTicks()) return;

        if (player.isTickingReliablyFor(3)) {
            for (FlagData data : flags) {
                flag(V.write(verbose())
                        .str(actionName(data.action()))
                        .bool(data.attacking())
                        .bool(data.releasing())
                        .bool(data.rightClicking())
                        .bool(data.picking())
                        .bool(data.digging()));
            }
        }

        flags.clear();
    }

    private record FlagData(
            int action,
            boolean attacking,
            boolean releasing,
            boolean rightClicking,
            boolean picking,
            boolean digging) {
    }
}
