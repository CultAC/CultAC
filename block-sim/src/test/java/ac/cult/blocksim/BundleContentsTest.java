package ac.cult.blocksim;

import ac.cult.blocksim.data.BundleContents;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.MutableBundleContents;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BundleContentsTest {
    @Test void selectedExtractionSurvivesStackCopiesWithoutChangingMergeIdentity() {
        var items = new ItemRegistry(DataTables.defaults());
        var bundle = items.stack("minecraft:bundle", 1);
        var contents = new MutableBundleContents(bundle.components().bundle(), items);
        var stone = items.stack("minecraft:stone", 8);
        var dirt = items.stack("minecraft:dirt", 4);
        assertEquals(8, contents.tryInsert(stone));
        assertEquals(4, contents.tryInsert(dirt));
        bundle.bundleContents(contents.toImmutable());
        var prior = bundle.copy();
        contents.toggleSelectedItem(1);
        bundle.bundleContents(contents.toImmutable());
        assertTrue(prior.sameItemSameComponents(bundle));
        assertEquals(-1, prior.components().bundle().selectedItem());
        var copied = bundle.copy();
        var extract = new MutableBundleContents(copied.components().bundle(), items);
        var removed = extract.removeOne();
        assertEquals("minecraft:stone", removed.itemKey());
        assertEquals(8, removed.count());
        assertEquals(-1, extract.toImmutable().selectedItem());
        assertEquals("minecraft:dirt", extract.toImmutable().items().getFirst().itemKey());
        assertEquals(0, stone.count());
        assertEquals(0, dirt.count());
    }

    @Test void nestedCapacityConsumesOnlyWhatFitsAndRejectsShulkerBoxes() {
        var items = new ItemRegistry(DataTables.defaults());
        var nested = items.stack("minecraft:bundle", 1);
        var fill = new MutableBundleContents(nested.components().bundle(), items);
        assertEquals(32, fill.tryInsert(items.stack("minecraft:stone", 32)));
        nested.bundleContents(fill.toImmutable());
        var contents = new MutableBundleContents(new BundleContents(List.of()), items);
        assertEquals(0, contents.tryInsert(items.stack("minecraft:shulker_box", 1)));
        assertEquals(1, contents.tryInsert(nested));
        var stone = items.stack("minecraft:stone", 64);
        assertEquals(28, contents.tryInsert(stone));
        assertEquals(36, stone.count());
        assertEquals(0, contents.tryInsert(stone));
        assertEquals(List.of("minecraft:stone", "minecraft:bundle"),
                contents.toImmutable().items().stream().map(item -> item.itemKey()).toList());
    }
}
