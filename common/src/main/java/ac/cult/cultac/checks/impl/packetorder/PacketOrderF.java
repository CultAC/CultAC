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
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.ArrayDeque;

@CheckData(name = "PacketOrderF", stableKey = "cult.packetorder.input_tick_to_sneak_sprint_order", description = "Sent action packets after sneak or sprint input in an invalid order", experimental = true)
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
    public void onInteract(PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        onAction(event, player, ACTION_INTERACT, null);
    }


    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onAction(event, player, ACTION_ATTACK, null);
    }


    // 26.1 uses a required entity id; 26.2 also permits a spectator action without a target.
    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSpectateEntityPacket")
    public void onSpectateEntity(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onSpectatorAction(event, player, packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket")
    public void onSpectatorAction(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onAction(event, player, ACTION_SPECTATE_ENTITY, null);
    }


    @CultPacketHandler
    public void onUseItemOn(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemOnPacket packet) {
        onAction(event, player, ACTION_PLACE, null);
    }


    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemPacket packet) {
        onAction(event, player, ACTION_USE, null);
    }


    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundPickItemPacket")
    public void onPickItem(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onAction(event, player, ACTION_PICK, null);
    }


    @CultPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerActionPacket packet) {
        onAction(event, player, ACTION_DIG, packet.getAction());
    }


    @CultPacketHandler
    public void onClientCommand(PacketReceiveEvent event, CultPlayer player, ServerboundClientCommandPacket packet) {
        // The 26.2 enum has no OPEN_INVENTORY_ACHIEVEMENT (removed in 1.12)
        if (packet.getAction().name().equals("OPEN_INVENTORY_ACHIEVEMENT")) {
            onAction(event, player, ACTION_OPEN_INVENTORY, null);
        }
    }

    private void onAction(PacketReceiveEvent event, CultPlayer player, int action, ServerboundPlayerActionPacket.Action digAction) {
        if (player.packetOrderProcessor.isSprinting() || player.packetOrderProcessor.isSneaking()) {
            boolean sprinting = player.packetOrderProcessor.isSprinting();
            boolean sneaking = player.packetOrderProcessor.isSneaking();
            if (!player.canSkipTicks()) {
                if (flag(V.write(verbose()).str(actionName(action)).bool(sprinting).bool(sneaking)) && shouldModifyPackets()) {
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


    private boolean canCancel(ServerboundPlayerActionPacket.Action action) {
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
                flag(V.write(verbose()).str(actionName(data.action())).bool(data.sprinting()).bool(data.sneaking()));
            }
        }

        flags.clear();
    }

    private record FlagData(int action, boolean sprinting, boolean sneaking) {}
}
