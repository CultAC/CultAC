package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage.ServerStats;
import org.bukkit.Bukkit;

/** Main-thread task, run once a second: publishes server stats. */
final class BridgeTicker implements Runnable {
    private final BridgeService service;

    BridgeTicker(BridgeService service) {
        this.service = service;
    }

    @Override
    public void run() {
        BridgeSession session = service.anySession();
        if (session != null) session.send(new ServerStats((float) Bukkit.getTPS()[0]));
    }
}
