package ac.cult.cultac.checks.impl.breaking;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;

@CheckData(
        name = "MultiBreak",
        stableKey = "cult.breaking.multi_break",
        description = "Tried to break multiple different blocks in the same movement tick",
        experimental = true)
public class MultiBreak extends Check implements BlockBreakListener {
    private static final Verbose V = Verbose.of("face={face}, lastFace={face}, pos={mcpos}, lastPos={mcpos}");

    private boolean hasBroken;
    private Direction lastFace;
    private BlockPos lastPos;

    public MultiBreak(CultPlayer player) {
        super(player);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (!player.cameraEntity.isSelf()) {
            hasBroken = false;
        }

        if (blockBreak.action == PlayerAction.ABORT_DESTROY_BLOCK) { // PE DiggingAction.CANCELLED_DIGGING
            return;
        }

        if (hasBroken && (blockBreak.face != lastFace || !blockBreak.position.equals(lastPos))) {
            final int face = VerboseTags.enumId(blockBreak.face);
            final int previousFace = VerboseTags.enumId(lastFace);
            var buf = V.write(verbose())
                    .uint(face)
                    .uint(previousFace)
                    .mcPos(blockBreak.position.getX(), blockBreak.position.getY(), blockBreak.position.getZ())
                    .mcPos(lastPos.getX(), lastPos.getY(), lastPos.getZ());
            if (flag(buf) && shouldModifyPackets()) {
                blockBreak.cancel();
            }
        }

        lastFace = blockBreak.face;
        lastPos = blockBreak.position;
        hasBroken = true;
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            hasBroken = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.receivedMovementThisClientTick) {
            hasBroken = false;
        }
    }
}
