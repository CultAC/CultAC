package ac.grim.grimac.checks.impl.multiactions;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.type.BlockPlaceCheck;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;

import java.util.ArrayList;
import java.util.List;

@CheckData(name = "MultiActionsF", stableKey = "grim.multiactions.block_and_entity_interact", description = "Interacting with a block and an entity in the same tick", experimental = true)
public class MultiActionsF extends BlockPlaceCheck implements BlockBreakListener, PostPredictionListener {
    // Shape index == ACTION_* constant value.
    private static final Verbose V = Verbose.of("action=place").or("action=entity").or("action=dig");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private static final int ACTION_PLACE = 0;
    private static final int ACTION_ENTITY = 1;
    private static final int ACTION_DIG = 2;

    private final List<FlagData> flags = new ArrayList<>();
    private boolean entity, block;

    public MultiActionsF(GrimPlayer player) {
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

    @GrimPacketHandler
    public void onInteractEntity(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
        onEntityAction(event);
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
        onEntityAction(event);
    }


    @GrimPacketHandler
    public void onSpectatorAction(PacketReceiveEvent event, GrimPlayer player, ServerboundSpectatorActionPacket packet) {
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
    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!player.packetStateData.lastPacketWasTeleport) {
            block = entity = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick) {
            block = entity = false;
        }
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK || blockBreak.action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
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
