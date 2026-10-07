package ac.cult.blocksim.environment;

import ac.cult.blocksim.engine.ClientClocks;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BooleanEnvironmentTest {
    @Test void receivedMetadataIsCapturedAndTimelinesUseWholeCompensatedClockTicks() {
        var dimension = JsonParser.parseString("{\"gameplay/water_evaporates\":false,\"gameplay/creaking_active\":true}").getAsJsonObject();
        var firstBiome = JsonParser.parseString("{\"gameplay/water_evaporates\":true,\"gameplay/creaking_active\":false}").getAsJsonObject();
        var secondBiome = JsonParser.parseString("{}").getAsJsonObject();
        var day = JsonParser.parseString("""
                {"clock":"test:day","period_ticks":20,"tracks":{"gameplay/water_evaporates":{
                 "modifier":"xor","keyframes":[{"ticks":0,"value":false},{"ticks":10,"value":true},{"ticks":20,"value":false}]}}}
                """);
        var moon = JsonParser.parseString("""
                {"clock":"test:moon","tracks":{"gameplay/creaking_active":{
                 "modifier":"and","keyframes":[{"ticks":0,"value":true},{"ticks":5,"value":false}]}}}
                """);
        var clocks = new ClientClocks();
        var environment = new BooleanEnvironment(dimension, Map.of(7, firstBiome, 8, secondBiome), List.of(day, moon), clocks);
        assertEquals(new BooleanEnvironment.Values(true, false), environment.at(7));
        assertEquals(new BooleanEnvironment.Values(false, true), environment.at(8));

        dimension.addProperty("gameplay/water_evaporates", true);
        secondBiome.addProperty("gameplay/water_evaporates", true);
        day.getAsJsonObject().getAsJsonObject("tracks").getAsJsonObject("gameplay/water_evaporates")
                .getAsJsonArray("keyframes").get(1).getAsJsonObject().addProperty("value", false);
        clocks.handleUpdates(100, Map.of("test:day", new ClientClocks.State(10, .75F, .5F),
                "test:moon", new ClientClocks.State(4, .99F, .1F)));
        assertTrue(environment.at(7).waterEvaporates(), "Received updates become visible at the environment tick");
        environment.tick();
        assertEquals(new BooleanEnvironment.Values(false, false), environment.at(7));
        assertEquals(new BooleanEnvironment.Values(true, true), environment.at(8));
        clocks.tick(101);
        environment.tick();
        assertFalse(environment.at(8).creakingActive());
        clocks.handleUpdates(102, Map.of("test:moon", new ClientClocks.State(4, .99F, 0)));
        environment.tick();
        clocks.tick(20000);
        environment.tick();
        assertTrue(environment.at(8).creakingActive(), "A paused clock retains its whole-tick sample");
        assertThrows(IllegalStateException.class, () -> environment.at(9));
    }
}
