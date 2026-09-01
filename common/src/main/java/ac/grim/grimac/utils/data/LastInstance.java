package ac.grim.grimac.utils.data;

import ac.grim.grimac.player.GrimPlayer;
import lombok.ToString;

@ToString
public class LastInstance {
    public LastInstance(GrimPlayer player) {
        player.lastInstanceManager.addInstance(this);
    }

    int lastInstance = 100;

    public boolean hasOccurredSince(int time) {
        return lastInstance <= time;
    }

    public void reset() {
        lastInstance = 0;
    }

    public int getRaw() {
        return lastInstance;
    }

    public int setRaw(int value) {
        return lastInstance = value;
    }

    public void tick() {
        // Don't overflow (a VERY long timer attack or a player playing for days could cause this to overflow)
        // The CPU can predict this branch, so it's only a few cycles.
        if (lastInstance == Integer.MAX_VALUE) lastInstance = 100;
        lastInstance++;
    }
}
