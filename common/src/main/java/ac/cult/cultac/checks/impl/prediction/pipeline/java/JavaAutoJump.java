package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientSprintState;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.List;

/** Source-proven automatic input: LocalPlayer#move/updateAutoJump, then next tick's aiStep/makeJump. */
public final class JavaAutoJump extends CultProcessor implements PostPredictionListener {
    private static final double KEPT_IN_PLACE = Math.sqrt(1.0E-7);

    private boolean queued;
    private boolean jumpedThisTick;
    private boolean possible;
    private int movedClientTick = -1;

    public JavaAutoJump(CultPlayer player) {
        super(player);
    }

    public void reset() {
        queued = jumpedThisTick = false;
    }

    public boolean jumpedThisTick() {
        return jumpedThisTick;
    }

    /**
     * Whether LocalPlayer#updateAutoJump can have set autoJumpTime in the client tick {@code clientTick}. It is false
     * only where the engine proves updateAutoJump returned early for every position and input it allows: from the
     * move the simulation ran for that tick, or from canAutoJump's known prerequisites when it ran none.
     */
    public boolean canFireIn(int clientTick) {
        return movedClientTick == clientTick ? possible : !keysCannotFire();
    }

    public boolean hasManualMovementInput() {
        var input = player.packetStateData.knownInput;
        return input.movingNoJump() || input.jump() && !jumpedThisTick;
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (player.isBedrockMovement() || complete.isTeleport()) return;
        jumpedThisTick = queued;
        PredictionResult result = complete.getPredictionResult();
        queued = !complete.isExempt() && !result.hasEffectiveFlags() && schedulesJump(result);
        movedClientTick = player.packetStateData.acceptedClientTick;
        possible = !provablyCannotFire(result);
    }

    /** A client tick that sent no position, simulated from the player's position under the movement exemption. */
    public void onExemptNoPositionTick(PredictionResult result) {
        if (player.isBedrockMovement()) return;
        movedClientTick = player.packetStateData.acceptedClientTick;
        possible = !provablyCannotFire(result);
    }

    boolean schedulesJump(PredictionResult result) {
        return JavaFallDistance.landsOnClip(
                        result.getCollideAxisData().getYNeg(),
                        result.getTarget().y,
                        result.getSimulationContext().getLastStuckSpeed().y)
                && canFire(
                        result.getTarget(),
                        sprintOutcome(result),
                        result.getSimulationContext().getJumpAmplifier(),
                        result.getSimulationContext().getWorldData().getMainSupportingBlockPos());
    }

    /**
     * Whether updateAutoJump returns early for every client position within {@link #positionMargin} of the player's
     * and every attempted movement the move's candidates allow: canAutoJump's prerequisites fail, the move cannot
     * have ended on the ground, the ceiling check stops it, or no collision box crosses its look-ahead rays' height.
     * An unloaded chunk, or an entity's collision box, leaves it unproven.
     */
    private boolean provablyCannotFire(PredictionResult result) {
        if (keysCannotFire()) return true;
        SimulationContext context = result.getSimulationContext();
        if (context == null) return false;
        if (BlockProperties.onHoneyBlock(
                player,
                context.getWorldData().getMainSupportingBlockPos(),
                new Vec3(player.x, player.y, player.z))) return true;
        double margin = positionMargin();
        SimpleCollisionBox end = GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
        return cannotHaveLanded(result, end, margin) || ceilingBlocks(end, margin) || nothingAhead(context, margin);
    }

    // LocalPlayer#sendPosition withholds a position within the movement threshold of the last one it sent, and the
    // simulation places a withheld position within the threshold of that one.
    private double positionMargin() {
        return 2 * player.getMovementThreshold() + 1.0E-6;
    }

    // LocalPlayer#canAutoJump needs movement input without shift, outside a vehicle.
    private boolean keysCannotFire() {
        if (player.isSneaking || player.inVehicle()) return true;
        var keys = player.packetStateData.knownInput;
        return keys.left() == keys.right() && keys.forward() == keys.backward();
    }

