package ac.grim.grimac.checks.impl.scaffolding;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockPlaceCheck;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

@CheckData(name = "MultiPlace", stableKey = "grim.scaffolding.multi_place", description = "Placed multiple blocks in a tick", experimental = true)
public class MultiPlace extends BlockPlaceCheck implements PostPredictionListener {
    private static final Verbose V = Verbose.of("face={face}, lastFace={face}, cursor={cursor}, lastCursor={cursor}, pos={mcpos}, lastPos={mcpos}");

    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private final List<FlagData> flags = new ArrayList<>();
    private boolean hasPlaced;
    private BlockFace lastFace;
    private Vec3 lastCursor;
    private BlockPos lastPos;

    public MultiPlace(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {
        final BlockFace face = place.getDirection();
        final Vec3 cursor = place.getCursor();
        final BlockPos pos = place.getPlacedAgainstBlockLocation();

        if (hasPlaced && (face != lastFace || !cursor.equals(lastCursor) || !pos.equals(lastPos))) {
            final int faceId = VerboseTags.enumId(face);
            final int lastFaceId = VerboseTags.enumId(lastFace);
            if (!canSkipTicks()) {
                var buf = V.write(verbose()).uint(faceId).uint(lastFaceId)
                        .cursor((float) cursor.x, (float) cursor.y, (float) cursor.z)
                        .cursor((float) lastCursor.x, (float) lastCursor.y, (float) lastCursor.z)
                        .mcPos(pos.getX(), pos.getY(), pos.getZ())
                        .mcPos(lastPos.getX(), lastPos.getY(), lastPos.getZ());
                if (flag(buf)
                        && shouldModifyPackets() && shouldCancel()) {
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
    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            hasPlaced = false;
        }
    }

    // isTickPacket: the 1.21.2+ end-of-tick packet also ends the client tick when no movement was received
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
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
                flag(V.write(verbose()).uint(data.face()).uint(data.lastFace())
                        .cursor((float) data.cursor().x, (float) data.cursor().y, (float) data.cursor().z)
                        .cursor((float) data.lastCursor().x, (float) data.lastCursor().y, (float) data.lastCursor().z)
                        .mcPos(data.pos().getX(), data.pos().getY(), data.pos().getZ())
                        .mcPos(data.lastPos().getX(), data.lastPos().getY(), data.lastPos().getZ()));
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

    private record FlagData(
            int face,
            int lastFace,
            Vec3 cursor,
            Vec3 lastCursor,
            BlockPos pos,
            BlockPos lastPos) {}
}
