package ac.cult.cultac.checks.impl.scaffolding;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.math.Vec3;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import java.util.ArrayList;
import java.util.List;

@CheckData(
        name = "MultiPlace",
        stableKey = "cult.scaffolding.multi_place",
        description = "Placed multiple blocks in a tick",
        experimental = true)
public class MultiPlace extends BlockPlaceCheck implements PostPredictionListener {
    private static final Verbose V = Verbose.of(
            "face={face}, lastFace={face}, cursor={cursor}, lastCursor={cursor}, pos={mcpos}, lastPos={mcpos}");

    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol());

    private final List<FlagData> flags = new ArrayList<>();
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
            if (!canSkipTicks()) {
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
            } else {
                flags.add(new FlagData(faceId, lastFaceId, cursor, lastCursor, pos, lastPos));
            }
        }

        lastFace = face;
        lastCursor = cursor;
        lastPos = pos;
        hasPlaced = true;
    }

    // isTickPacket (guide §"Removed Check helpers"): a non-teleport movement packet ends the client tick
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
        if (!player.cameraEntity.isSelf()
                || (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                        && !player.packetStateData.receivedMovementThisClientTick)) {
            hasPlaced = false;
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!canSkipTicks()) return;

        if (player.isTickingReliablyFor(3)) {
            for (FlagData data : flags) {
                flag(V.write(verbose())
                        .uint(data.face())
                        .uint(data.lastFace())
                        .cursor((float) data.cursor().x, (float) data.cursor().y, (float) data.cursor().z)
                        .cursor((float) data.lastCursor().x, (float) data.lastCursor().y, (float) data.lastCursor().z)
                        .mcPos(data.pos().getX(), data.pos().getY(), data.pos().getZ())
                        .mcPos(
                                data.lastPos().getX(),
                                data.lastPos().getY(),
                                data.lastPos().getZ()));
            }
        }

        flags.clear();
    }

    // Only clients without reliable tick-end packets may skip movement ticks.
    private boolean canSkipTicks() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)
                && !(player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                        && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21_2));
    }

    private record FlagData(int face, int lastFace, Vec3 cursor, Vec3 lastCursor, BlockPos pos, BlockPos lastPos) {}
}
