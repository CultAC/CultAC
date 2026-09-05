package ac.cult.cultac.manager.tick.impl;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.tick.Tickable;
import ac.cult.cultac.network.netty.channel.ChannelHelper;
import ac.cult.cultac.player.CultPlayer;

public class ClientVersionSetter implements Tickable {
    @Override
    public void tick() {
        for (CultPlayer player : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            // channel was somehow closed without us getting a disconnect event
            if (!ChannelHelper.isOpen(player.user.getChannel())) {
                CultAPI.INSTANCE.getPlayerDataManager().onDisconnect(player.user);
                continue;
            }

            player.pollData();
        }
    }
}
