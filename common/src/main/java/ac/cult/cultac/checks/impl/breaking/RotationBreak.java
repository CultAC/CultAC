package ac.cult.cultac.checks.impl.breaking;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.EyePositions;
import ac.cult.cultac.checks.RiderPickYaw;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.PostFlyingBlockBreakListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Ray;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import ac.grim.grimac.api.storage.verbose.Verbose;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@CheckData(
        name = "RotationBreak",
        stableKey = "cult.breaking.rotation_break",
        description = "Tried to break a block without looking at it",
        experimental = true)
public class RotationBreak extends Check
        implements BlockBreakListener, PostFlyingBlockBreakListener, ClientTickEndListener {
    private static final Verbose V = Verbose.of("[pre-flying|post-flying], action={digging}");

    private double flagBuffer =
            0; // If the player flags once, force them to play legit, or we will cancel the tick before.
    private boolean ignorePost = false;
    private final ClientVisibilityProcessor visibility;
    // From 26.3, a boat rider's breaks of this client tick and where its pick saw it from.
    private final List<TurnedBreak> turnedBreaks = new ArrayList<>();
    private final RiderPickYaw pickYaw = new RiderPickYaw();

    public RotationBreak(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player);
        this.visibility = visibility;
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (!player.cameraEntity.isSelf()) return; // you don't send flying packets when spectating entities
        if (blockBreak.action == PlayerAction.ABORT_DESTROY_BLOCK) return; // falses

        if (flagBuffer <= 0) return;
        float yaw = pickYaw.beforeRot(player);
        if (!Float.isNaN(yaw)
                && !didRayTraceHit(
                        blockBreak, player.compensatedEntities.pickPositions(), player.getPoseEyeHeights(), yaw)) {
            ignorePost = true;
            // If the player hit and has flagged this check recently
            if (flag(V.write(verbose()).bool(true).uint(VerboseCodecs.digging(blockBreak.action)))
                    && shouldModifyPackets()) {
                blockBreak.cancel();
            }
        }
    }

    public void onPostFlyingBlockBreak(BlockBreak blockBreak) {
        if (!player.cameraEntity.isSelf()) return; // you don't send flying packets when spectating entities
        if (blockBreak.action == PlayerAction.ABORT_DESTROY_BLOCK) return; // falses

        // Don't flag twice
        if (ignorePost) {
            ignorePost = false;
            return;
        }

        List<Vec3> origins = player.compensatedEntities.pickPositions();
        if (player.rotationTurnedAfterPick()) {
            turnedBreaks.add(new TurnedBreak(blockBreak, origins));
            return;
        }
        judge(blockBreak, origins, player.xRot);
    }

    // After the tick's vehicle move and the riding tree's interpolation, which give the boat's turn and yaw.
    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        pickYaw.onTickEnd(player);
        if (turnedBreaks.isEmpty()) return;
        float yaw = player.yawBeforeRideTurn(player.xRot);
        if (!Float.isNaN(yaw)) {
            for (TurnedBreak turned : turnedBreaks) judge(turned.blockBreak(), turned.origins(), yaw);
        }
        turnedBreaks.clear();
    }

    private void judge(BlockBreak blockBreak, List<Vec3> origins, float yaw) {
        if (didRayTraceHit(blockBreak, origins, blockBreak.eyeHeights, yaw)) {
            flagBuffer = Math.max(0, flagBuffer - 0.1);
        } else {
            flagBuffer = 1;
            flag(V.write(verbose()).bool(false).uint(VerboseCodecs.digging(blockBreak.action)));
        }
    }

    private boolean didRayTraceHit(BlockBreak blockBreak, List<Vec3> origins, List<Double> eyeHeights, float yaw) {
        SimpleCollisionBox box = new SimpleCollisionBox(blockBreak.position);

        // Start checking if player is in the block
        SimpleCollisionBox eyePositions = EyePositions.box(origins, eyeHeights);
        eyePositions.expand(player.getMovementThreshold());

        // If the player is inside a block, then they can ray trace through the block and hit the other side of the
        // block
        if (eyePositions.isIntersected(box)) {
            return true;
        }
        // End checking if the player is in the block

        // {yaw, pitch} pairs; the merged pipeline stores yaw in xRot and pitch in yRot
        List<float[]> possibleLookDirs = new ArrayList<>(
                Arrays.asList(new float[] {player.lastTickXRot, player.yRot}, new float[] {yaw, player.yRot}));

        // 1.9+ players could be a tick behind because we don't get skipped ticks
        possibleLookDirs.add(new float[] {player.lastTickXRot, player.lastTickYRot});

        List<SimpleCollisionBox> outline = visibility.outlineBoxes(blockBreak.position);
        for (Vec3 origin : origins) {
            for (double d : eyeHeights) {
                for (float[] lookDir : possibleLookDirs) {
                    Ray trace = new Ray(player, origin.x, origin.y + d, origin.z, lookDir[0], lookDir[1]);
                    if (ReachUtils.pickHits(player, blockBreak.position, outline, trace)) return true;
                }
            }
        }

        return false;
    }

    private record TurnedBreak(BlockBreak blockBreak, List<Vec3> origins) {}
}
