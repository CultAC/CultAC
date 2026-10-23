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
        if (this.viewPlayer != player) {
            this.view = FluidTypeFlowing.view(player);
            this.viewPlayer = player;
        }
        double fluidMinY = entityBox.minY + 0.001D;
        int minX = (int) Math.floor(entityBox.minX + 0.001D);
        int minY = (int) Math.floor(fluidMinY);
        int minZ = (int) Math.floor(entityBox.minZ + 0.001D);
        int maxX = (int) Math.ceil(entityBox.maxX - 0.001D) - 1;
        int maxY = (int) Math.ceil(entityBox.maxY - 0.001D) - 1;
        int maxZ = (int) Math.ceil(entityBox.maxZ - 0.001D) - 1;

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
                    this.pos.set(blockX, blockY, blockZ);
                    SimFluidState fluidState = ClientFluidQueries.fluidAt(player.compensatedWorld, this.pos);
                    // An empty cell is in no fluid tag; skipping it keeps a dry tick off the client tag projection.
                    if (fluidState.isEmpty() || !ClientFluidQueries.is(player, fluidState, fluidTag)) {
                        continue;
                    }

                    double fluidTop = blockY + ClientFluidQueries.height(player.compensatedWorld, this.pos, fluidState);
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
        if (currentCount == 0 || lengthSqr < 1.0E-5F) {
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
            double length = Math.sqrt(lengthSqr);
            currentX /= length;
            currentY /= length;
            currentZ /= length;
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
