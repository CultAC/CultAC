package ac.cult.cultac.checks.impl.autoclicker;

import ac.grim.grimac.api.config.ConfigManager;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.AutoClickCheck;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;

//@CheckData(name = "Autoclicker (Limit)", configName = "AutoclickerLimit")
public class AutoclickerLimit extends AutoClickCheck {
    double maxCps = 25;

    public AutoclickerLimit(CultPlayer playerData) { super(playerData, CheckInfo.builder().name("AutoClickerLimit").configName("AutoclickerLimit").stableKey("cult.autoclicker.limit").build(), 50, 0.2, 3); }

    @Override
    public void handle(LinkedList<Long> samples) {
        final double cps = CultMath.getCps(samples);

        // Not going to make this cloud-based - too stupid to do that
        if (cps > maxCps) {
            increaseBuffer(() -> "cps " + (int) Math.round(cps));
        } else {
            decreaseBuffer();
        }
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        maxCps = config.getDoubleElse("max-cps", 25);
    }
}
