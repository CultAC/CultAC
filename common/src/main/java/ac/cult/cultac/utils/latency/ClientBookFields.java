package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.nbt.NbtValue;
import java.util.Map;

/** Lecterns need the effective page count, not book text or author/title data. */
public final class ClientBookFields {
    private ClientBookFields() {}

    public static boolean isContentComponent(String name) {
        String key =
                ClientContainerFields.reference(new NbtValue.Text(name.startsWith("!") ? name.substring(1) : name));
        return "minecraft:written_book_content".equals(key) || "minecraft:writable_book_content".equals(key);
    }

    /** Normalize aliases before the packet is queued; discarded names and values are transient. */
    public static NbtValue.Compound contentComponents(NbtValue.Compound components) {
        var result = new java.util.HashMap<String, NbtValue>();
        for (var entry : components.values().entrySet()) {
            String name = entry.getKey();
            if (!isContentComponent(name)) continue;
            boolean removed = name.startsWith("!");
            String key = ClientContainerFields.reference(new NbtValue.Text(removed ? name.substring(1) : name));
            result.remove(key);
            result.remove("!" + key);
            result.put(removed ? "!" + key : key, entry.getValue());
        }
        return new NbtValue.Compound(result);
    }

    static NbtValue.Compound lectern(NbtValue.Compound tag) {
        var book = ClientContainerFields.item(tag.values().get("Book"));
        int pageCount = 0;
        boolean hasBook = false;
        if (book != null) {
            var defaults = ac.cult.cultac.utils.inventory.ItemUtil.modelItems()
                    .defaults(((NbtValue.Text) book.values().get("id")).value());
            var input = (NbtValue.Compound) tag.values().get("Book");
            var patch = input.values().get("components") instanceof NbtValue.Compound components
                    ? components.values()
                    : Map.<String, NbtValue>of();
            for (String key : java.util.List.of("minecraft:written_book_content", "minecraft:writable_book_content")) {
                NbtValue content = defaults.encodedNbt(key);
                for (var entry : patch.entrySet()) {
                    String name = entry.getKey();
                    boolean removed = name.startsWith("!");
                    if (key.equals(
                            ClientContainerFields.reference(new NbtValue.Text(removed ? name.substring(1) : name))))
                        content = removed ? null : entry.getValue();
                }
                if (!(content instanceof NbtValue.Compound fields)) continue;
                hasBook = true;
                if (fields.values().get("pages") instanceof NbtValue.Sequence pages)
                    pageCount = pages.values().size();
                break;
            }
        }
        // Mth.clamp returns -1 when a present book has no pages.
        int page =
                Math.min(Math.max(ClientBlockEntityFields.integer(tag.values().get("Page")), 0), pageCount - 1);
        return new NbtValue.Compound(Map.of(
                "page_count",
                new NbtValue.Numeric(NbtValue.Kind.INT, pageCount),
                "has_book_content",
                new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) (hasBook ? 1 : 0)),
                "Page",
                new NbtValue.Numeric(NbtValue.Kind.INT, page)));
    }
}
