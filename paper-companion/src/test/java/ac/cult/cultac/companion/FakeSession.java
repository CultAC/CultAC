package ac.cult.cultac.companion;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.companion.bridge.CompanionSession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/** Records what the companion sends and runs its tasks inline. */
public final class FakeSession implements CompanionSession {
    public final List<BridgeMessage> sent = new ArrayList<>();
    public final List<Runnable> executed = new ArrayList<>();
    public final UUID playerId;
    public final Player player;
    public boolean ready = true;
    public Consumer<BridgeMessage> onSend = message -> {};

    public FakeSession(UUID playerId) {
        this(
                playerId,
                Stubs.stub(
                        Player.class,
                        (proxy, method, args) -> method.getName().equals("getUniqueId") ? playerId : null));
    }

    public FakeSession(UUID playerId, Player player) {
        this.playerId = playerId;
        this.player = player;
    }

    @Override
    public UUID playerId() {
        return playerId;
    }

    @Override
    public Player player() {
        return player;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void send(BridgeMessage message) {
        sent.add(message);
        onSend.accept(message);
    }

    @Override
    public void execute(Runnable task) {
        executed.add(task);
        task.run();
    }

    @Override
    public boolean runInPacketOrder(Runnable task) {
        return false;
    }
}
