package ac.cult.cultac.checks.type;

import ac.cult.cultac.utils.anticheat.update.PositionUpdate;

public interface CheckListener {

    default void onPositionUpdate(final PositionUpdate positionUpdate) {
    }
}
