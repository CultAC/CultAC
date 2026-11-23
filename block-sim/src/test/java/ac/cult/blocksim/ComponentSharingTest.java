package ac.cult.blocksim;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.ComponentPatch;
import ac.cult.blocksim.data.ComponentWireEncoding;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.SimItemStack;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ComponentSharingTest {
    @Test void ownedAssemblyKeepsPublicJsonBoundariesIndependent() {
        var input = JsonParser.parseString("{\"nested\":{\"value\":1}}");
        var original = new Components(Map.of("test:value", input));
        var external = JsonParser.parseString("{\"changed\":2}");
        var changed = original.with("test:other", external);
        var subset = changed.subset(Set.of("test:value"));
        var overlay = original.overlay(changed, Set.of());
        input.getAsJsonObject().getAsJsonObject("nested").addProperty("value", 99);
        external.getAsJsonObject().addProperty("changed", 99);
        subset.get("test:value").getAsJsonObject().getAsJsonObject("nested").addProperty("value", 99);
        for (var value : List.of(original, changed, subset, overlay))
            assertEquals(1, value.get("test:value").getAsJsonObject().getAsJsonObject("nested").get("value").getAsInt());
        assertEquals(2, changed.get("test:other").getAsJsonObject().get("changed").getAsInt());
        assertSame(original, ComponentPatch.EMPTY.apply(original));
        assertSame(ComponentPatch.EMPTY, ComponentPatch.between(original, original));
    }

    @Test void stackCopiesKeepLivePrototypesAndIndependentMutations() {
        var data = DataTables.defaults(); var items = new ItemRegistry(data);
        var prototype = items.defaults("minecraft:stone"); var visible = new AtomicReference<>(prototype);
        var patch = ComponentPatch.fromJson(JsonParser.parseString("{\"test:value\":{\"nested\":1}}").getAsJsonObject());
        var stack = new SimItemStack(items.stack("minecraft:stone", 1).definition(), 5, prototype, patch, visible::get);
        var wire = new ComponentWireEncoding(777, new byte[] {1, 2, 3}, Map.of()); stack.wirePatch(wire);
        var copy = stack.copy();
        assertNotSame(stack, copy); assertSame(stack.components(), copy.components());
        copy.count(3); assertEquals(5, stack.count()); assertSame(wire, copy.wirePatch());
        visible.set(prototype.with("test:prototype", JsonParser.parseString("3")));
        assertSame(visible.get(), stack.prototype()); assertSame(visible.get(), copy.prototype());
        assertSame(prototype, copy.capturedPrototype());
        copy.removeComponent("test:value");
        assertNull(copy.wirePatch()); assertSame(wire, stack.wirePatch());
        assertTrue(stack.components().has("test:value")); assertFalse(copy.components().has("test:value"));
        assertSame(SimItemStack.empty(), SimItemStack.empty().copy());
    }
}