    /**
     * Entity#move grounds a move that ends on a collider: the final box's minY at a collider's Y coordinate after a
     * landing or one of Entity#collide's steps, its maxY at one after a step a ceiling clipped, or the box kept in
     * place within sqrt(1.0E-7) above one by the setPos guard. The colliders lie within the reach of the attempted
     * horizontal movement from the start box: the largest of the candidates' extents, plus a block.
     */
    private boolean cannotHaveLanded(PredictionResult result, SimpleCollisionBox end, double margin) {
        List<PredictionResult> candidates = new ArrayList<>();
        candidates.add(result);
        candidates.addAll(result.getRealities());
        double reach = -1;
        for (PredictionResult candidate : candidates) {
            if (candidate.getValidMovements() == null) continue;
            SimpleCollisionBox extents = candidate.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
            if (extents == null) continue;
            reach = Math.max(
                    reach,
                    Math.max(
                            Math.max(Math.abs(extents.minX), Math.abs(extents.maxX)),
                            Math.max(Math.abs(extents.minZ), Math.abs(extents.maxZ))));
        }
        SimpleCollisionBox from = result.getSimulationContext().getFromActualPose();
        if (reach < 0 || from == null) return false;
        reach += 1.0;
        double low = end.minY - KEPT_IN_PLACE - margin, high = end.minY + margin;
        var region = new SimpleCollisionBox(
                Math.min(from.minX, end.minX) - reach,
                low - 1.0E-3,
                Math.min(from.minZ, end.minZ) - reach,
                Math.max(from.maxX, end.maxX) + reach,
                end.maxY + margin + 1.0E-3,
                Math.max(from.maxZ, end.maxZ) + reach);
        if (!JavaAutoJumpGeometry.loaded(player, region) || JavaAutoJumpGeometry.hasEntityCollision(player, region))
            return false;
        for (SimpleCollisionBox box : JavaAutoJumpGeometry.blockCollisions(player, region)) {
            for (double y : new double[] {box.minY, box.maxY}) {
                if (y >= low && y <= high || Math.abs(y - end.maxY) <= margin) return false;
            }
        }
        return true;
    }

