package ac.cult.blocksim.environment;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.BlockPos;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeWeatherTest {
    @Test void onlyRainPrecipitationMakesThePositionWet() {
        var pos = new BlockPos(0, 64, 0);
        assertTrue(BiomeWeather.canRain(climate(1, .15F), pos, 63));
        assertFalse(BiomeWeather.canRain(climate(1, Math.nextDown(.15F)), pos, 63));
        assertFalse(BiomeWeather.canRain(climate(0, .8F), pos, 63));
        assertTrue(BiomeWeather.canRain(climate(256, .8F), pos, 63));
        assertFalse(BiomeWeather.canRain(climate(1, Float.NaN), pos, 63));
        assertTrue(BiomeWeather.canRain(climate(1, .8F), new BlockPos(0, 300, 0), 63));
        assertFalse(BiomeWeather.canRain(climate(1, .2F), new BlockPos(0, 300, 0), 63));
        assertTrue(BiomeWeather.canRain(climate(1, .2F), new BlockPos(0, 300, 0), 300));
    }

    private static NbtValue.Compound climate(int precipitation, float temperature) {
        return new NbtValue.Compound(Map.of(
            "has_precipitation", new NbtValue.Numeric(NbtValue.Kind.INT, precipitation),
            "temperature", new NbtValue.Numeric(NbtValue.Kind.FLOAT, temperature)));
    }
}
