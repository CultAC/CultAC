package ac.cult.cultac.protocol.value;

import java.util.Objects;

/** Consumed spawn fields; the dimension type ID belongs to the server's dynamic registry. */
public record PlayerSpawnInfo(
        int dimensionTypeId, String dimension, GameMode gameMode, int seaLevel, long biomeZoomSeed) {
    public PlayerSpawnInfo(int dimensionTypeId, String dimension, GameMode gameMode) {
        this(dimensionTypeId, dimension, gameMode, 63);
    }

    public PlayerSpawnInfo(int dimensionTypeId, String dimension, GameMode gameMode, int seaLevel) {
        this(dimensionTypeId, dimension, gameMode, seaLevel, 0);
    }

    public PlayerSpawnInfo {
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(gameMode);
    }
}
