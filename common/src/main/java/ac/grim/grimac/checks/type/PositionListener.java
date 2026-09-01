package ac.grim.grimac.checks.type;

import ac.grim.grimac.utils.anticheat.update.PositionUpdate;

public interface PositionListener extends CheckListener, PostPredictionListener {

    default void onPositionUpdate(final PositionUpdate positionUpdate) {}
}
