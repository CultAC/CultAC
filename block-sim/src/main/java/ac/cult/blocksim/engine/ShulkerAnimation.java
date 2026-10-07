package ac.cult.blocksim.engine;

import com.google.gson.JsonPrimitive;

/** ShulkerBoxBlockEntity.triggerEvent and updateAnimation, without entity movement. */
public final class ShulkerAnimation {
    private ShulkerAnimation() { }
    public static BlockEntityData event(BlockEntityData entity, int action, int parameter) {
        if (!entity.type().equals("minecraft:shulker_box") || action != 1 || parameter != 0 && parameter != 1) return entity;
        return fields(entity, entity.data().with("animation_status", new JsonPrimitive(parameter == 0 ? "CLOSING" : "OPENING")));
    }
    public static BlockEntityData tick(BlockEntityData entity) {
        if (!entity.type().equals("minecraft:shulker_box")) return entity;
        var fields = entity.data();
        var encoded = fields.get("animation_status"); String status = encoded == null ? "CLOSED" : encoded.getAsString();
        var value = fields.get("progress"); float previous = value == null ? 0 : value.getAsFloat(), progress = previous;
        switch (status) {
            case "CLOSED" -> progress = 0;
            case "OPENED" -> progress = 1;
            case "OPENING" -> { progress += 0.1F; if (progress >= 1) { progress = 1; status = "OPENED"; } }
            case "CLOSING" -> { progress -= 0.1F; if (progress <= 0) { progress = 0; status = "CLOSED"; } }
            default -> throw new IllegalArgumentException("Unknown client animation " + status);
        }
        fields = fields.with("animation_status", new JsonPrimitive(status)).with("progress", new JsonPrimitive(progress)).with("progress_old", new JsonPrimitive(previous));
        return fields(entity, fields);
    }
    private static BlockEntityData fields(BlockEntityData entity, ac.cult.blocksim.data.Components fields) {
        return fields.equals(entity.data()) ? entity : new BlockEntityData(entity.type(), fields, entity.savedData());
    }
}
