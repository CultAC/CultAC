package ac.cult.blocksim.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import ac.cult.blocksim.data.nbt.NbtValue;

/** Portable codec values. Copies at the boundary keep mutable JSON out of shared data. */
public final class Components {
    private static final Set<String> RAW_NBT = Set.of("minecraft:custom_data", "minecraft:bucket_entity_data",
            "minecraft:entity_data", "minecraft:block_entity_data");
    public static final Components EMPTY = new Components(Map.of());
    private final Map<String, JsonElement> values;
    private final Map<String, NbtValue> encodedNbt;
    private final Map<String, NbtValue.Compound> layouts;
    private final BundleContents bundle;
    private final Map<String, ComponentWireEncoding> wireEncodings;

    public Components(Map<String, JsonElement> values) {
        this(values, Map.of());
    }
    /** Both codec encodings are captured at the registry/packet boundary, without native objects. */
    public Components(Map<String, JsonElement> values, Map<String, NbtValue> encodedNbt) {
        this(values, encodedNbt, Map.of());
    }
    public Components(Map<String, JsonElement> values, Map<String, NbtValue> encodedNbt,
                      Map<String, NbtValue.Compound> layouts) {
        this(values, encodedNbt, layouts, null, Map.of());
    }
    private Components(Map<String, JsonElement> values, Map<String, NbtValue> encodedNbt,
                       Map<String, NbtValue.Compound> layouts, BundleContents bundle,
                       Map<String, ComponentWireEncoding> wireEncodings) {
        this(values, encodedNbt, layouts, bundle, wireEncodings, true);
    }
    /** Only this class can assemble trees it already owns; public boundaries always copy. */
    private Components(Map<String, JsonElement> values, Map<String, NbtValue> encodedNbt,
                       Map<String, NbtValue.Compound> layouts, BundleContents bundle,
                       Map<String, ComponentWireEncoding> wireEncodings, boolean copyJson) {
        if (copyJson) {
            var copy = new HashMap<String, JsonElement>();
            values.forEach((key, value) -> {
                var layout = layouts.get(key);
                copy.put(key, ComponentNumbers.restore(value.deepCopy(), layout == null ? null : layout.values().get("negative_zero")));
            });
            this.values = Map.copyOf(copy);
        } else this.values = Map.copyOf(values);
        this.encodedNbt = Map.copyOf(encodedNbt);
        this.layouts = Map.copyOf(layouts);
        this.wireEncodings = Map.copyOf(wireEncodings);
        this.bundle = !has(BundleContents.COMPONENT) ? null : bundle != null ? bundle
                : new BundleContents(ItemComponents.itemTemplates(this, BundleContents.COMPONENT));
    }
    public ComponentWireEncoding wireEncoding(String key) { return wireEncodings.get(key); }
    public Components withWireEncodings(Map<String, ComponentWireEncoding> encodings) {
        var retained = new HashMap<String, ComponentWireEncoding>();
        encodings.forEach((key, value) -> { if (has(key)) retained.put(key, value); });
        var encoding = retained.get(BundleContents.COMPONENT);
        var contents = bundle != null && encoding != null ? bundle.withItemEncodings(encoding) : bundle;
        return new Components(values, encodedNbt, layouts, contents, retained, false);
    }
    public BundleContents bundle() { return bundle; }
    /** Source-defined slot layouts; absent details mean the persistent codec's canonical layout. */
    public NbtValue.Compound layout(String key) { return layouts.get(key); }
    public NbtValue.Compound encodedLayouts() { return new NbtValue.Compound(new HashMap<>(layouts)); }
    public NbtValue encodedNbt(String key) {
        if (!has(key)) return null;
        NbtValue result = encodedNbt.get(key);
        if (result == null) throw new IllegalStateException("Missing typed component encoding: " + key);
        return result;
    }
    public boolean hasEncodedNbt(String key) { return encodedNbt.containsKey(key); }
    /** A full subset would preserve every metadata entry as well as every value. */
    boolean hasOnlyValueMetadata() {
        return values.keySet().containsAll(encodedNbt.keySet())
                && values.keySet().containsAll(layouts.keySet())
                && values.keySet().containsAll(wireEncodings.keySet());
    }
    public NbtValue.Compound encodedNbt() {
        var result = new HashMap<String, NbtValue>();
        keys().forEach(key -> result.put(key, encodedNbt(key)));
        return new NbtValue.Compound(result);
    }
    /** {@code encodedNbt().equals(other.encodedNbt())}, throwing what either call throws, this side first. */
    public boolean sameEncodedNbt(Components other) {
        for (String key : keys()) encodedNbt(key);
        for (String key : other.keys()) other.encodedNbt(key);
        if (keys().size() != other.keys().size()) return false;
        for (String key : keys()) if (!encodedNbt(key).equals(other.encodedNbt(key))) return false;
        return true;
    }
    public Components subset(Set<String> keys) {
        if (keys.isEmpty()) return EMPTY;
        var selected = new HashMap<String, JsonElement>();
        var nbt = new HashMap<String, NbtValue>();
        var details = new HashMap<String, NbtValue.Compound>();
        var wire = new HashMap<String, ComponentWireEncoding>();
        for (String key : keys) if (has(key)) {
            selected.put(key, values.get(key));
            if (wireEncodings.containsKey(key)) wire.put(key, wireEncodings.get(key));
            if (encodedNbt.containsKey(key)) nbt.put(key, encodedNbt.get(key));
            if (layouts.containsKey(key)) details.put(key, layouts.get(key));
        }
        return new Components(selected, nbt, details, selected.containsKey(BundleContents.COMPONENT) ? bundle : null, wire, false);
    }
    /** Apply a patch while retaining typed encodings for unchanged components. */
    public Components overlay(Components added, Set<String> removed) {
        return overlay(added, removed, false);
    }
    /** The enclosing stack already retains both its captured prototype and patch. */
    Components overlayCapturedPrototype(Components added, Set<String> removed) {
        return overlay(added, removed, true);
    }
    private Components overlay(Components added, Set<String> removed, boolean prototypeRetained) {
        if (added.values.isEmpty() && removed.isEmpty()) return this;
        // Most stacks apply one small received patch to a shared prototype. Keep
        // those immutable inputs instead of copying all four complete maps.
        // Repatching a view takes the existing materializing path, so lookup
        // depth and the retained object graph never grow with edit history.
        if (prototypeRetained && !hasOverlayMaps() && !added.hasOverlayMaps())
            return new Components(this, added, Set.copyOf(removed));
        var result = new HashMap<>(values); var nbt = new HashMap<>(encodedNbt);
        var details = new HashMap<>(layouts);
        var wire = new HashMap<>(wireEncodings);
        removed.forEach(key -> { result.remove(key); nbt.remove(key); details.remove(key); wire.remove(key); });
        added.values.forEach((key, value) -> {
            result.put(key, value); nbt.remove(key);
            wire.remove(key);
            if (added.wireEncodings.containsKey(key)) wire.put(key, added.wireEncodings.get(key));
            if (added.encodedNbt.containsKey(key)) nbt.put(key, added.encodedNbt.get(key));
            details.remove(key);
            if (added.layouts.containsKey(key)) details.put(key, added.layouts.get(key));
        });
        return new Components(result, nbt, details, added.has(BundleContents.COMPONENT) ? added.bundle
                : removed.contains(BundleContents.COMPONENT) ? null : bundle, wire, false);
    }

