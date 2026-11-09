package ac.cult.blocksim.data;

import java.util.ArrayList;
import java.util.List;

/** Fresh registry identities over the same generated layout; shapes are shared because the facts are identical. */
public final class AnalogProofRegistries {
    private AnalogProofRegistries() {}

    public static List<StateFacts> facts(BlockRegistry source) {
        var facts = new ArrayList<StateFacts>(source.stateCount());
        for (int state = 0; state < source.stateCount(); state++) facts.add(source.facts(state));
        return List.copyOf(facts);
    }

    /** Every definition is a new object equal to the source definition; state ids are unchanged. */
    public static BlockRegistry equalCopy(BlockRegistry source, List<StateFacts> facts) {
        var blocks = new ArrayList<BlockDefinition>(source.blocks().size());
        for (var block : source.blocks()) {
            blocks.add(new BlockDefinition(block.key(), block.vanillaClass(), block.firstState(), block.stateCount(),
                block.defaultState(), block.properties(), block.bindings(), block.features()));
        }
        return new BlockRegistry(blocks, facts, source.maxValidChunkCoordinate(), source.shapes());
    }

    /** A new registry object holding the source's own definition instances. */
    public static BlockRegistry sameDefinitions(BlockRegistry source, List<StateFacts> facts) {
        return new BlockRegistry(source.blocks(), facts, source.maxValidChunkCoordinate(), source.shapes());
    }
}
