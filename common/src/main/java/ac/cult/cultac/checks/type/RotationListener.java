package ac.cult.cultac.checks.type;

import ac.cult.cultac.utils.anticheat.update.RotationUpdate;

public interface RotationListener extends CheckListener {
    void process(RotationUpdate rotationUpdate);
}
