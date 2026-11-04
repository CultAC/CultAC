package ac.cult.cultac.player;

import java.util.Collection;
import java.util.Locale;

/** Client identity reported by the brand, registered channels and companion. */
public record ClientClassification(Type brandType, Type channelType, Type modType) {
    public enum Type {
        VANILLA,
        FABRIC,
        FORGE,
        NEOFORGE,
        QUILT,
        LUNAR,
        BADLION,
        FEATHER,
        LABYMOD,
        BEDROCK,
        UNKNOWN,
        UNSET
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

    private static Type channelType(String channel) {
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

    public boolean contradictsVanilla() {
        return brandType == Type.VANILLA && channelType == Type.FABRIC;
    }

    public String display() {
        return "brandtype=" + brandType
                + (brandType == channelType ? "" : " channeltype=" + channelType)
                + (modType == Type.UNSET ? "" : " modtype=" + modType);
    }
}
