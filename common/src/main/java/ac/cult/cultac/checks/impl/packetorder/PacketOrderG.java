package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "PacketOrderG",
        stableKey = "cult.packetorder.hotbar_inventory_manage_order",
        description = "Managed hotbar or inventory while performing another conflicting action",
        experimental = true)
public class PacketOrderG extends Check implements CheckListener {
    private static final Verbose V = Verbose.of(
            "action={str}, attacking={bool}, releasing={bool}, rightClicking={bool}, picking={bool}, digging={bool}");

    static final int ACTION_SWAP = 1;
    static final int ACTION_DROP = 2;

    public PacketOrderG(CultPlayer player) {
        super(player);
    }

    static String actionName(int action) {
        return switch (action) {
            case ACTION_SWAP -> "swap";
            case ACTION_DROP -> "drop";
            default -> "unknown";
        };
    }

    private static int action(PlayerAction action) {
        return action == PlayerAction.SWAP_ITEM_WITH_OFFHAND ? ACTION_SWAP : ACTION_DROP;
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        final PlayerAction action = packet.action();
        if (action != PlayerAction.SWAP_ITEM_WITH_OFFHAND
                && action != PlayerAction.DROP_ITEM
                && action != PlayerAction.DROP_ALL_ITEMS) return;

        onAction(event, player, action);
    }

    private void onAction(PacketReceiveEvent event, CultPlayer player, PlayerAction action) {
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
            if (flag(V.write(verbose())
                            .str(actionName(actionKind))
                            .bool(attacking)
                            .bool(releasing)
                            .bool(rightClicking)
                            .bool(picking)
                            .bool(digging))
                    && shouldModifyPackets()
                    && canCancel(action)) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }

    private boolean canCancel(PlayerAction action) {
        return action != PlayerAction.RELEASE_USE_ITEM
                && action != PlayerAction.DROP_ITEM
                && action != PlayerAction.DROP_ALL_ITEMS;
    }
}
