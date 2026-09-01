package ac.grim.grimac.utils.math;

import ac.grim.grimac.player.GrimPlayer;
import lombok.Getter;

public class TrigHandler {
    GrimPlayer player;
    @Getter
    private boolean isVanillaMath = true;

    public TrigHandler(GrimPlayer player) {
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
