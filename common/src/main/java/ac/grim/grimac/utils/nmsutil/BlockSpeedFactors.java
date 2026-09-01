package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class BlockSpeedFactors {
    private BlockSpeedFactors() {
    }

    public static float nextTickForResult(GrimPlayer player, PredictionResult result) {
        // MCP-Reborn Player#getBlockSpeedFactor returns 1.0F while the player is
        // fall-flying or ability-flying. Use the frozen SimulationContext for
        // fall-flying; mutable GrimPlayer state may already describe a later packet.
        if (result.getSimulationContext().isGliding() || player.isFlying) {
            return 1.0f;
        }

        PacketEntity vehicle = result.getSimulationContext().getVehicle();
        if (vehicle != null) {
            // Entity#move applies getBlockSpeedFactor on the root entity itself.
            // A mounted player's supporting block can differ from the ridden root,
            // especially while passenger attachment/interpolation offsets are active.
            return getEntityBlockSpeedFactor(player, result.getSimulationContext().getEnd());
        }

        return BlockProperties.getBlockSpeedFactor(player, result.getSimulationContext().getWorldData().getMainSupportingBlockPos(), result.getSimulationContext().getEnd());
    }

    public static float playerForResult(GrimPlayer player, PredictionResult result) {
        // MCP-Reborn Player#getBlockSpeedFactor returns 1.0F while the player is
        // fall-flying or ability-flying. Use the frozen SimulationContext for
        // fall-flying; mutable GrimPlayer state may already describe a later packet.
        if (result.getSimulationContext().isGliding() || player.isFlying) {
            return 1.0f;
        }

        return BlockProperties.getBlockSpeedFactor(player, result.getSimulationContext().getWorldData().getMainSupportingBlockPos(), result.getSimulationContext().getEnd());
    }

    private static float getEntityBlockSpeedFactor(GrimPlayer player, Vec3 position) {
        BlockState inBlock = player.compensatedWorld.getBlockStateAt(
                (int) Math.floor(position.x),
                (int) Math.floor(position.y),
                (int) Math.floor(position.z)
        );
        float inBlockSpeedFactor = inBlock.getBlock().getSpeedFactor();
        if (inBlock.getBlock() != Blocks.WATER && inBlock.getBlock() != Blocks.BUBBLE_COLUMN) {
            if (inBlockSpeedFactor != 1.0F) {
                return inBlockSpeedFactor;
            }
            BlockState below = player.compensatedWorld.getBlockStateAt(
                    (int) Math.floor(position.x),
                    (int) Math.floor(position.y - 0.500001F),
                    (int) Math.floor(position.z)
            );
            return below.getBlock().getSpeedFactor();
        }
        return inBlockSpeedFactor;
    }
}
