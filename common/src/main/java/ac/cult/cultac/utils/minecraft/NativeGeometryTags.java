package ac.cult.cultac.utils.minecraft;

import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelRegistryNames;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import ac.cult.placement.api.GeometryTags;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/** Captures the active native bindings using values safe to cross the isolated loader. */
public final class NativeGeometryTags {
    private NativeGeometryTags() {}

    /** Project received tags through the client before resolving them in the isolated runtime. */
    public static GeometryTags project(
            GeometryTags tags, ProtocolVersion source, ProtocolVersion client, ProtocolVersion model) {
        return new GeometryTags(
                ProtocolCodecs.projectTags("minecraft:block", tags.blocks(), source, client, model),
                ProtocolCodecs.projectTags("minecraft:item", tags.items(), source, client, model),
                ProtocolCodecs.projectTags("minecraft:fluid", tags.fluids(), source, client, model),
                ProtocolCodecs.projectTags("minecraft:entity_type", tags.entities(), source, client, model));
    }

    public static GeometryTags capture() {
        return new GeometryTags(
                capture(BuiltInRegistries.BLOCK, UnaryOperator.identity()),
                capture(BuiltInRegistries.ITEM, UnaryOperator.identity()),
                capture(BuiltInRegistries.FLUID, UnaryOperator.identity()),
                capture(BuiltInRegistries.ENTITY_TYPE, UnaryOperator.identity()));
    }

    public static GeometryTags capture(ModelRegistryNames names) {
        return new GeometryTags(
                capture(BuiltInRegistries.BLOCK, names::modelBlock),
                capture(BuiltInRegistries.ITEM, names::modelItem),
                capture(BuiltInRegistries.FLUID, UnaryOperator.identity()));
    }

    public static GeometryTags capture(ModelRegistryNames names, ProtocolVersion source, ProtocolVersion target) {
        return translate(capture(), names, source, target);
    }

    /** Preserve the established geometry maps while carrying its explicit action entity context. */
    public static GeometryTags translate(
            GeometryTags tags, ModelRegistryNames names, ProtocolVersion source, ProtocolVersion target) {
        var geometry = translate(tags, names);
        return new GeometryTags(
                geometry.blocks(),
                geometry.items(),
                geometry.fluids(),
                ProtocolCodecs.projectTags("minecraft:entity_type", tags.entities(), source, source, target));
    }

    public static GeometryTags translate(GeometryTags tags, ModelRegistryNames names) {
        return new GeometryTags(
                translate(tags.blocks(), names::modelBlock), translate(tags.items(), names::modelItem), tags.fluids());
    }

    private static Map<String, List<String>> translate(Map<String, List<String>> tags, UnaryOperator<String> names) {
        Map<String, List<String>> translated = new HashMap<>();
        tags.forEach((tag, members) -> translated.put(
                tag,
                members.stream().map(names).filter(java.util.Objects::nonNull).toList()));
        return translated;
    }

    private static <T> Map<String, List<String>> capture(Registry<T> registry, UnaryOperator<String> names) {
        Map<String, List<String>> tags = new HashMap<>();
        registry.getTags()
                .forEach(tag -> tags.put(
                        NmsIdentifierUtil.tagKey(tag.key()),
                        tag.stream()
                                .map(holder -> names.apply(NmsIdentifierUtil.registryKey(registry, holder.value())))
                                .filter(java.util.Objects::nonNull)
                                .toList()));
        return tags;
    }
}