    private boolean hasOverlayMaps() {
        return values instanceof OverlayMap<?> || encodedNbt instanceof OverlayMap<?>
                || layouts instanceof OverlayMap<?> || wireEncodings instanceof OverlayMap<?>;
    }

    private Components(Components prototype, Components added, Set<String> removed) {
        var overridden = added.values.keySet();
        values = OverlayMap.of(prototype.values, added.values, overridden, removed);
        encodedNbt = OverlayMap.of(prototype.encodedNbt, added.encodedNbt, overridden, removed);
        layouts = OverlayMap.of(prototype.layouts, added.layouts, overridden, removed);
        wireEncodings = OverlayMap.of(prototype.wireEncodings, added.wireEncodings, overridden, removed);
        bundle = added.has(BundleContents.COMPONENT) ? added.bundle
                : removed.contains(BundleContents.COMPONENT) ? null : prototype.bundle;
    }

    /** A single immutable overlay; metadata absent from an override hides the prototype's metadata too. */
    private static final class OverlayMap<V> extends java.util.AbstractMap<String, V> {
        private final Map<String, V> prototype, added;
        private final Set<String> overridden, removed;
        private final int size;

        private OverlayMap(Map<String, V> prototype, Map<String, V> added,
                           Set<String> overridden, Set<String> removed) {
            this.prototype = prototype;
            this.added = added;
            this.overridden = overridden;
            this.removed = removed;
            int count = 0;
            for (String key : prototype.keySet()) if (retains(key)) count++;
            for (String key : added.keySet()) if (overridden.contains(key)) count++;
            size = count;
        }

