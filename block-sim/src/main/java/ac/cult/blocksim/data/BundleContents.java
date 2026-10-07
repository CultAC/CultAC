package ac.cult.blocksim.data;

import java.util.List;
import org.apache.commons.lang3.math.Fraction;

/** Owned bundle templates. Selection and the client-derived weight do not participate in identity. */
public final class BundleContents {
    public static final String COMPONENT = "minecraft:bundle_contents";
    private final List<ItemTemplate> items;
    private final int selectedItem;
    private Weight cachedWeight;

    public BundleContents(List<ItemTemplate> items) { this(items, -1); }
    public BundleContents(List<ItemTemplate> items, int selectedItem) {
        this.items = List.copyOf(items);
        this.selectedItem = selectedItem;
    }
    BundleContents withItemEncodings(ComponentWireEncoding encoding) {
        var children = new java.util.ArrayList<ItemTemplate>(items.size());
        for (int index = 0; index < items.size(); index++)
            children.add(items.get(index).withWireEncodings(encoding.item(Integer.toString(index))));
        var result = new BundleContents(children, selectedItem);
        result.cachedWeight = cachedWeight;
        return result;
    }

    public List<ItemTemplate> items() { return items; }
    public int selectedItem() { return selectedItem; }

    /** The client memoizes this result on first use, including an invalid total. No locks or global cache. */
    public Weight weight(ItemRegistry registry) {
        if (cachedWeight != null) return cachedWeight;
        try {
            Fraction total = Fraction.ZERO;
            for (var item : items) {
                var weight = itemWeight(item.components(registry), registry);
                if (!weight.valid()) return cachedWeight = weight;
                total = total.add(weight.value().multiplyBy(Fraction.getFraction(item.count(), 1)));
            }
            return cachedWeight = new Weight(total);
        } catch (ArithmeticException exception) {
            return cachedWeight = new Weight(null);
        }
    }

    public static Weight itemWeight(Components components, ItemRegistry registry) {
        var nested = components.bundle();
        if (nested != null) {
            var weight = nested.weight(registry);
            return weight.valid() ? new Weight(weight.value().add(Fraction.getFraction(1, 16))) : weight;
        }
        var bees = components.get("minecraft:bees");
        return new Weight(bees != null && !bees.getAsJsonArray().isEmpty() ? Fraction.ONE
                : Fraction.getFraction(1, components.integer("minecraft:max_stack_size", 1)));
    }

    public record Weight(Fraction value) {
        public boolean valid() { return value != null; }
        public Fraction getOrThrow() {
            if (!valid()) throw new IllegalStateException("Excessive total bundle weight");
            return value;
        }
    }
    @Override public boolean equals(Object other) { return other instanceof BundleContents bundle && items.equals(bundle.items); }
    @Override public int hashCode() { return items.hashCode(); }
}
