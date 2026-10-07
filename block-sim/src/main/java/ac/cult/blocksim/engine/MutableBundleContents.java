package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.BundleContents;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.ItemTemplate;
import ac.cult.blocksim.data.ItemTemplates;
import java.util.ArrayList;
import org.apache.commons.lang3.math.Fraction;

/** Action-local bundle insertion, extraction and selection; no server slot-modifier machinery. */
public final class MutableBundleContents {
    private final ItemRegistry registry;
    private final ArrayList<SimItemStack> items = new ArrayList<>();
    private Fraction weight = Fraction.ZERO;
    private int selectedItem = -1;

    public MutableBundleContents(BundleContents contents, ItemRegistry registry) {
        this.registry = java.util.Objects.requireNonNull(registry);
        var initial = contents.weight(registry);
        if (initial.valid()) {
            var templates = new ItemTemplates(registry);
            contents.items().forEach(item -> items.add(templates.create(item)));
            weight = initial.value();
            selectedItem = contents.selectedItem();
        }
    }
    public Fraction weight() { return weight; }

    public int tryInsert(SimItemStack incoming) {
        if (incoming.isEmpty() || !registry.canFitInsideContainerItems(incoming.definition())) return 0;
        var itemWeight = BundleContents.itemWeight(incoming.components(), registry);
        if (!itemWeight.valid()) return 0;
        int amount = Math.min(incoming.count(), Math.max(Fraction.ONE.subtract(weight).divideBy(itemWeight.value()).intValue(), 0));
        if (amount == 0) return 0;
        weight = weight.add(itemWeight.value().multiplyBy(Fraction.getFraction(amount, 1)));
        int existing = -1;
        if (incoming.isStackable()) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).sameItemSameComponents(incoming)) { existing = i; break; }
            }
        }
        SimItemStack inserted;
        if (existing >= 0) {
            var prior = items.remove(existing);
            inserted = prior.copyWithCount(prior.count() + amount);
            incoming.shrink(amount);
        } else inserted = incoming.split(amount);
        items.addFirst(inserted);
        return amount;
    }

    public void toggleSelectedItem(int index) {
        selectedItem = selectedItem != index && index >= 0 && index < items.size() ? index : -1;
    }
    public SimItemStack removeOne() {
        if (items.isEmpty()) return null;
        int index = selectedItem >= 0 && selectedItem < items.size() ? selectedItem : 0;
        var removed = items.remove(index).copy();
        weight = weight.subtract(BundleContents.itemWeight(removed.components(), registry).getOrThrow()
                .multiplyBy(Fraction.getFraction(removed.count(), 1)));
        toggleSelectedItem(-1);
        return removed;
    }
    public BundleContents toImmutable() {
        return new BundleContents(items.stream().map(ItemTemplate::fromNonEmptyStack).toList(), selectedItem);
    }
}