        static <V> Map<String, V> of(Map<String, V> prototype, Map<String, V> added,
                                    Set<String> overridden, Set<String> removed) {
            if (prototype.isEmpty() && added.isEmpty()) return Map.of();
            if (overridden.isEmpty() && removed.isEmpty()) return prototype;
            return new OverlayMap<>(prototype, added, overridden, removed);
        }

        private boolean retains(String key) { return !overridden.contains(key) && !removed.contains(key); }

        @Override public V get(Object key) {
            java.util.Objects.requireNonNull(key);
            return overridden.contains(key) ? added.get(key) : removed.contains(key) ? null : prototype.get(key);
        }

        @Override public boolean containsKey(Object key) { return get(key) != null; }
        @Override public int size() { return size; }
        @Override public Set<String> keySet() { return java.util.Collections.unmodifiableSet(super.keySet()); }

        @Override public void forEach(java.util.function.BiConsumer<? super String, ? super V> action) {
            java.util.Objects.requireNonNull(action);
            prototype.forEach((key, value) -> { if (retains(key)) action.accept(key, value); });
            added.forEach((key, value) -> { if (overridden.contains(key)) action.accept(key, value); });
        }

        @Override public Set<Entry<String, V>> entrySet() {
            return java.util.Collections.unmodifiableSet(new java.util.AbstractSet<>() {
                @Override public int size() { return size; }
                @Override public java.util.Iterator<Entry<String, V>> iterator() {
                    return new java.util.Iterator<>() {
                        private final java.util.Iterator<Entry<String, V>> defaults = prototype.entrySet().iterator();
                        private final java.util.Iterator<Entry<String, V>> overrides = added.entrySet().iterator();
                        private Entry<String, V> next;
                        @Override public boolean hasNext() {
                            while (next == null && defaults.hasNext()) {
                                var entry = defaults.next();
                                if (retains(entry.getKey())) next = entry;
                            }
                            while (next == null && overrides.hasNext()) {
                                var entry = overrides.next();
                                if (overridden.contains(entry.getKey())) next = entry;
                            }
                            return next != null;
                        }
                        @Override public Entry<String, V> next() {
                            if (!hasNext()) throw new java.util.NoSuchElementException();
                            var result = next;
                            next = null;
                            return result;
                        }
                    };
                }
            });
        }
    }

    public static Components parse(String json) {
        var object = JsonParser.parseString(json).getAsJsonObject();
        return new Components(object.asMap());
    }

