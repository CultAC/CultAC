package ac.cult.cultac.checks.impl.combat;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.Optional;

/** The blocks a Bedrock client's block pick certainly meets, judged from the blocks Cult models for its world. */
final class BedrockPickBlocks {
    private static final String BARRIER = "minecraft:barrier";

    private BedrockPickBlocks() {}

    /** Whether the ray from {@code from} to {@code to} meets such a block at least {@code edge} inside its face. */
    static boolean blocks(CultPlayer player, Vec3 from, Vec3 to, double edge) {
        if (edge >= 0.5) return false;
        BlockHit hit;
        try {
            hit = player.compensatedWorld
                    .geometry()
                    .clipBlocks(
                            from,
                            to,
                            (state, pos) -> fullCube(player, state, pos.x(), pos.y(), pos.z())
                                    ? Shapes.block()
                                    : Shapes.empty());
        } catch (SimLevel.UnloadedWorldException unloaded) {
            return false; // The client's view of an unloaded block is unknown.
        }
        if (hit == null || hit.inside()) return false;
        double[] at = {hit.location().x(), hit.location().y(), hit.location().z()};
        int[] normal = {hit.face().x(), hit.face().y(), hit.face().z()};
        for (int axis = 0; axis < 3; axis++) {
            if (normal[axis] != 0) continue;
            double inBlock = at[axis] - Math.floor(at[axis]);
            if (inBlock < edge || inBlock > 1.0 - edge) return false;
        }
        return true;
    }

    private static boolean fullCube(CultPlayer player, int state, int x, int y, int z) {
        if (player.compensatedWorld.geometry().staticCollision(state) != Shapes.block()) return false;
        if (NativeBlockCollisionHelper.getSelectionShape(player, state, x, y, z) != Shapes.block()) return false;
        if (BARRIER.equals(DataTables.defaults().registry().block(state).key())) return false;
        Optional<CollisionBox> bedrock = ClientBlockShapes.movementAlternative(player, state, x, y, z, Double.NaN);
        return bedrock.isEmpty() || bedrock.get().isFullBlock();
    }
}
