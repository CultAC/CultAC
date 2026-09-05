package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.bukkit.GameMode;

/** 26.1/26.2 Entity#checkInsideBlocks and the movement-affecting block callbacks. */
public final class JavaInsideBlockEffects {
    private JavaInsideBlockEffects() {}

    public record State(double fallDistance, Vec3 velocity, Vec3 stuckSpeed) {}

    public static State resolve(CultPlayer player, PredictionResult result, Vec3 movement,
                                double distance, Vec3 velocity, boolean landed) {
        var context = result.getSimulationContext();
        Vec3 originalMovement = result.getValidMovements().getClosestTrace().preCollisionMovement();
        State[] current = {new State(distance, velocity, null)};
        PacketEntity actor = context.getVehicle() == null ? context.getEntities().getSelf() : context.getVehicle();
        if (actor.isDead || player.gamemode == GameMode.SPECTATOR) return current[0];
        Vec3 end = context.getStart().add(movement);
        SimpleCollisionBox to = context.getToActualPose().copy().offset(end.subtract(context.getEnd()));
        SimpleCollisionBox from = to.copy().offset(movement.scale(-1));
        boolean living = actor.isLivingEntity();
        boolean stuckAllowed = context.getVehicle() != null || !player.isFlying;
        boolean powderAllowed = !living || player.compensatedWorld.getBlockStateAt(BlockPos.containing(end)).is(Blocks.POWDER_SNOW);
        // AbstractBoat#tick invokes applyEffectsFromBlocks twice. The second pass
        // has no recorded axis-dependent movement and uses oldPosition -> position.
        int passes = actor.isBoat() ? 2 : 1;
        for (int pass = 0; pass < passes; pass++) {
            Collisions.visitInsideBlocks26Dot2(from, to, pass == 0 ? originalMovement : null, (segmentFrom, segmentTo, pos, precise) -> {
                var block = player.compensatedWorld.getBlockStateAt(pos);
                boolean powder = block.getBlock() instanceof PowderSnowBlock;
                boolean web = block.getBlock() instanceof WebBlock;
                boolean berry = block.getBlock() instanceof SweetBerryBushBlock;
                boolean honey = block.getBlock() instanceof HoneyBlock;
                boolean bubble = block.getBlock() instanceof BubbleColumnBlock;
                if (!powder && !web && !berry && !honey && !bubble) return false;
                VoxelShape shape = powder
                        ? JavaCollisionState.of(player, actor, current[0].fallDistance()).powderSnowShape(pos.getY(), end.y)
                        : Shapes.block();
                if (shape.isEmpty()) shape = Shapes.block(); // PowderSnowBlock#getEntityInsideCollisionShape
                boolean inside = shape == Shapes.block() || intersects(segmentFrom, segmentTo, shape, pos);
                if (!inside) return false;
                State state = current[0];
                Vec3 speed = null;
                if (stuckAllowed) {
                    if (powder && powderAllowed) speed = new Vec3(0.9F, 1.5, 0.9F);
                    if (web) speed = Collisions.getCobwebStuckSpeed(player);
                    if (berry && living && !actor.type.equals(ac.cult.cultac.utils.nmsutil.EntityTypesCompat.FOX)
                            && !actor.type.equals(ac.cult.cultac.utils.nmsutil.EntityTypesCompat.BEE)) speed = new Vec3(0.8F, 0.75, 0.8F);
                }
                if (speed != null) state = new State(0, state.velocity(), speed);
                if (honey && slides(end, pos, to.maxX - to.minX, landed, state.velocity().y)) {
                    state = new State(0, slideVelocity(state.velocity()), state.stuckSpeed());
                }
                if (bubble && precise && (context.getVehicle() != null || !player.isFlying)) {
                    var above = player.compensatedWorld.getBlockStateAt(pos.above());
                    boolean surface = above.getCollisionShape(player.compensatedWorld, pos).isEmpty() && above.getFluidState().isEmpty();
                    // AbstractBoat overrides the surface callback without changing client velocity.
                    if (context.getVehicle() == null) {
                        // Player bubble velocity uses the existing uncertainty handler;
                        // committing it here would force one pose and apply it twice.
                        if (!surface) state = new State(0, state.velocity(), state.stuckSpeed());
                    } else if (!surface || !actor.isBoat()) {
                        state = bubble(state, surface, block.getValue(BubbleColumnBlock.DRAG_DOWN));
                    }
                }
                current[0] = state;
                return true;
            });
        }
        return current[0];
    }

    static boolean intersects(SimpleCollisionBox from, SimpleCollisionBox to, VoxelShape shape, BlockPos pos) {
        AABB box = new AABB(from.minX, from.minY, from.minZ, from.maxX, from.maxY, from.maxZ);
        return box.collidedAlongVector(new Vec3(to.minX - from.minX, to.minY - from.minY, to.minZ - from.minZ),
                shape.move(pos.getX(), pos.getY(), pos.getZ()).toAabbs());
    }

    static boolean slides(Vec3 end, BlockPos pos, double width, boolean landed, double velocityY) {
        return !landed && end.y <= pos.getY() + 0.9375 - 1.0E-7 && oldDeltaY(velocityY) < -0.08
                && (Math.abs(pos.getX() + 0.5 - end.x) + 1.0E-7 > 0.4375 + width / 2
                || Math.abs(pos.getZ() + 0.5 - end.z) + 1.0E-7 > 0.4375 + width / 2);
    }

    private static double oldDeltaY(double y) { return y / 0.98F + 0.08; }

    static Vec3 slideVelocity(Vec3 velocity) {
        double scale = oldDeltaY(velocity.y) < -0.13 ? -0.05 / oldDeltaY(velocity.y) : 1;
        return new Vec3(velocity.x * scale, (-0.05 - 0.08) * 0.98F, velocity.z * scale);
    }

    static State bubble(State state, boolean surface, boolean down) {
        double y = down ? Math.max(surface ? -0.9 : -0.3, state.velocity().y - 0.03)
                : Math.min(surface ? 1.8 : 0.7, state.velocity().y + (surface ? 0.1 : 0.06));
        return new State(surface ? state.fallDistance() : 0, new Vec3(state.velocity().x, y, state.velocity().z), state.stuckSpeed());
    }
}
