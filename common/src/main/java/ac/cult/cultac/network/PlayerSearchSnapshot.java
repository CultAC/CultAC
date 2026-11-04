package ac.cult.cultac.network;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.aim.processor.AimProcessor;
import ac.cult.cultac.player.CultPlayer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cheap, already observed profile data; sensitivity uses the profile's 0..200 percent scale. */
public final class PlayerSearchSnapshot {
    private PlayerSearchSnapshot() {}

    public static Map<String, List<String>> capture(UUID uuid) {
        CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(uuid);
        if (player == null) return Map.of();
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("platform", List.of(player.isBedrockMovement() ? "bedrock" : "java"));
        values.put("version", List.of(player.getVersionName()));
        values.put("ping", List.of(Long.toString(player.getTransactionPing())));
        values.put("fastmath", List.of(Boolean.toString(!player.isVanillaMath())));
        if (!player.isBedrockMovement()) {
            AimProcessor aim = player.checkManager.getCheck(AimProcessor.class);
            if (aim != null) {
                var sensitivity = aim.sensitivitySnapshot();
                if (Double.isFinite(sensitivity.horizontal()))
                    values.put("sens", List.of(Double.toString(sensitivity.horizontal() * 200)));
                if (Double.isFinite(sensitivity.vertical()))
                    values.put("vsens", List.of(Double.toString(sensitivity.vertical() * 200)));
            }
        }
        List<String> channels = List.copyOf(player.pluginChannelManager.channels());
        values.put("channel", channels);
        List<String> mods = new ArrayList<>();
        var reported = player.clientMods;
        if (reported != null) {
            for (var mod : reported.mods())
                mods.add(mod.id() + " " + mod.name() + " " + mod.version() + " " + mod.type());
        }
        for (String channel : channels) {
            if (!channel.startsWith("minecraft:") && !channel.equals("REGISTER") && !channel.equals("UNREGISTER"))
                mods.add(channel);
        }
        values.put("mod", List.copyOf(mods));
        return Map.copyOf(values);
    }
}
