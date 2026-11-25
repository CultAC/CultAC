package ac.cult.cultac.player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Client identity reported by the brand, registered channels and companion. {@code channelType} is the one type
 * {@link #channelType(String)} gives the registered channels, {@link Type#UNKNOWN} when they give several and
 * {@link Type#UNSET} when none; {@code channelTypes} lists every family {@link #channelFamily} names among them, in
 * {@link Type} order, for staff and search.
 */
public record ClientClassification(Type brandType, Type channelType, Type modType, List<Type> channelTypes) {
    public enum Type {
        VANILLA("Vanilla"),
        FABRIC("Fabric"),
        FORGE("Forge"),
        NEOFORGE("NeoForge"),
        QUILT("Quilt"),
        LUNAR("Lunar"),
        BADLION("Badlion"),
        FEATHER("Feather"),
        LABYMOD("LabyMod"),
        BEDROCK("Bedrock"),
        UNKNOWN("Unknown"),
        UNSET("None");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        /** The name shown to staff. */
        public String label() {
            return label;
        }
    }

    public ClientClassification {
        channelTypes = List.copyOf(channelTypes);
    }

    public ClientClassification(Type brandType, Type channelType, Type modType) {
        this(
                brandType,
                channelType,
                modType,
                channelType == Type.UNSET || channelType == Type.UNKNOWN ? List.of() : List.of(channelType));
    }

    public static Type brandType(String brand) {
        if (brand == null) return Type.UNKNOWN;
        String value = brand.toLowerCase(Locale.ROOT).replace(" (velocity)", "");
        if (value.startsWith("lunarclient")) return Type.LUNAR;
        if (value.startsWith("badlion")) return Type.BADLION;
        if (value.startsWith("feather")) return Type.FEATHER;
        if (value.startsWith("labymod")) return Type.LABYMOD;
        return switch (value) {
            case "vanilla" -> Type.VANILLA;
            case "fabric" -> Type.FABRIC;
            case "forge", "fml,forge" -> Type.FORGE;
            case "neoforge" -> Type.NEOFORGE;
            case "quilt" -> Type.QUILT;
            default -> Type.UNKNOWN;
        };
    }

    public static Type channelType(Collection<String> channels) {
        Type result = Type.UNSET;
        for (String channel : channels) {
            Type type = channelType(channel);
            if (type == Type.UNSET) continue;
            if (result != Type.UNSET && result != type) return Type.UNKNOWN;
            result = type;
        }
        return result;
    }

    public static Type channelType(String channel) {
        if (channel.equals("fabric:registry/sync") || channel.equals("fabric:registry/sync/direct")) return Type.FABRIC;
        if (channel.startsWith("neoforge:")) return Type.NEOFORGE;
        if (channel.startsWith("fml:")
                || channel.startsWith("forge:")
                || channel.equals("FML|HS")
                || channel.equals("FML")
                || channel.equals("FORGE")) return Type.FORGE;
        if (channel.equals("lunar:apollo") || channel.startsWith("lunarclient:")) return Type.LUNAR;
        if (channel.equals("badlion:mods") || channel.equals("badlion:timers")) return Type.BADLION;
        if (channel.equals("labymod3:main") || channel.equals("labymod:main")) return Type.LABYMOD;
        return Type.UNSET;
    }

    /** The client family a channel's name shows, for {@code channelTypes}; any Fabric API channel is Fabric. */
    public static Type channelFamily(String channel) {
        if (channel.startsWith("fabric:") || channel.startsWith("fabric-")) return Type.FABRIC;
        return channelType(channel);
    }

    public boolean contradictsVanilla() {
        return brandType == Type.VANILLA && channelType == Type.FABRIC;
    }

    public String display() {
        return "brandtype=" + brandType
                + (brandType == channelType ? "" : " channeltype=" + channelType)
                + (modType == Type.UNSET ? "" : " modtype=" + modType);
    }

    /** The registered channels' families for staff, such as {@code Fabric, Lunar}; {@code None} without any. */
    public String channelLabel() {
        return channelTypes.isEmpty()
                ? Type.UNSET.label()
                : channelTypes.stream().map(Type::label).collect(Collectors.joining(", "));
    }

    /**
     * The classification for staff: the brand's family, then the channels' families when they are not just that one,
     * then the companion's when it differs, such as {@code Lunar (channels: Fabric, Lunar)} or {@code Fabric}.
     */
    public String summary() {
        StringBuilder details = new StringBuilder();
        if (!channelTypes.isEmpty() && !channelTypes.equals(List.of(brandType))) {
            details.append("channels: ").append(channelLabel());
        }
        if (modType != Type.UNSET && modType != brandType) {
            if (!details.isEmpty()) details.append("; ");
            details.append("mod: ").append(modType.label());
        }
        return details.isEmpty() ? brandType.label() : brandType.label() + " (" + details + ")";
    }
}
