package ac.cult.blocksim;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.ComponentPatch;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.nbt.NbtValue;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ComponentSanitizationTest {
    @Test
    void alreadySanitizedPatchRetainsValuesEncodingsAndPublicMutationIsolation() {
        var prototype = Components.parse("{\"test:removed\":1}");
        var source = JsonParser.parseString("{\"child\":[1,2]}");
        var typed = new NbtValue.Compound(Map.of("child", new NbtValue.Sequence(List.of(
                new NbtValue.Numeric(NbtValue.Kind.INT, 1), new NbtValue.Numeric(NbtValue.Kind.INT, 2)))));
        var added = new Components(Map.of("test:new", source), Map.of("test:new", typed));
        var patch = new ComponentPatch(added, Set.of("test:removed"));
        var result = patch.sanitized(prototype);
        assertSame(patch, result);
        source.getAsJsonObject().addProperty("child", 99);
        result.added().get("test:new").getAsJsonObject().addProperty("child", 99);
        assertEquals(typed, result.added().encodedNbt("test:new"));
        assertTrue(result.added().get("test:new").getAsJsonObject().get("child").isJsonArray());
        assertFalse(result.applyToCapturedPrototype(prototype).has("test:removed"));
    }

    @Test
    void redundantValuesAndOrphanMetadataStillUseTheOriginalSanitization() {
        var prototype = Components.parse("{\"test:default\":1,\"test:removed\":2}");
        var redundant = new ComponentPatch(Components.parse("{\"test:default\":1,\"test:new\":3}"),
                Set.of("test:removed", "test:missing", "test:new"));
        var clean = redundant.sanitized(prototype);
        assertEquals(Set.of("test:new"), clean.added().keys());
        assertEquals(Set.of("test:removed"), clean.removed());
        var number = new NbtValue.Numeric(NbtValue.Kind.INT, 3);
        var orphaned = new Components(Map.of("test:new", new JsonPrimitive(3)),
                Map.of("test:new", number, "test:orphan", number),
                Map.of("test:orphan", new NbtValue.Compound(Map.of())));
        var result = new ComponentPatch(orphaned, Set.of()).sanitized(prototype);
        assertFalse(result.added().hasEncodedNbt("test:orphan"));
        assertNull(result.added().layout("test:orphan"));
        assertEquals(number, result.added().encodedNbt("test:new"));
        assertSame(ComponentPatch.EMPTY, new ComponentPatch(Components.EMPTY, Set.of()).sanitized(prototype));
    }
}
