package ac.cult.cultac.checks.type;

import ac.cult.cultac.network.event.PacketReceiveEvent;

public interface ClientTickEndListener {
    void onPlayerTickEnd(PacketReceiveEvent event);
}
