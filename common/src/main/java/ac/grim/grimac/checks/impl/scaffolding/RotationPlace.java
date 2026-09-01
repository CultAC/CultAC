package ac.grim.grimac.checks.impl.scaffolding;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockPlaceCheck;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.Pair;
import ac.grim.grimac.utils.nmsutil.Ray;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@CheckData(name = "RotationPlace", stableKey = "grim.scaffolding.rotation_place", description = "Placed a block while not looking at it")
public class RotationPlace extends BlockPlaceCheck {
    private static final Verbose V = Verbose.of("[pre-flying|post-flying]");

    private double flagBuffer = 0; // If the player flags once, force them to play legit, or we will cancel the tick before.
    private boolean ignorePost = false;

    public RotationPlace(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {
        if (place.getMaterial() == Material.SCAFFOLDING) return;
        if (!player.cameraEntity.isSelf())
            return; // you don't send flying packets when spectating entities
        if (player.inVehicle()) return;
        if (flagBuffer > 0 && !didRayTraceHit(place)) {
            ignorePost = true;
            // If the player hit and has flagged this check recently
            if (flag(V.write(verbose()).bool(true)) && shouldModifyPackets() && shouldCancel()) {
                place.resync();  // Deny the block placement.
            }
        }
    }

    // Use post flying because it has the correct rotation, and can't false easily.
    @Override
    public void onPostFlyingBlockPlace(BlockPlace place) {
        if (place.getMaterial() == Material.SCAFFOLDING) return;
        if (!player.cameraEntity.isSelf())
            return; // you don't send flying packets when spectating entities
        if (player.inVehicle()) return;

        // Don't flag twice
        if (ignorePost) {
            ignorePost = false;
            return;
        }

        // Ray trace to try and hit the target block.
        boolean hit = didRayTraceHit(place);
        // This can false with rapidly moving yaw in 1.8+ clients
        if (!hit) {
            flagBuffer = 1;
            flag(V.write(verbose()).bool(false));
        } else {
            flagBuffer = Math.max(0, flagBuffer - 0.1);
        }
    }

    private boolean didRayTraceHit(BlockPlace place) {
        SimpleCollisionBox box = new SimpleCollisionBox(place.getPlacedAgainstBlockLocation());


        List<Vector3f> possibleLookDirs = new ArrayList<>(Arrays.asList(
                new Vector3f(player.xRot, player.yRot, 0),
                new Vector3f(player.lastTickXRot, player.yRot, 0)
        ));

        final List<Double> possibleEyeHeights = player.getPossibleEyeHeights();

        // Start checking if player is in the block
        double minEyeHeight = Double.MAX_VALUE;
        double maxEyeHeight = Double.MIN_VALUE;
        for (double height : possibleEyeHeights) {
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

        // 1.9+ players could be a tick behind because we don't get skipped ticks
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) {
            possibleLookDirs.add(new Vector3f(player.lastTickXRot, player.lastTickYRot, 0));
        }

        // 1.7 players do not have any of these issues! They are always on the latest look vector
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_8)) {
            possibleLookDirs = Collections.singletonList(new Vector3f(player.xRot, player.yRot, 0));
        }

        final double distance = player.compensatedEntities.getSelf().getBlockInteractionRange();
        for (double d : possibleEyeHeights) {
            for (Vector3f lookDir : possibleLookDirs) {
                // x, y, z are correct for the block placement even after post tick because of code elsewhere
                Vec3 starting = new Vec3(player.x, player.y + d, player.z);
                // xRot and yRot are a tick behind
                Ray trace = new Ray(player, starting.x, starting.y, starting.z, lookDir.x, lookDir.y);
                Pair<Vector, BlockFace> intercept = ReachUtils.calculateIntercept(box, trace.getOrigin(), trace.getPointAtDistance(distance));

                if (intercept.getFirst() != null) return true;
            }
        }

        return false;
    }

}
