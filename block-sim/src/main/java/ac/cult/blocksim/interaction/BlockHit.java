package ac.cult.blocksim.interaction;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.Vec3;

/** Claimed packet hit. Existing checks independently decide whether it is legal. */
public record BlockHit(BlockPos pos, Direction face, Vec3 location, boolean inside) { }
