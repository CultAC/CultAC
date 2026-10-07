package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.api.storage.verbose.Verbose;
import java.util.ArrayList;
import java.util.List;

@CheckData(
        name = "MultiActionsF",
        stableKey = "cult.multiactions.block_and_entity_interact",
        description = "Interacting with a block and an entity in the same tick",
        experimental = true)
public class MultiActionsF extends BlockPlaceCheck implements BlockBreakListener, PostPredictionListener {
    // Shape index == ACTION_* constant value.
    private static final Verbose V =
            Verbose.of("action=place").or("action=entity").or("action=dig");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol());

    private static final int ACTION_PLACE = 0;
    private static final int ACTION_ENTITY = 1;
    private static final int ACTION_DIG = 2;

    private final List<FlagData> flags = new ArrayList<>();
    private boolean entity, block;

    public MultiActionsF(CultPlayer player) {
        super(player);
    }

    private Verbose.Writer writeAction(int action) {
        return V.write(verbose(), action);
    }

    @Override
    public void onBlockPlace(BlockPlace place) {
        block = true;
        if (entity) {
            if (!canSkipTicks()) {
                if (flag(writeAction(ACTION_PLACE)) && shouldModifyPackets() && shouldCancel()) {
                    place.resync();
                }
            } else {
                flags.add(new FlagData(ACTION_PLACE));
            }
        }
    }

    @CultPacketHandler
    public void onInteractEntity(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        onEntityAction(event);
    }

    @CultPacketHandler
    public void onSpectatorAction(
            PacketReceiveEvent<ServerboundSpectatorAction> event,
            CultPlayer player,
            ServerboundSpectatorAction packet) {
        onEntityAction(event);
    }

    private void onEntityAction(PacketReceiveEvent event) {
        entity = true;
        if (block) {
            if (!canSkipTicks()) {
                if (flag(writeAction(ACTION_ENTITY)) && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else {
                flags.add(new FlagData(ACTION_ENTITY));
            }
        }
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!player.packetStateData.lastPacketWasTeleport) {
            block = entity = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick) {
            block = entity = false;
        }
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action == PlayerAction.START_DESTROY_BLOCK
                || blockBreak.action == PlayerAction.STOP_DESTROY_BLOCK) {
            block = true;
            if (entity) {
                if (!canSkipTicks()) {
                    if (flag(writeAction(ACTION_DIG)) && shouldModifyPackets()) {
                        blockBreak.cancel();
                    }
                } else {
                    flags.add(new FlagData(ACTION_DIG));
                }
            }
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!canSkipTicks()) return;

        if (player.isTickingReliablyFor(3)) {
            for (FlagData data : flags) {
                flag(writeAction(data.action()));
            }
        }

        flags.clear();
    }

    private boolean canSkipTicks() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)
                && !(player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                        && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21_2));
    }

    private record FlagData(int action) {}
}
