package ac.cult.cultac.manager.player;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;

/** Test-only access to setback state that production code never writes directly. */
public final class SetbackFixtures {
    private SetbackFixtures() {}

    /** Places the player at {@code safe} as its safe position, without simulating how it got there. */
    public static void seedSafePosition(CultPlayer player, SetbackPosWithVector safe) {
        player.getSetbackTeleportUtil().seedSafePosition(safe);
    }

    public static void seedSafePosition(SetbackTeleportUtil setbacks, SetbackPosWithVector safe) {
        setbacks.seedSafePosition(safe);
    }

    /** Marks whether the client accepted its placement teleport. */
    public static void setLoaded(SetbackTeleportUtil setbacks, boolean loaded) {
        setbacks.setLoaded(loaded);
    }

    public static void setBlockOffsets(SetbackTeleportUtil setbacks, boolean block) {
        setbacks.setBlockOffsets(block);
    }

    /** Puts {@code data} in flight as the correction the client must accept, without sending it. */
    public static void requireCorrection(SetbackTeleportUtil setbacks, SetBackData data) {
        setbacks.requireCorrection(data);
    }
}
