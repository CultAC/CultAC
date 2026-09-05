package ac.cult.cultac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.type.PostFlyingBlockBreakListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.Pair;
import ac.cult.cultac.utils.nmsutil.Ray;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@CheckData(name = "RotationBreak", stableKey = "cult.breaking.rotation_break", description = "Tried to break a block without looking at it", experimental = true)
public class RotationBreak extends Check implements BlockBreakListener, PostFlyingBlockBreakListener {
    private static final Verbose V = Verbose.of("[pre-flying|post-flying], action={digging}");

    private double flagBuffer = 0; // If the player flags once, force them to play legit, or we will cancel the tick before.
    private boolean ignorePost = false;

    public RotationBreak(CultPlayer player) {
        super(player);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (!player.cameraEntity.isSelf())
            return; // you don't send flying packets when spectating entities
        if (player.inVehicle()) return; // falses
        if (blockBreak.action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) return; // PE DiggingAction.CANCELLED_DIGGING; falses

        if (flagBuffer > 0 && !didRayTraceHit(blockBreak)) {
            ignorePost = true;
            // If the player hit and has flagged this check recently
            if (flag(V.write(verbose()).bool(true)
                    .uint(VerboseTags.enumId(blockBreak.action))) && shouldModifyPackets()) {
                blockBreak.cancel();
            }
        }
    }

    public void onPostFlyingBlockBreak(BlockBreak blockBreak) {
        if (!player.cameraEntity.isSelf())
            return; // you don't send flying packets when spectating entities
        if (player.inVehicle()) return; // falses
        if (blockBreak.action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) return; // PE DiggingAction.CANCELLED_DIGGING; falses

        // Don't flag twice
        if (ignorePost) {
            ignorePost = false;
            return;
        }

        if (didRayTraceHit(blockBreak)) {
            flagBuffer = Math.max(0, flagBuffer - 0.1);
        } else {
            flagBuffer = 1;
            flag(V.write(verbose()).bool(false)
                    .uint(VerboseTags.enumId(blockBreak.action)));
        }
    }

    private boolean didRayTraceHit(BlockBreak blockBreak) {
        SimpleCollisionBox box = new SimpleCollisionBox(blockBreak.position);

        // Start checking if player is in the block
        double minEyeHeight = Double.MAX_VALUE;
        double maxEyeHeight = Double.MIN_VALUE;
        for (double height : player.getPossibleEyeHeights()) {
            minEyeHeight = Math.min(minEyeHeight, height);
            maxEyeHeight = Math.max(maxEyeHeight, height);
        }

        SimpleCollisionBox eyePositions = new SimpleCollisionBox(player.x, player.y + minEyeHeight, player.z, player.x, player.y + maxEyeHeight, player.z);
        eyePositions.expand(player.getMovementThreshold());

        // If the player is inside a block, then they can ray trace through the block and hit the other side of the block
        if (eyePositions.isIntersected(box)) {
            return true;
        }
        // End checking if the player is in the block

        // {yaw, pitch} pairs; the merged pipeline stores yaw in xRot and pitch in yRot
        List<float[]> possibleLookDirs = new ArrayList<>(Arrays.asList(
                new float[]{player.lastTickXRot, player.yRot},
                new float[]{player.xRot, player.yRot}
        ));

        // 1.9+ players could be a tick behind because we don't get skipped ticks
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) {
            possibleLookDirs.add(new float[]{player.lastTickXRot, player.lastTickYRot});
        }

        // 1.7 players do not have any of these issues! They are always on the latest look vector
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_8)) {
            possibleLookDirs = Collections.singletonList(new float[]{player.xRot, player.yRot});
        }


        final double distance = player.compensatedEntities.getSelf().getBlockInteractionRange();
        for (double d : player.getPossibleEyeHeights()) {
            for (float[] lookDir : possibleLookDirs) {
                Ray trace = new Ray(player, player.x, player.y + d, player.z, lookDir[0], lookDir[1]);
                Pair<Vector, BlockFace> intercept = ReachUtils.calculateIntercept(box, trace.getOrigin(), trace.getPointAtDistance(distance));

                if (intercept.first() != null) return true;
            }
        }

        return false;
    }
}
