package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "PacketOrderF",
        stableKey = "cult.packetorder.input_tick_to_sneak_sprint_order",
        description = "Sent action packets after sneak or sprint input in an invalid order",
        experimental = true)
public class PacketOrderF extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("action={str}, sprinting={bool}, sneaking={bool}");

    static final int ACTION_INTERACT = 0;
    static final int ACTION_ATTACK = 1;
    static final int ACTION_SPECTATE_ENTITY = 2;
    static final int ACTION_PLACE = 3;
    static final int ACTION_USE = 4;
    static final int ACTION_PICK = 5;
    static final int ACTION_DIG = 6;

    public PacketOrderF(CultPlayer player) {
        super(player);
    }

    static String actionName(int action) {
        return switch (action) {
            case ACTION_INTERACT -> "interact";
            case ACTION_ATTACK -> "attack";
            case ACTION_SPECTATE_ENTITY -> "spectateEntity";
            case ACTION_PLACE -> "place";
            case ACTION_USE -> "use";
            case ACTION_PICK -> "pick";
            case ACTION_DIG -> "dig";
            default -> "unknown";
        };
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        onAction(
                event,
                player,
                (packet.action() == ac.cult.cultac.protocol.value.InteractAction.ATTACK
                                && ProtocolVersion.V26_3.protocol()
                                        >= ac.cult.cultac.protocol.ProtocolVersion.V26_1.protocol())
                        ? ACTION_ATTACK
                        : ACTION_INTERACT,
                null);
    }

    @CultPacketHandler
    public void onSpectatorAction(
            PacketReceiveEvent<ServerboundSpectatorAction> event,
            CultPlayer player,
            ServerboundSpectatorAction packet) {
        onAction(event, player, ACTION_SPECTATE_ENTITY, null);
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        onAction(event, player, ACTION_PLACE, null);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        onAction(event, player, ACTION_USE, null);
    }

    @CultPacketHandler("serverbound.pick_item")
    public void onPickItem(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        onAction(event, player, ACTION_PICK, null);
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        onAction(event, player, ACTION_DIG, packet.action());
    }

    private void onAction(PacketReceiveEvent event, CultPlayer player, int action, PlayerAction digAction) {
        if (player.packetOrderProcessor.isSprinting() || player.packetOrderProcessor.isSneaking()) {
            boolean sprinting = player.packetOrderProcessor.isSprinting();
            boolean sneaking = player.packetOrderProcessor.isSneaking();
            if (flag(V.write(verbose()).str(actionName(action)).bool(sprinting).bool(sneaking))
                    && shouldModifyPackets()) {
                if (digAction != null && !canCancel(digAction)) {
                    return; // don't cause a noslow
                }

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
