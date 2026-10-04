package ac.cult.cultac.utils.blockplace;

import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.minecraft.IsolatedMinecraft;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.placement.api.InteractionEngine;
import ac.cult.placement.api.PlacementEngine;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.DimensionType;

/** Item types and the components block actions read cross the boundary; original vanilla classes own every action. */
public final class VanillaBlockActions {
    private VanillaBlockActions() {}

    public static void useOn(CultPlayer player, BlockPlace place) {
        var pos = place.getPlacedAgainstBlockLocation();
        var cursor = place.getCursor();
        double hitX = pos.getX() + cursor.x, hitY = pos.getY() + cursor.y, hitZ = pos.getZ() + cursor.z;
        // UseItemOn has a hit vector but no camera rotation. The hit supplies the
        // camera that performed the action when rotation movement was not sent yet.
        double dx = hitX - player.x, dy = hitY - (player.y + player.getEyeHeight()), dz = hitZ - player.z;
        float yaw = player.xRot, pitch = player.yRot;
        if (Math.hypot(dx, dz) >= 1e-7 || Math.abs(dy) >= 1e-7) {
            yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        }
        perform(
                player,
                InteractionEngine.Operation.USE_ON,
                place.getHand(),
                pos,
                place.getDirection().name(),
                hitX,
                hitY,
                hitZ,
                place.isInside(),
                yaw,
                pitch,
                place);
    }

    public static void use(CultPlayer player, InteractionHand hand) {
        if (equipmentLocked(player, hand)) return;
        perform(
                player,
                InteractionEngine.Operation.USE,
                hand,
                BlockPos.ZERO,
                "UP",
                0,
                0,
                0,
                false,
                player.xRot,
                player.yRot,
                null);
    }

