package ac.grim.grimac.checks.type;

import ac.grim.grimac.network.event.PacketReceiveEvent;

public interface ClientTickEndListener {
    void onPlayerTickEnd(PacketReceiveEvent event);
}