    public boolean has(String key) { return values.containsKey(key); }
    /** Boundary encodings preserve NBT kinds and normalize codec booleans independently of JSON. */
    public boolean sameValue(String key, Components other) {
        if (AdventurePredicateData.isComponent(key)) return java.util.Objects.equals(identity(key), other.identity(key));
        if (key.equals(BundleContents.COMPONENT)) return java.util.Objects.equals(bundle, other.bundle);
        if (key.equals(ComponentIdentity.TOOLTIP_DISPLAY)) return java.util.Objects.equals(identity(key), other.identity(key));
        if (!java.util.Objects.equals(layouts.get(key), other.layouts.get(key))) return false;
        if (RAW_NBT.contains(key)) return java.util.Objects.equals(identity(key), other.identity(key));
        if (encodedNbt.containsKey(key) && other.encodedNbt.containsKey(key))
            return java.util.Objects.equals(encodedNbt.get(key), other.encodedNbt.get(key));
        return java.util.Objects.equals(values.get(key), other.values.get(key));
    }
    public Set<String> keys() { return values.keySet(); }
    public JsonElement get(String key) {
        JsonElement value = values.get(key);
        return value == null ? null : value.deepCopy();
    }
    /** The stored tree itself, for read-only views in this package. */
    JsonElement view(String key) { return values.get(key); }
    public int integer(String key, int fallback) {
        JsonElement value = values.get(key);
        return value == null ? fallback : value.getAsInt();
    }
    public Components with(String key, JsonElement value) {
        return with(key, value, java.util.Objects.equals(values.get(key), value) ? encodedNbt.get(key) : null);
    }
    public Components with(String key, JsonElement value, NbtValue encoding) {
        var copy = new HashMap<>(values);
        var nbt = new HashMap<>(encodedNbt);
        var details = new HashMap<>(layouts);
        nbt.remove(key);
        if (value == null) copy.remove(key);
        else copy.put(key, value.deepCopy());
        if (value != null && encoding != null) nbt.put(key, encoding);
        details.remove(key);
        var wire = new HashMap<>(wireEncodings); wire.remove(key);
        return new Components(copy, nbt, details, key.equals(BundleContents.COMPONENT) ? null : bundle, wire, false);
    }
    /** Preserve local selection/cache while exposing the ordinary persistent encoding at boundaries. */
    public Components withBundle(BundleContents value) {
        java.util.Objects.requireNonNull(value);
        var json = new com.google.gson.JsonArray();
        var children = new HashMap<String, NbtValue>();
        boolean typed = true;
        for (int i = 0; i < value.items().size(); i++) {
            var item = value.items().get(i);
            json.add(item.json());
            typed &= item.patch().added().keys().stream().allMatch(item.patch().added()::hasEncodedNbt);
            var child = item.patch().added().encodedLayouts();
            if (!child.values().isEmpty()) children.put(Integer.toString(i), child);
        }
        var copy = new HashMap<>(values); copy.put(BundleContents.COMPONENT, json);
        var nbt = new HashMap<>(encodedNbt); nbt.remove(BundleContents.COMPONENT);
        if (typed) nbt.put(BundleContents.COMPONENT, new NbtValue.Sequence(value.items().stream().map(ItemTemplate::encodedNbt).toList()));
        var details = new HashMap<>(layouts); details.remove(BundleContents.COMPONENT);
        if (!children.isEmpty()) details.put(BundleContents.COMPONENT, new NbtValue.Compound(Map.of("items", new NbtValue.Compound(children))));
        var wire = new HashMap<>(wireEncodings); wire.remove(BundleContents.COMPONENT);
        return new Components(copy, nbt, details, value, wire, false);
    }
    public Map<String, String> stringMap(String key) {
        JsonElement value = values.get(key);
        if (value == null) return Map.of();
        var map = new HashMap<String, String>();
        value.getAsJsonObject().entrySet().forEach(entry -> map.put(entry.getKey(), entry.getValue().getAsString()));
        return Map.copyOf(map);
    }
    public String json() {
        var object = new JsonObject();
        values.forEach(object::add);
        return object.toString();
    }
    private Object identity(String key) {
        if (AdventurePredicateData.isComponent(key)) return has(key) ? AdventurePredicateData.identity(this, key) : null;
        if (key.equals(BundleContents.COMPONENT)) return bundle;
        // Generated JSON and received NBT expose floats/booleans differently. Their typed
        // codec value is the common identity; source-defined omitted details remain below.
        Object value = key.equals(ComponentIdentity.TOOLTIP_DISPLAY) ? ComponentIdentity.tooltipDisplay(values.get(key))
                : encodedNbt.containsKey(key) ? encodedNbt.get(key) : values.get(key);
        return layouts.containsKey(key) ? new LayoutIdentity(value, layouts.get(key)) : value;
    }
    private record LayoutIdentity(Object value, NbtValue.Compound layout) {}
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Components components) || !values.keySet().equals(components.values.keySet())) return false;
        for (String key : values.keySet()) if (!java.util.Objects.equals(identity(key), components.identity(key))) return false;
        return true;
    }
    @Override public int hashCode() {
        var hash = new IdentityHash();
        values.forEach(hash);
        return hash.value;
    }
    private final class IdentityHash implements java.util.function.BiConsumer<String, JsonElement> {
        private int value;
        @Override public void accept(String key, JsonElement ignored) {
            value += key.hashCode() ^ identity(key).hashCode();
        }
    }
}
