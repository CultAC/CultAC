package ac.cult.cultac.utils.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ac.cult.blocksim.data.ItemComponents;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemComponentsRereadTest {
    @Test
    void repeatedReadsMatchAFirstRead() {
        var stack = ItemUtil.modelItems().stack("minecraft:diamond_pickaxe", 1);
        var components = stack.components()
                .with(
                        "minecraft:enchantments",
                        JsonParser.parseString("{\"minecraft:unbreaking\":2,\"efficiency\":3}"));
        var first = ItemComponents.enchantments(components);
        assertEquals(List.of("minecraft:unbreaking", "minecraft:efficiency"), List.copyOf(first.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> first.put("minecraft:mending", 1));
        for (int i = 0; i < 2; i++) {
            var again = ItemComponents.enchantments(components);
            assertEquals(first, again);
            assertEquals(List.copyOf(first.keySet()), List.copyOf(again.keySet()));
        }
        var fresh = components.with("minecraft:damage", new JsonPrimitive(1));
        assertEquals(ItemComponents.tool(fresh), ItemComponents.tool(components));
        assertNotNull(ItemComponents.tool(components));
        assertEquals(ItemComponents.tool(components), ItemComponents.tool(components));

        var stick = ItemUtil.modelItems().stack("minecraft:stick", 1).components();
        assertNull(ItemComponents.tool(stick));
        assertNull(ItemComponents.tool(stick));
        assertEquals(Map.of(), ItemComponents.enchantments(stick));
    }
}