    /**
     * Equipping by use fails while the worn item has an armor-change-preventing enchantment
     * (Curse of Binding) outside creative. Enchantments do not cross the runtime boundary, so
     * this one enchantment-dependent outcome is decided here: nothing changes.
     */
    private static boolean equipmentLocked(CultPlayer player, InteractionHand hand) {
        var held = player.getInventory().getHandItem(hand);
        var equippable = held == null ? null : held.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        if (equippable == null || player.gamemode == ac.cult.cultac.protocol.value.GameMode.CREATIVE) return false;
        int storage = switch (equippable.slot()) {
            case HEAD -> Inventory.SLOT_HELMET;
            case CHEST -> Inventory.SLOT_CHESTPLATE;
            case LEGS -> Inventory.SLOT_LEGGINGS;
            case FEET -> Inventory.SLOT_BOOTS;
            case BODY -> Inventory.SLOT_BODY;
            case SADDLE -> Inventory.SLOT_SADDLE;
            default -> -1;
        };
        if (storage < 0) return false;
        var worn = player.getInventory().inventory.getInventoryStorage().getItem(storage);
        return worn != null
                && !worn.isEmpty()
                && net.minecraft.world.item.enchantment.EnchantmentHelper.has(
                        worn, net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }

    public static boolean breakBlock(CultPlayer player, BlockPos pos) {
        return perform(
                        player,
                        InteractionEngine.Operation.BREAK,
                        InteractionHand.MAIN_HAND,
                        pos,
                        "UP",
                        pos.getX() + .5,
                        pos.getY() + .5,
                        pos.getZ() + .5,
                        false,
                        player.xRot,
                        player.yRot,
                        null)
                .success();
    }

    private static InteractionEngine.Result perform(
            CultPlayer player,
            InteractionEngine.Operation operation,
            InteractionHand hand,
            BlockPos clicked,
            String face,
            double hitX,
            double hitY,
            double hitZ,
            boolean inside,
            float yaw,
            float pitch,
            BlockPlace place) {
        var model = IsolatedMinecraft.actionsFor(player);
        if (model == null)
            throw new IllegalStateException("Block actions require a compatible isolated vanilla runtime");
        var runtime = model.runtime();
        var registries = player.user.registries();
        var inventory = new ArrayList<InteractionEngine.Stack>(43);
        var storage = player.getInventory().inventory.getInventoryStorage();
        var before = new ItemStack[43];
        for (int slot = 0; slot < 43; slot++) {
            before[slot] = storage.getItem(storageSlot(slot));
            var stack = encode(
                    before[slot], registries.access(), model.clientProtocol().protocol() < 770);
            inventory.add(new InteractionEngine.Stack(
                    model.toModelItem(stack.item()), stack.count(), model.toModelComponents(stack.components())));
        }
        var actor = new InteractionEngine.Actor(
                player.x,
                player.y,
                player.z,
                yaw,
                pitch,
                player.pose.name(),
                player.gamemode.name(),
                player.isSneaking,
                player.food,
                player.canUseGameMasterBlocks(),
                player.getInventory().inventory.selected,
                inventory,
                player.checkManager
                        .getCompensatedCooldown()
                        .hasItem(player.getInventory().getHandItem(hand)),
                player.getScale(),
                player.compensatedEntities.getSelf().getBlockInteractionRange(),
                player.canInstabuild);
        var type = player.compensatedWorld.getVisibleDimensionType();
        // RegistryDataLoader synchronizes dimensions with NETWORK_CODEC. Its
        // environment map removes server-only attributes before the client sees them.
        // Pre-1.21.11 dimensions have no environment map and use DIRECT_CODEC.
        var dimensionCodec = net.minecraft.SharedConstants.getProtocolVersion() >= 774
                ? DimensionType.NETWORK_CODEC
                : DimensionType.DIRECT_CODEC;
        String dimensionType = type == null
                ? null
                : dimensionCodec
                        .encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries.access()), type)
                        .getOrThrow()
                        .toString();
        String dimensionTypeKey = type == null
                ? null
                : registries
                        .access()
                        .lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
                        .listElements()
                        .filter(holder -> holder.value() == type)
                        .map(holder -> ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil.resourceKey(holder.key()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("The visible dimension type is not registered"));
        var world = world(player, model);
        SmoketestPredictionSafety.detachedAdapter(world);
        var result = runtime.interact(new InteractionEngine.Request(
                operation,
                world,
                actor,
                hand.name(),
                new PlacementEngine.Pos(clicked.getX(), clicked.getY(), clicked.getZ()),
                face,
                hitX,
                hitY,
                hitZ,
                inside,
                player.compensatedWorld.getVisibleDimension(),
                model.dimensionType(dimensionTypeKey, dimensionType)));
        var resolvedWrites = result.writes().stream()
                .map(write -> Map.entry(
                        new BlockPos(
                                write.pos().x(), write.pos().y(), write.pos().z()),
                        Block.stateById(model.toHostState(write.state()))))
                .toList();
        var hostInventory = new TreeMap<Integer, InteractionEngine.Stack>();
        result.inventory()
                .forEach((slot, stack) -> hostInventory.put(
                        slot,
                        new InteractionEngine.Stack(
                                model.toHostItem(stack.item()),
                                stack.count(),
                                model.toHostComponents(stack.components()))));
        var resolvedInventory = merge(hostInventory, before, registries.access(), model);
        for (var write : resolvedWrites) {
            var pos = write.getKey();
            var state = write.getValue();
            if (place == null) player.compensatedWorld.updateBlock(pos, state);
            else place.applyResolvedPrediction(pos, state);
        }
        resolvedInventory.forEach((slot, stack) -> storage.setItem(storageSlot(slot), stack));
        for (var cooldown : result.cooldowns())
            player.checkManager
                    .getCompensatedCooldown()
                    .addCooldown(cooldown.group(), cooldown.ticks(), player.lastTransactionReceived.get());
        player.food = result.food();
        return result;
    }

    static int storageSlot(int slot) {
        if (slot < 9) return slot + Inventory.HOTBAR_OFFSET;
        if (slot < 36) return slot;
        return switch (slot) {
            case 36 -> Inventory.SLOT_BOOTS;
            case 37 -> Inventory.SLOT_LEGGINGS;
            case 38 -> Inventory.SLOT_CHESTPLATE;
            case 39 -> Inventory.SLOT_HELMET;
            case 40 -> Inventory.SLOT_OFFHAND;
            case 41 -> Inventory.SLOT_BODY;
            case 42 -> Inventory.SLOT_SADDLE;
            default -> throw new IllegalArgumentException("Invalid vanilla inventory slot " + slot);
        };
    }

