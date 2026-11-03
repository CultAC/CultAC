package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.blocksim.environment.DimensionData;
import java.util.List;

/**
 * How a client sees its connection's received world registries. A Java client uses them as received; a Bedrock
 * client sees Geyser's conversion of them.
 */
public interface ClientWorldView {
    ClientWorldView RECEIVED = new ClientWorldView() {};

    /** Block heights, minimum inclusive and maximum exclusive. */
    record Heights(int min, int max) {}

    /** The biome the client uses for the received biome {@code key} in a level of {@code dimension}, if known. */
    default String biome(String key, DimensionData dimension) {
        return key;
    }

    /** The definition of the client's biome {@code key}; {@code received} is its received definition or null. */
    default ClientWorldDefaults.Entry biomeDefinition(String key, ClientWorldDefaults.Entry received) {
        return received;
    }

    /** The painting the client shows for the received variant {@code key}; {@code received} may be null. */
    default PaintingSize painting(String key, PaintingSize received) {
        return received;
    }

    /** The heights the client can hold blocks at in a level of {@code dimension}, one of the received types. */
    default Heights heights(DimensionData dimension, List<ClientWorldRegistries.Definition> dimensions) {
        return new Heights(dimension.minY(), dimension.minY() + dimension.height());
    }
}
