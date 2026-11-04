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
            if (aim != null && aim.modeX > 0)
                values.put("sens", List.of(Double.toString(player.getHorizontalSensitivity() * 200)));
            if (aim != null && aim.modeY > 0)
                values.put("vsens", List.of(Double.toString(player.getVerticalSensitivity() * 200)));
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
