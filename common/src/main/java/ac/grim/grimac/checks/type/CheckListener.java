package ac.grim.grimac.checks.type;

import ac.grim.grimac.utils.anticheat.update.PositionUpdate;

public interface CheckListener {

    default void onPositionUpdate(final PositionUpdate positionUpdate) {
    }
}