    // updateAutoJump returns when the block at the box's top, or the one above it, has a collision shape.
    private boolean ceilingBlocks(SimpleCollisionBox end, double margin) {
        for (double dx : new double[] {-margin, margin}) {
            for (double dy : new double[] {-margin, margin}) {
                for (double dz : new double[] {-margin, margin}) {
                    int x = (int) Math.floor(player.x + dx),
                            y = (int) Math.floor(end.maxY + dy),
                            z = (int) Math.floor(player.z + dz);
                    if (player.compensatedWorld.getChunk(x >> 4, z >> 4) == null) return false;
                    if (JavaAutoJumpGeometry.block(player, x, y, z).isEmpty()
                            && JavaAutoJumpGeometry.block(player, x, y + 1, z).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    /**
     * updateAutoJump's rays run at the position's Y plus 0.51F, offset by half the width, to max(getSpeed() * 7, the
     * move's length) past the move; only a box that crosses that height can stop them.
     */
    private boolean nothingAhead(SimulationContext context, double margin) {
        double speed = 0;
        for (boolean canonical : new boolean[] {true, false}) {
            speed = Math.max(
                    speed,
                    player.compensatedEntities.getMaxPlayerMovementSpeed(canonical, ClientSprintState.Outcome.ANY));
        }
        Vec3 start = context.getStart();
        double moved = Math.hypot(player.x - start.x, player.z - start.z) + 2 * margin;
        double reach = moved
                + Math.max(7 * speed, Math.max(moved, speed))
                + player.pose.width * player.getScale() / 2
                + margin
                + 1.0E-3;
        double height = player.y + 0.51F;
        var region = new SimpleCollisionBox(
                player.x - reach,
                height - margin - 1.0E-3,
                player.z - reach,
                player.x + reach,
                height + margin + 1.0E-3,
                player.z + reach);
        if (!JavaAutoJumpGeometry.loaded(player, region)
                || JavaAutoJumpGeometry.hasEntityCollision(player, region.copy().expand(0, 1, 0))) return false;
        for (SimpleCollisionBox box : JavaAutoJumpGeometry.blockCollisions(player, region)) {
            if (box.minY < height + margin && box.maxY > height - margin) return false;
        }
        return true;
    }

    private static ClientSprintState.Outcome sprintOutcome(PredictionResult result) {
        PredVector start = result.getInitialStartingVel();
        return start == null ? ClientSprintState.Outcome.ANY : start.getSprintOutcome();
    }

    // LocalPlayer#canAutoJump's remaining prerequisites and updateAutoJump's geometry for a move of {@code move}
    // ending at the player's position.
    private boolean canFire(
            Vec3 move, ClientSprintState.Outcome sprint, Integer amplifier, MainSupportingBlockData support) {
        if (player.isSneaking
                || player.inVehicle()
                || BlockProperties.onHoneyBlock(player, support, new Vec3(player.x, player.y, player.z)))
            return false;
        var keys = player.packetStateData.knownInput;
        Vec3 input = new Vec3(
                (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0),
                0,
                (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0));
        if (input.horizontalDistanceSqr() == 0) return false;
        float yaw = player.xRot * ((float) Math.PI / 180.0F);
        float sin = player.trigHandler.sin(yaw), cos = player.trigHandler.cos(yaw);
        float speed = (float) player.compensatedEntities.getMaxPlayerMovementSpeed(false, sprint);
        Vec3 begin = new Vec3(player.x, player.y, player.z);
        float dx = (float) move.x, dz = (float) move.z;
        Vec3 end = begin.add(dx, 0, dz), diff = new Vec3(dx, 0, dz);
        float distanceSq = (float) diff.lengthSqr();
        if (distanceSq <= 0.001F) {
            double length = Math.sqrt(input.horizontalDistanceSqr());
            if (length > 1) input = input.scale(1 / length);
            float ix = speed * (float) input.x, iz = speed * (float) input.z;
            diff = new Vec3(ix * cos - iz * sin, 0, iz * cos + ix * sin);
            distanceSq = (float) diff.horizontalDistanceSqr();
            if (distanceSq <= 0.001F) return false;
        }
        float inverse = org.joml.Math.invsqrt(distanceSq);
        Vec3 direction = diff.scale(inverse);
        float pitchCos = player.trigHandler.cos(player.yRot * ((float) Math.PI / 180.0F));
        if ((float) (player.trigHandler.sin(-yaw) * pitchCos * direction.x
                        + player.trigHandler.cos(-yaw) * pitchCos * direction.z)
                < -0.15F) return false;
        int ceilingX = (int) Math.floor(player.x),
                ceilingY = (int) Math.floor(player.boundingBox.maxY),
                ceilingZ = (int) Math.floor(player.z);
        if (!JavaAutoJumpGeometry.block(player, ceilingX, ceilingY, ceilingZ).isEmpty()
                || !JavaAutoJumpGeometry.block(player, ceilingX, ++ceilingY, ceilingZ)
                        .isEmpty()) return false;
        float jumpHeight = 1.2F;
        if (amplifier != null) jumpHeight += (amplifier + 1) * 0.75F;
        float lookAhead = Math.max(speed * 7.0F, 1.0F / inverse);
        float width = player.pose.width * player.getScale(), height = player.getBukkitHeight();
        Vec3 far = end.add(direction.scale(lookAhead));
        var query = new SimpleCollisionBox(begin.x, begin.y, begin.z, far.x, far.y + height, far.z)
                .sort()
                .inflate(width, 0, width);
        Vec3 offset = new Vec3(-direction.z, 0, direction.x).scale(width * 0.5F);
        Vec3 lowBegin = begin.add(0, 0.51F, 0), lowEnd = far.add(0, 0.51F, 0);
        for (var box : JavaAutoJumpGeometry.collisions(player, query)) {
            if (!JavaAutoJumpGeometry.intersects(box, lowBegin.subtract(offset), lowEnd.subtract(offset))
                    && !JavaAutoJumpGeometry.intersects(box, lowBegin.add(offset), lowEnd.add(offset))) continue;
            float obstacleHeight = (float) box.maxY;
            Vec3 center = box.getCenter();
            int x = (int) Math.floor(center.x), y = (int) Math.floor(center.y), z = (int) Math.floor(center.z);
            for (int steps = 1; steps < jumpHeight; steps++) {
                var above = JavaAutoJumpGeometry.block(player, x, y + steps, z);
                if (!above.isEmpty()) {
                    // VoxelShape.max returns a local coordinate before the client's float promotion.
                    double max = Double.NEGATIVE_INFINITY;
                    for (var part : above) max = Math.max(max, part.maxY - (y + steps));
                    obstacleHeight = (float) max + y + steps;
                    if (obstacleHeight - player.y > jumpHeight) return false;
                }
                if (steps > 1
                        && !JavaAutoJumpGeometry.block(player, ceilingX, ++ceilingY, ceilingZ)
                                .isEmpty()) return false;
            }
            float rise = (float) (obstacleHeight - player.y);
            return rise > 0.5F && rise <= jumpHeight;
        }
        return false;
    }
}
