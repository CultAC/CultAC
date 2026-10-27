package ac.cult.cultac.utils.data;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import java.util.List;
import java.util.Map;

/** The lava-height query retained by a strider between actual fluid refreshes. */
public record ClientFluidHeights(double lavaHeight, Map<String, Double> rawHeights) {
    public static final ClientFluidHeights EMPTY = new ClientFluidHeights(0.0D, Map.of());

    public static ClientFluidHeights capture(CultPlayer player, SimpleCollisionBox body) {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                ? new ClientFluidHeights(0.0D, ClientFluidQueries.rawHeights(player, body))
                : new ClientFluidHeights(ClientFluidQueries.depth(player, body, FluidTags.LAVA), Map.of());
    }

    public double lavaHeight(CultPlayer player) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_26_3)) return lavaHeight;
        double height = 0.0D;
        for (String fluid : ClientFluidQueries.clientTags(player).fluids().getOrDefault(FluidTags.LAVA.key(), List.of()))
            height = Math.max(height, rawHeights.getOrDefault(fluid, 0.0D));
        return height;
    }
}
