package ac.cult.cultac.utils.math;

import ac.cult.cultac.player.CultPlayer;
import lombok.Getter;

public class TrigHandler {
    CultPlayer player;
    @Getter
    private boolean isVanillaMath = true;

    public TrigHandler(CultPlayer player) {
        this.player = player;
    }

    public void toggleShitMath() {
        isVanillaMath = !isVanillaMath;
    }


    public float sin(float f) {
        return isVanillaMath ? VanillaMath.sin(f) : OptifineFastMath.sin(f);
    }

    public float cos(float f) {
        return isVanillaMath ? VanillaMath.cos(f) : OptifineFastMath.cos(f);
    }
}
