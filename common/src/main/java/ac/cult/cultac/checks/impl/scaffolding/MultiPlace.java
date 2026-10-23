package ac.cult.cultac.checks.impl.scaffolding;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.math.Vec3;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;

@CheckData(
        name = "MultiPlace",
        stableKey = "cult.scaffolding.multi_place",
        description = "Placed multiple blocks in a tick",
        experimental = true)
public class MultiPlace extends BlockPlaceCheck {
    private static final Verbose V = Verbose.of(
            "face={face}, lastFace={face}, cursor={cursor}, lastCursor={cursor}, pos={mcpos}, lastPos={mcpos}");

    private boolean hasPlaced;
    private Direction lastFace;
    private Vec3 lastCursor;
    private BlockPos lastPos;

    public MultiPlace(CultPlayer player) {
        super(player);
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {
        final Direction face = place.getDirection();
        final Vec3 cursor = place.getCursor();
        final BlockPos pos = place.getPlacedAgainstBlockLocation();

        if (hasPlaced && (face != lastFace || !cursor.equals(lastCursor) || !pos.equals(lastPos))) {
            final int faceId = VerboseTags.enumId(face);
            final int lastFaceId = VerboseTags.enumId(lastFace);
            var buf = V.write(verbose())
                    .uint(faceId)
                    .uint(lastFaceId)
                    .cursor((float) cursor.x, (float) cursor.y, (float) cursor.z)
                    .cursor((float) lastCursor.x, (float) lastCursor.y, (float) lastCursor.z)
                    .mcPos(pos.getX(), pos.getY(), pos.getZ())
                    .mcPos(lastPos.getX(), lastPos.getY(), lastPos.getZ());
            if (flag(buf) && shouldModifyPackets() && shouldCancel()) {
                place.resync();
            }
        }

        lastFace = face;
        lastCursor = cursor;
        lastPos = pos;
        hasPlaced = true;
    }

    // isTickPacket: a non-teleport movement packet ends the client tick
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            hasPlaced = false;
        }
    }

    // isTickPacket: the 1.21.2+ end-of-tick packet also ends the client tick when no movement was received
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.receivedMovementThisClientTick) {
            hasPlaced = false;
        }
    }
}
