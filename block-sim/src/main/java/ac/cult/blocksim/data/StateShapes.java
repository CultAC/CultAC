package ac.cult.blocksim.data;

import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.util.HashMap;
import java.util.List;

/** Shared immutable geometry compiled once from the generated ordered boxes. */
public final class StateShapes {
    private final VoxelShape[] collision, outline, support, interaction;

    StateShapes(StateFacts[] facts) {
        collision = new VoxelShape[facts.length];
        outline = new VoxelShape[facts.length];
        support = new VoxelShape[facts.length];
        interaction = new VoxelShape[facts.length];
        var shared = new HashMap<List<Box>, VoxelShape>();
        for (int state = 0; state < facts.length; state++) {
            collision[state] = shared.computeIfAbsent(facts[state].collision(), Shapes::fromBoxes);
            outline[state] = shared.computeIfAbsent(facts[state].outline(), Shapes::fromBoxes);
            support[state] = shared.computeIfAbsent(facts[state].support(), Shapes::fromBoxes);
            interaction[state] = shared.computeIfAbsent(facts[state].interaction(), Shapes::fromBoxes);
        }
    }

    public VoxelShape collision(int state) { return collision[state]; }
    public VoxelShape outline(int state) { return outline[state]; }
    public VoxelShape support(int state) { return support[state]; }
    public VoxelShape interaction(int state) { return interaction[state]; }
}
