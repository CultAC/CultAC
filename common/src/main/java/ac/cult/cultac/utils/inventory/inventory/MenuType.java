package ac.cult.cultac.utils.inventory.inventory;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;

@Getter
public enum MenuType {
    GENERIC_9x1,
    GENERIC_9x2,
    GENERIC_9x3,
    GENERIC_9x4,
    GENERIC_9x5,
    GENERIC_9x6,
    GENERIC_3x3,
    CRAFTER_3x3,
    ANVIL,
    BEACON,
    BLAST_FURNACE,
    BREWING_STAND,
    CRAFTING,
    ENCHANTMENT,
    FURNACE,
    GRINDSTONE,
    HOPPER,
    LECTERN,
    LOOM,
    MERCHANT,
    SHULKER_BOX,
    SMITHING,
    SMOKER,
    CARTOGRAPHY_TABLE,
    STONECUTTER,
    UNKNOWN(null);

    private static final Map<String, MenuType> BY_REGISTRY_KEY = Arrays.stream(values())
            .filter(type -> type.registryKey != null)
            .collect(Collectors.toUnmodifiableMap(MenuType::getRegistryKey, Function.identity()));

    private final String registryKey;

    MenuType() {
        this.registryKey = "minecraft:" + name().toLowerCase(Locale.ROOT);
    }

    MenuType(String registryKey) {
        this.registryKey = registryKey;
    }

    public int getId() {
        if (registryKey == null) {
            return -1;
        }

        return ModelRegistryData.load(ProtocolVersion.V26_3)
                .registry("minecraft:menu")
                .id(registryKey);
    }

    public static MenuType fromRegistryKey(String registryKey) {
        return registryKey == null ? UNKNOWN : BY_REGISTRY_KEY.getOrDefault(registryKey, UNKNOWN);
    }
}
