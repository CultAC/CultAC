package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.api.storage.verbose.Verbose;
import java.util.ArrayDeque;

@CheckData(
        name = "PacketOrderF",
        stableKey = "cult.packetorder.input_tick_to_sneak_sprint_order",
        description = "Sent action packets after sneak or sprint input in an invalid order",
        experimental = true)
public class PacketOrderF extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of("action={str}, sprinting={bool}, sneaking={bool}");

    static final int ACTION_INTERACT = 0;
    static final int ACTION_ATTACK = 1;
    static final int ACTION_SPECTATE_ENTITY = 2;
    static final int ACTION_PLACE = 3;
    static final int ACTION_USE = 4;
    static final int ACTION_PICK = 5;
    static final int ACTION_DIG = 6;
    static final int ACTION_OPEN_INVENTORY = 7;

    public PacketOrderF(CultPlayer player) {
        super(player);
    }

    private final ArrayDeque<FlagData> flags = new ArrayDeque<>();

    static String actionName(int action) {
        return switch (action) {
            case ACTION_INTERACT -> "interact";
            case ACTION_ATTACK -> "attack";
            case ACTION_SPECTATE_ENTITY -> "spectateEntity";
            case ACTION_PLACE -> "place";
            case ACTION_USE -> "use";
            case ACTION_PICK -> "pick";
            case ACTION_DIG -> "dig";
            case ACTION_OPEN_INVENTORY -> "openInventory";
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

    @CultPacketHandler
    public void onClientCommand(
            PacketReceiveEvent<ServerboundClientCommand> event, CultPlayer player, ServerboundClientCommand packet) {
        // The 26.2 enum has no OPEN_INVENTORY_ACHIEVEMENT (removed in 1.12)
        if (packet.action().name().equals("OPEN_INVENTORY_ACHIEVEMENT")) {
            onAction(event, player, ACTION_OPEN_INVENTORY, null);
        }
    }

    private void onAction(PacketReceiveEvent event, CultPlayer player, int action, PlayerAction digAction) {
        if (player.packetOrderProcessor.isSprinting() || player.packetOrderProcessor.isSneaking()) {
            boolean sprinting = player.packetOrderProcessor.isSprinting();
            boolean sneaking = player.packetOrderProcessor.isSneaking();
            if (!player.canSkipTicks()) {
                if (flag(V.write(verbose())
                                .str(actionName(action))
                                .bool(sprinting)
                                .bool(sneaking))
                        && shouldModifyPackets()) {
                    if (digAction != null && !canCancel(digAction)) {
                        return; // don't cause a noslow
                    }

                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else {
                flags.add(new FlagData(action, sprinting, sneaking));
            }
        }
    }

    private boolean canCancel(PlayerAction action) {
        return action != PlayerAction.RELEASE_USE_ITEM
                && ((action != PlayerAction.DROP_ITEM && action != PlayerAction.DROP_ALL_ITEMS)
                        || player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8));
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!player.canSkipTicks()) return;

        if (player.isTickingReliablyFor(3)) {
            for (FlagData data : flags) {
                flag(V.write(verbose())
                        .str(actionName(data.action()))
                        .bool(data.sprinting())
                        .bool(data.sneaking()));
            }
        }

        flags.clear();
    }

    private record FlagData(int action, boolean sprinting, boolean sneaking) {}
}
