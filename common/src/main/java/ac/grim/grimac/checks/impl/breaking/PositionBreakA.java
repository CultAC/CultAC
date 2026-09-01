package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.Blocks;

@CheckData(name = "PositionBreakA", stableKey = "grim.breaking.position_break_a", description = "Tried to break a block face from an impossible eye position")
public class PositionBreakA extends Check implements BlockBreakListener {
    private static final Verbose V = Verbose.of("action={digging}, face={face}");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());


    private boolean didLastMovementIncludePosition;

    public PositionBreakA(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        didLastMovementIncludePosition = packet.hasPosition();
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (player.inVehicle()
                || blockBreak.action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK // PE DiggingAction.CANCELLED_DIGGING
                || blockBreak.block.is(Blocks.REDSTONE_WIRE)
        ) return;

        SimpleCollisionBox combined = blockBreak.getCombinedBox();

        double minEyeHeight = Double.MAX_VALUE;
        double maxEyeHeight = Double.MIN_VALUE;
        for (double height : player.getPossibleEyeHeights()) {
            minEyeHeight = Math.min(minEyeHeight, height);
            maxEyeHeight = Math.max(maxEyeHeight, height);
        }

        SimpleCollisionBox eyePositions = new SimpleCollisionBox(player.x, player.y + minEyeHeight, player.z, player.x, player.y + maxEyeHeight, player.z);
        if (!didLastMovementIncludePosition || canSkipTicks()) {
            eyePositions.expand(player.getMovementThreshold());
        }

        // If the player is inside a block, then they can ray trace through the block and hit the other side of the block
        if (eyePositions.isIntersected(combined)) {
            return;
        }

        // So now we have the player's possible eye positions
        // So then look at the face that the player has clicked
        boolean flag = switch (blockBreak.face) {
            case NORTH -> eyePositions.minZ > combined.minZ; // Z- face
            case SOUTH -> eyePositions.maxZ < combined.maxZ; // Z+ face
            case EAST -> eyePositions.maxX < combined.maxX; // X+ face
            case WEST -> eyePositions.minX > combined.minX; // X- face
            case UP -> eyePositions.maxY < combined.maxY; // Y+ face
            case DOWN -> eyePositions.minY > combined.minY; // Y- face
            default -> false;
        };

        if (flag && flag(V.write(verbose())
                .uint(VerboseTags.enumId(blockBreak.action))
                .uint(VerboseTags.enumId(blockBreak.face)))
                && shouldModifyPackets()) {
            blockBreak.cancel();
        }
    }

    private boolean canSkipTicks() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)
                && !(player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21_2));
    }
}
