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

    // Mth.sin as this client's version indexes it (1.21.11 moved to double/long indexing).
    public float clientSin(float f) {
        return isVanillaMath ? VanillaMath.sin(player.getClientVersion(), f) : OptifineFastMath.sin(f);
    }

    public float clientCos(float f) {
        return isVanillaMath ? VanillaMath.cos(player.getClientVersion(), f) : OptifineFastMath.cos(f);
    }
}
