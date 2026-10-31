package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.engine.FluidQueries;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;

/** A fluid current computed into owned scratch, allocating nothing once its player's view exists. */
public final class FluidCurrent {
    public double x;
    public double y;
    public double z;
    /** Contact exists even when its accumulated flow is zero or below the current cutoff. */
    public boolean touching;

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
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
        return compute(player, entityBox, playerEntity, velocityX, velocityZ, fluidTag, scale,
                entityBox.minX + .001D, entityBox.minY + .001D, entityBox.minZ + .001D,
                entityBox.maxX - .001D, entityBox.maxY - .001D, entityBox.maxZ - .001D);
    }

    /** Entity#getFluidInteractionBox after the ridden boat clips the already-deflated passenger box. */
    public boolean computePassenger(
            CultPlayer player, SimpleCollisionBox body, SimpleCollisionBox interaction,
            double velocityX, double velocityZ, FluidTags tag, double scale) {
        return compute(player, body, true, velocityX, velocityZ, tag, scale,
                interaction.minX, interaction.minY, interaction.minZ,
                interaction.maxX, interaction.maxY, interaction.maxZ);
    }

    private boolean compute(
            CultPlayer player, SimpleCollisionBox entityBox, boolean playerEntity,
            double velocityX, double velocityZ, FluidTags fluidTag, double scale,
            double minBoxX, double fluidMinY, double minBoxZ, double maxBoxX, double maxBoxY, double maxBoxZ) {
        this.touching = false;
        if (!ClientFluidQueries.hasLoadedFluidNeighborhood(player, entityBox)) return false;
        if (this.viewPlayer != player) {
            this.view = FluidTypeFlowing.view(player);
            this.viewPlayer = player;
        }
        int minX = (int) Math.floor(minBoxX);
        int minY = (int) Math.floor(fluidMinY);
        int minZ = (int) Math.floor(minBoxZ);
        int maxX = (int) Math.ceil(maxBoxX) - 1;
        int maxY = (int) Math.ceil(maxBoxY) - 1;
        int maxZ = (int) Math.ceil(maxBoxZ) - 1;

        double currentX = 0.0D;
        double currentY = 0.0D;
        double currentZ = 0.0D;
        int currentCount = 0;
        double height = 0.0D;
        boolean interaction = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1);
        // Entity measures from the deflated box; EntityFluidInteraction measures from the entity's box.
        double entityMinY = interaction ? entityBox.minY : fluidMinY;

        for (int blockX = minX; blockX <= maxX; blockX++) {
            for (int blockY = minY; blockY <= maxY; blockY++) {
                for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                    this.pos.set(blockX, blockY, blockZ);
                    SimFluidState fluidState = ClientFluidQueries.fluidAt(player.compensatedWorld, this.pos);
                    // The old Entity updater counts tagged EmptyFluid's zero flow in its average; 26.1 skips it.
                    if (fluidState.isEmpty() && !ClientFluidQueries.usesLegacyFluidDepth(player)
                            || !ClientFluidTagOrder.currentMatches(player, fluidState, fluidTag)) {
                        continue;
                    }

                    float fluidHeight = ClientFluidQueries.height(player.compensatedWorld, this.pos, fluidState);
                    // Entity's int + float rounds before promotion; EntityFluidInteraction adds to a double Y.
                    double fluidTop = interaction ? (double) blockY + fluidHeight : (double) (blockY + fluidHeight);
                    if (fluidTop < fluidMinY) {
                        continue;
                    }

                    height = Math.max(fluidTop - entityMinY, height);
                    FluidTypeFlowing.flow(this.view, blockX, blockY, blockZ, fluidState, this.flow);
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
        this.touching = currentCount != 0;
        // The accumulated-current cutoff belongs to EntityFluidInteraction, added in 26.1.
        if (currentCount == 0 || (interaction ? lengthSqr < 1.0E-5F : !(Math.sqrt(lengthSqr) > 0.0D))) {
            return false;
        }

        // Entity averages first for every entity. The newer accumulator averages only for players;
        // its non-player normalization reads the accumulated vector directly.
        if (playerEntity || !interaction) {
            double average = 1.0D / currentCount;
            currentX *= average;
            currentY *= average;
            currentZ *= average;
        }
        if (!playerEntity) {
            double length = Math.sqrt(currentX * currentX + currentY * currentY + currentZ * currentZ);
            // Vec3#normalize's exact threshold also applies after the older averaging step.
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
}
