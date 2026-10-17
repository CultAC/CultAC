package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.math.Vec3;
import java.util.function.Consumer;

/** The offline replay harness's player fixture, for tests outside this package. */
public final class OfflinePlayers {
    private OfflinePlayers() {}

    public static void installConfig() {
        OfflineCultTestBootstrap.installConfig();
    }

    public static CultPlayer player() {
        return OfflineBedrockReplayRunnerTest.offlinePlayer();
    }

    public static void close(CultPlayer player) {
        OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
    }

    /** Runs {@code body} with a fully joined Java player standing at {@code safe}, its last known good position. */
    public static void withSafePlayer(Vec3 safe, Consumer<CultPlayer> body) {
        installConfig();
        CultPlayer player = player();
        player.movementPlatform = MovementPlatform.JAVA;
        try {
            player.x = safe.x;
            player.y = safe.y;
            player.z = safe.z;
            player.getSetbackTeleportUtil().lastKnownGoodPosition = new SetbackPosWithVector(safe, Vec3.ZERO, 0);
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            player.getSetbackTeleportUtil().hasFullyJoined = true;
            body.accept(player);
        } finally {
            close(player);
        }
    }
}
