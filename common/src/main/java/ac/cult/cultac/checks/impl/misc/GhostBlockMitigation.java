package ac.cult.cultac.checks.impl.misc;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.grim.grimac.api.config.ConfigManager;
import org.jetbrains.annotations.NotNull;

public class GhostBlockMitigation extends BlockPlaceCheck {

    private boolean allow;
    private int distance;

    public GhostBlockMitigation(CultPlayer player) {
        super(player);
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {
        if (allow || CultAPI.INSTANCE.getConfigManager().isObserveOnly()) return;

        CompensatedWorld world = player.compensatedWorld;
        BlockPos pos = place.getPlacedBlockPos();

        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        for (int i = x - distance; i <= x + distance; i++) {
            for (int j = y - distance; j <= y + distance; j++) {
                for (int k = z - distance; k <= z + distance; k++) {
                    if (i == x && j == y && k == z) {
                        continue;
                    }

                    // The server's view of the surroundings, as most recently sent to this client.
                    int type = world.getServerSentBlockStateIdAt(new BlockPos(i, j, k));
                    if (type == CompensatedWorld.UNKNOWN_SERVER_STATE) {
                        continue;
                    }

                    if (!DataTables.defaults().registry().facts(type).has(StateFacts.AIR)) {
                        return;
                    }
                }
            }
        }

        place.resync();
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        allow = config.getBooleanElse("exploit.allow-building-on-ghostblocks", true);
        distance = config.getIntElse("exploit.distance-to-check-for-ghostblocks", 2);

        if (distance < 2 || distance > 4) distance = 2;
    }
}
