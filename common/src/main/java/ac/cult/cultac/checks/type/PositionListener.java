package ac.cult.cultac.checks.type;

import ac.cult.cultac.utils.anticheat.update.PositionUpdate;

public interface PositionListener extends CheckListener, PostPredictionListener {

    default void onPositionUpdate(final PositionUpdate positionUpdate) {}
}
