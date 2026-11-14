package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.engine.FluidQueries;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;

/** A fluid current computed into owned scratch, allocating nothing once its player's view exists. */
public final class FluidCurrent {
    public double x;
    public double y;
    public double z;

    private final double[] flow = new double[3];
    private FluidQueries.View view;
    private CultPlayer viewPlayer;

    /**
     * Entity#updateFluidHeightAndDoFluidPushing's current from {@code fluidTag} on {@code entityBox} into x, y, z, or
     * false with none. {@code playerEntity} averages it as a player's; a slow horizontal velocity before it raises a
     * weak current to the minimum push.
     */
    public boolean compute(
            CultPlayer player,
            SimpleCollisionBox entityBox,
            boolean playerEntity,
            double velocityX,
            double velocityZ,
            FluidTags fluidTag,
            double scale) {
        return compute(player, entityBox, playerEntity, velocityX, velocityZ, fluidTag, scale, false);
    }

    /**
     * {@link #compute}; with {@code beforeRecentChanges}, every cell a recent fluid change touched is read at the
     * first state those changes replaced.
     */
    public boolean compute(
            CultPlayer player,
            SimpleCollisionBox entityBox,
            boolean playerEntity,
            double velocityX,
            double velocityZ,
            FluidTags fluidTag,
            double scale,
            boolean beforeRecentChanges) {
        double fluidMinY = entityBox.minY + 0.001D;
        int minX = (int) Math.floor(entityBox.minX + 0.001D);
        int minY = (int) Math.floor(fluidMinY);
        int minZ = (int) Math.floor(entityBox.minZ + 0.001D);
        int maxX = (int) Math.ceil(entityBox.maxX - 0.001D) - 1;
        int maxY = (int) Math.ceil(entityBox.maxY - 0.001D) - 1;
        int maxZ = (int) Math.ceil(entityBox.maxZ - 0.001D) - 1;
        if (!beforeRecentChanges && !player.compensatedWorld.mayHaveFluid(minX, minY, minZ, maxX, maxY, maxZ))
            return false;
        FluidQueries.View view;
        if (beforeRecentChanges) {
            view = FluidTypeFlowing.viewBeforeRecentChanges(player);
        } else {
            if (this.viewPlayer != player) {
                this.view = FluidTypeFlowing.view(player);
                this.viewPlayer = player;
            }
            view = this.view;
        }

        double currentX = 0.0D;
        double currentY = 0.0D;
        double currentZ = 0.0D;
        int currentCount = 0;
        double height = 0.0D;
        // 1.21.11 Entity#updateFluidHeightAndDoFluidPushing measures fluid height from the deflated box;
        // 26.1 and 26.2 EntityFluidInteraction#update measure it from the entity's bounding box.
        double entityMinY =
                player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1) ? entityBox.minY : fluidMinY;

        for (int blockX = minX; blockX <= maxX; blockX++) {
            for (int blockY = minY; blockY <= maxY; blockY++) {
                for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                    SimFluidState fluidState = view.fluidAt(blockX, blockY, blockZ);
                    // An empty cell is in no fluid tag; skipping it keeps a dry tick off the client tag projection.
                    if (fluidState.isEmpty() || !ClientFluidQueries.is(player, fluidState, fluidTag)) {
                        continue;
                    }

                    double fluidTop = blockY + fluidState.height(view.fluidAt(blockX, blockY + 1, blockZ));
                    if (fluidTop < fluidMinY) {
                        continue;
                    }

                    height = Math.max(fluidTop - entityMinY, height);
                    FluidTypeFlowing.flow(view, blockX, blockY, blockZ, fluidState, this.flow);
                    double flowX = this.flow[0];
                    double flowY = this.flow[1];
                    double flowZ = this.flow[2];
                    if (height < 0.4D) {
                        flowX *= height;
                        flowY *= height;
                        flowZ *= height;
                    }

                    currentX += flowX;
                    currentY += flowY;
                    currentZ += flowZ;
                    currentCount++;
                }
            }
        }

        double lengthSqr = currentX * currentX + currentY * currentY + currentZ * currentZ;
        // 26.1 EntityFluidInteraction.CurrentAccumulator#applyTo drops a current under 1.0E-5F squared; before it,
        // Entity#updateFluidHeightAndDoFluidPushing pushes by any current longer than 0.
        boolean dropsWeakCurrent = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1);
        if (currentCount == 0 || (dropsWeakCurrent ? lengthSqr < 1.0E-5F : lengthSqr <= 0.0D)) {
            return false;
        }

        // MCP-Reborn EntityFluidInteraction.Tracker#applyCurrentTo averages water
        // current only for Player entities. Other entities, including boats,
        // normalize accumulated current before scaling it by 0.014.
        if (playerEntity) {
            double average = 1.0D / currentCount;
            currentX *= average;
            currentY *= average;
            currentZ *= average;
        } else {
            // Vec3#normalize returns zero below a length of 1.0E-5F.
            double length = Math.sqrt(lengthSqr);
            if (length < 1.0E-5F) {
                currentX = 0.0D;
                currentY = 0.0D;
                currentZ = 0.0D;
            } else {
                currentX /= length;
                currentY /= length;
                currentZ /= length;
            }
        }
        currentX *= scale;
        currentY *= scale;
        currentZ *= scale;
        double length = Math.sqrt(currentX * currentX + currentY * currentY + currentZ * currentZ);
        if (Math.abs(velocityX) < 0.003 && Math.abs(velocityZ) < 0.003 && length < 0.0045000000000000005) {
            if (length < 1.0E-5F) {
                currentX = 0.0D;
                currentY = 0.0D;
                currentZ = 0.0D;
            } else {
                currentX = currentX / length * 0.0045000000000000005;
                currentY = currentY / length * 0.0045000000000000005;
                currentZ = currentZ / length * 0.0045000000000000005;
            }
        }
        this.x = currentX;
        this.y = currentY;
        this.z = currentZ;
        return true;
    }

    /** False when {@link #compute} would find no fluid in a section around {@code entityBox}. */
    static boolean mayTouchFluid(CultPlayer player, SimpleCollisionBox entityBox) {
        return player.compensatedWorld.mayHaveFluid(
                (int) Math.floor(entityBox.minX + 0.001D),
                (int) Math.floor(entityBox.minY + 0.001D),
                (int) Math.floor(entityBox.minZ + 0.001D),
                (int) Math.ceil(entityBox.maxX - 0.001D) - 1,
                (int) Math.ceil(entityBox.maxY - 0.001D) - 1,
                (int) Math.ceil(entityBox.maxZ - 0.001D) - 1);
    }
}
