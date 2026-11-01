package ac.cult.cultac.checks.impl.breaking;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.EyePositions;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;

@CheckData(
        name = "PositionBreakA",
        stableKey = "cult.breaking.position_break_a",
        description = "Tried to break a block face from an impossible eye position")
public class PositionBreakA extends Check implements BlockBreakListener {
    private static final Verbose V = Verbose.of("action={digging}, face={face}");

    private final ClientVisibilityProcessor visibility;
    private boolean didLastMovementIncludePosition;

    public PositionBreakA(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player);
        this.visibility = visibility;
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        didLastMovementIncludePosition = packet.hasPosition();
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (player.inVehicle()
                || blockBreak.action == PlayerAction.ABORT_DESTROY_BLOCK
                || BlockIds.is(blockBreak.block, BlockIds.REDSTONE_WIRE)) return;

        SimpleCollisionBox combined = blockBreak.getCombinedBox();

        SimpleCollisionBox eyePositions = EyePositions.box(player, player.getPossibleEyeHeights());
        if (!didLastMovementIncludePosition) {
            eyePositions.expand(player.getMovementThreshold());
        }

        // VoxelShape#clip returns an inside hit, on any face, when eye + 0.001 * (rayEnd - eye) lies in the
        // outline. The pick ray is a unit view vector times the pick range, so that point is within
        // 0.001 * range of the eye.
        double insideSample = Math.nextUp(0.001 * visibility.maxPickRange());
        if (eyePositions.copy().expand(insideSample).isIntersected(combined)) {
            return;
        }

        // Compare the player's possible eye positions against the clicked face
        boolean flag = switch (blockBreak.face) {
            case NORTH -> eyePositions.minZ > combined.minZ; // Z- face
            case SOUTH -> eyePositions.maxZ < combined.maxZ; // Z+ face
            case EAST -> eyePositions.maxX < combined.maxX; // X+ face
            case WEST -> eyePositions.minX > combined.minX; // X- face
            case UP -> eyePositions.maxY < combined.maxY; // Y+ face
            case DOWN -> eyePositions.minY > combined.minY; // Y- face
            default -> false;
        };

        if (flag
                && flag(V.write(verbose())
                        .uint(VerboseCodecs.digging(blockBreak.action))
                        .uint(VerboseTags.enumId(blockBreak.face)))
                && shouldModifyPackets()) {
            blockBreak.cancel();
        }
    }
}