    private static final List<DataComponentType<?>> TRANSFERRED = InteractionEngine.TRANSFERRED_COMPONENTS.stream()
            .<DataComponentType<?>>map(id -> ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil.registryValue(
                    BuiltInRegistries.DATA_COMPONENT_TYPE, id))
            .filter(java.util.Objects::nonNull)
            .toList();

    /** The item type, count and its patch of the components the runtime reads (see {@link InteractionEngine.Stack}). */
    static InteractionEngine.Stack encode(
            ItemStack stack, net.minecraft.core.RegistryAccess registries, boolean legacyTool) {
        if (stack == null || stack.isEmpty()) return InteractionEngine.Stack.EMPTY;
        var patch = stack.getComponentsPatch().forget(type -> !TRANSFERRED.contains(type));
        var components = patch.isEmpty()
                ? null
                : ac.cult.cultac.network.codec.NativeAdventurePredicates.encodePatch(patch, registries);
        // Modern item defaults may contain TOOL.can_destroy=false, while the old
        // class predicate is independent of TOOL. Materialize the effective TOOL
        // only in the legacy action view so the exact old codec restores true,
        // including unpatched defaults. A real removal remains a removal.
        var tool = stack.get(net.minecraft.core.component.DataComponents.TOOL);
        if (legacyTool && tool != null) {
            if (components == null) components = new com.google.gson.JsonObject();
            components.add(
                    "minecraft:tool",
                    net.minecraft.core.component.DataComponents.TOOL
                            .codec()
                            .encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), tool)
                            .getOrThrow());
        }
        // Keep this class behind the actual host capability: older native models do
        // not define BlockTransformer, while modern custom holders must retain data.
        if (components != null && net.minecraft.SharedConstants.getProtocolVersion() == 777)
            ac.cult.cultac.network.codec.NativeBlockTransformers.inline(components, stack, registries);
        return new InteractionEngine.Stack(
                ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil.registryKey(BuiltInRegistries.ITEM, stack.getItem()),
                stack.getCount(),
                components == null ? null : components.toString());
    }

    private static ItemStack decode(
            InteractionEngine.Stack stack, net.minecraft.core.RegistryAccess registries, ItemStack preferred) {
        if (stack.count() <= 0 || stack.item().equals("minecraft:air")) return ItemStack.EMPTY;
        var item = ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil.registryValue(BuiltInRegistries.ITEM, stack.item());
        if (item == null) throw new IllegalArgumentException("Unknown host item " + stack.item());
        if (stack.components() == null) return new ItemStack(item, stack.count());
        var components = JsonParser.parseString(stack.components()).getAsJsonObject();
        if (net.minecraft.SharedConstants.getProtocolVersion() == 777)
            ac.cult.cultac.network.codec.NativeBlockTransformers.restore(components, registries, preferred);
        return new ItemStack(
                item.builtInRegistryHolder(),
                stack.count(),
                DataComponentPatch.CODEC
                        .parse(RegistryOps.create(JsonOps.INSTANCE, registries), components)
                        .getOrThrow());
    }

    /**
     * Changed slots as the client now holds them. The runtime saw only item types and the
     * transferred components, so each result keeps the player's real stack: from the same slot
     * when the item type is unchanged, else from the changed slot it moved out of (an equipment
     * swap), else the new item's defaults (a filled bucket). Count and transferred components
     * come from the runtime.
     */
    static Map<Integer, ItemStack> merge(
            Map<Integer, InteractionEngine.Stack> changes,
            ItemStack[] before,
            net.minecraft.core.RegistryAccess registries,
            IsolatedMinecraft.Binding model) {
        var merged = new TreeMap<Integer, ItemStack>();
        var after = new TreeMap<Integer, ItemStack>();
        changes.forEach((slot, stack) -> after.put(slot, decode(stack, registries, before[slot])));
        var sources = new ArrayList<Integer>();
        after.forEach((slot, stack) -> {
            if (!stack.isEmpty() && !before[slot].isEmpty() && before[slot].getItem() == stack.getItem())
                merged.put(slot, withTransferred(before[slot], stack, model, registries));
            else sources.add(slot);
        });
        var moved = new ArrayList<>(sources);
        for (int slot : sources) {
            var stack = after.get(slot);
            if (stack.isEmpty()) {
                merged.put(slot, ItemStack.EMPTY);
                continue;
            }
            Integer origin = moved.stream()
                    .filter(from -> !before[from].isEmpty() && before[from].getItem() == stack.getItem())
                    .findFirst()
                    .orElse(null);
            if (origin == null) {
                merged.put(slot, stack);
                continue;
            }
            moved.remove(origin);
            merged.put(slot, withTransferred(before[origin], stack, model, registries));
        }
        return merged;
    }

    private static ItemStack withTransferred(
            ItemStack real,
            ItemStack predicted,
            IsolatedMinecraft.Binding model,
            net.minecraft.core.RegistryAccess registries) {
        var stack = real.copyWithCount(predicted.getCount());
        for (var type : TRANSFERRED) {
            String componentName = ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil.registryKey(
                    BuiltInRegistries.DATA_COMPONENT_TYPE, type);
            // Older clients never received this component. Their changed count/equipment
            // must retain the native server's legitimate transformer patch unchanged.
            if (model.clientProtocol().protocol() < 777 && "minecraft:block_transformer".equals(componentName))
                continue;
            if (net.minecraft.SharedConstants.getProtocolVersion() == 777
                    && "minecraft:block_transformer".equals(componentName)
                    && ac.cult.cultac.network.codec.NativeBlockTransformers.same(real, predicted, registries)) continue;
            // Old Item/SwordItem/TridentItem own the creative guard independently
            // of TOOL. Its action-only adaptation must never replace the real patch
            // when the same native stack changes count or moves equipment slots.
            if (model.clientProtocol().protocol() < 770 && "minecraft:tool".equals(componentName)) continue;
            if (ac.cult.cultac.network.codec.NativeAdventurePredicates.sameActionView(
                    type, real, predicted, registries, model)) continue;
            copy(type, predicted, stack);
        }
        return stack;
    }

    private static <T> void copy(DataComponentType<T> type, ItemStack from, ItemStack to) {
        T value = from.get(type);
        if (value != null) to.set(type, value);
        else to.remove(type);
    }

    private static PlacementEngine.World world(CultPlayer player, IsolatedMinecraft.Binding model) {
        var border = player.checkManager.getListener(PacketWorldBorder.class);
        var tags = model.tagsFor(player);
        return new ac.cult.cultac.utils.minecraft.CompensatedBlockView(player.compensatedWorld) {
            public ac.cult.placement.api.GeometryTags tags() {
                return tags;
            }

            public int stateAt(int x, int y, int z) {
                return model.toModelState(super.stateAt(x, y, z));
            }

            public int minY() {
                return player.compensatedWorld.getMinHeight();
            }

            public int height() {
                return player.compensatedWorld.getHeight();
            }

            public boolean insideBorder(int x, int z) {
                double radius = border.getCurrentDiameter() / 2;
                double limit = border.getAbsoluteMaxSize();
                return x >= Math.max(-limit, border.getCenterX() - radius)
                        && x < Math.min(limit, border.getCenterX() + radius)
                        && z >= Math.max(-limit, border.getCenterZ() - radius)
                        && z < Math.min(limit, border.getCenterZ() + radius);
            }

            public boolean clear(PlacementEngine.Pos pos, List<PlacementEngine.Box> shape) {
                for (var box : shape) {
                    var bounds = new SimpleCollisionBox(
                            box.minX() + pos.x(),
                            box.minY() + pos.y(),
                            box.minZ() + pos.z(),
                            box.maxX() + pos.x(),
                            box.maxY() + pos.y(),
                            box.maxZ() + pos.z(),
                            false);
                    if (bounds.isIntersected(player.boundingBox)) {
                        return false;
                    }
                    for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
                        var entityBox = entity.getPossibleMovementCollisionBoxes();
                        double width = BoundingBoxSize.getWidth(player, entity),
                                height = BoundingBoxSize.getHeight(player, entity);
                        if (Math.max(entityBox.maxX - entityBox.minX, entityBox.maxZ - entityBox.minZ) - width > 0.05
                                || entityBox.maxY - entityBox.minY - height > 0.05) {
                            var position = entity.desyncClientPos;
                            entityBox = GetBoundingBox.getPacketEntityBoundingBox(
                                    player, position.x, position.y, position.z, entity);
                        }
                        if (bounds.isIntersected(entityBox)) return false;
                    }
                }
                return true;
            }
        };
    }
}
