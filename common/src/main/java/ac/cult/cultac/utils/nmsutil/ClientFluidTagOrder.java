package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import java.util.List;

/** EntityFluidInteraction assigns each fluid to its first matching current tag, in JVM Set iteration order. */
public enum ClientFluidTagOrder {
    WATER_FIRST,
    LAVA_FIRST;

    private static final List<ClientFluidTagOrder> BOTH = List.of(WATER_FIRST, LAVA_FIRST);

    public static List<ClientFluidTagOrder> candidates(CultPlayer player) {
        return player.provenClientFluidTagOrder == null ? BOTH : List.of(player.provenClientFluidTagOrder);
    }

    public static boolean hasOverlap(CultPlayer player) {
        if (player.isBedrockMovement() || player.getClientVersion().isOlderThan(ClientVersion.V_26_1)) return false;
        var tags = ClientFluidQueries.clientTags(player).fluids();
        var water = tags.getOrDefault(FluidTags.WATER.key(), List.of());
        var lava = tags.getOrDefault(FluidTags.LAVA.key(), List.of());
        for (String fluid : water) if (!fluid.equals("minecraft:empty") && lava.contains(fluid)) return true;
        return false;
    }

    public static boolean currentMatches(CultPlayer player, SimFluidState fluid, FluidTags tag) {
        if (!ClientFluidQueries.is(player, fluid, tag)) return false;
        if (player.isBedrockMovement() || player.getClientVersion().isOlderThan(ClientVersion.V_26_1)) return true;
        if (tag != FluidTags.WATER && tag != FluidTags.LAVA) return true;
        FluidTags other = tag == FluidTags.WATER ? FluidTags.LAVA : FluidTags.WATER;
        var order = player.clientFluidTagOrder == null ? player.provenClientFluidTagOrder : player.clientFluidTagOrder;
        return !ClientFluidQueries.is(player, fluid, other)
                || (order != LAVA_FIRST ? tag == FluidTags.WATER : tag == FluidTags.LAVA);
    }

    public static boolean contactMatches(CultPlayer player, SimFluidState fluid, FluidTags tag) {
        // 26.3 stores contact by holder and queries tags afterward; currents still have one accumulator.
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                ? ClientFluidQueries.is(player, fluid, tag)
                : currentMatches(player, fluid, tag);
    }

    public static Scope use(CultPlayer player, ClientFluidTagOrder order) {
        return new Scope(player, order);
    }

    public static final class Scope implements AutoCloseable {
        private final CultPlayer player;
        private final ClientFluidTagOrder previous;

        private Scope(CultPlayer player, ClientFluidTagOrder order) {
            this.player = player;
            previous = player.clientFluidTagOrder;
            player.clientFluidTagOrder = order;
        }

        @Override
        public void close() {
            player.clientFluidTagOrder = previous;
        }
    }
}
