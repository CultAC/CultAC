package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * An estimated server tick end queues one task per player for every tick; Paper tick ends keep a task each. Both send
 * what the base form, a fresh task running the same steps, sends: the same transactions, ping kinds and ticks, in
 * the same order, with tasks run late, out of step and while a flush ping proves the tick.
 */
class EstimatedTickEndTaskTest {
    @Test
    void estimatedAndPaperTickEndsSendWhatAFreshTaskSends() throws Exception {
        CultPlayer reused = player();
        CultPlayer base = player();
        try {
            Random random = new Random(9);
            int estimated = 0;
            for (int step = 0; step < 400; step++) {
                int serverTick = random.nextInt(3) == 0 ? 1 + random.nextInt(1000) : 0;
                if (serverTick == 0) {
                    reused.onServerTickEnd();
                    baseEndServerTick(base, CultPlayer.PING_LEADING, 0);
                    estimated++;
                } else {
                    reused.onEndOfTickEvent(serverTick);
                    baseEndServerTick(base, CultPlayer.PING_END_OF_TICK, serverTick);
                }
                if (random.nextInt(3) != 0) {
                    List<Runnable> tasks = writeTasks(reused);
                    List<Runnable> baseTasks = writeTasks(base);
                    assertEquals(baseTasks.size(), tasks.size(), "queued tasks at " + step);
                    for (int i = 0; i < tasks.size(); i++) {
                        tasks.get(i).run();
                        baseTasks.get(i).run();
                    }
                    assertEquals(sent(base), sent(reused), "sent at " + step);
                }
            }
            assertTrue(estimated > 100);
        } finally {
            close(reused);
            close(base);
        }
    }

    @Test
    void everyEstimatedTickEndQueuesTheSameTaskAndEveryPaperTickEndAFreshOne() {
        CultPlayer player = player();
        try {
            player.onServerTickEnd();
            player.onServerTickEnd();
            player.onEndOfTickEvent(5);
            player.onEndOfTickEvent(5);
            List<Runnable> tasks = writeTasks(player);
            assertEquals(4, tasks.size());
            assertSame(tasks.get(0), tasks.get(1));
            assertNotSame(tasks.get(2), tasks.get(3));
            for (Runnable task : tasks) task.run();
            assertEquals(4, player.lastTransactionSent.get());
        } finally {
            close(player);
        }
    }

    /** The base form of the task an end of server tick queues. */
    private static void baseEndServerTick(CultPlayer player, int kind, int serverTick) throws Exception {
        Method send = CultPlayer.class.getDeclaredMethod("sendTransaction", boolean.class, int.class, int.class);
        send.setAccessible(true);
        player.user.executeAfterWrites(() -> {
            var observer = player.observer;
            if (kind == CultPlayer.PING_END_OF_TICK && observer != null) observer.serverTickEnded(serverTick);
            if (player.user.getCultConnection().provingPing() <= 0) {
                try {
                    send.invoke(player, false, kind, serverTick);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }
        });
    }

    private static List<Runnable> writeTasks(CultPlayer player) {
        var channel = (EmbeddedChannel) player.user.getChannel();
        var tasks = new ArrayList<Runnable>();
        var others = new ArrayList<Object>();
        Object message;
        while ((message = channel.readOutbound()) != null) {
            if (message instanceof CultConnection.WriteTask task) tasks.add(task.action());
            else others.add(message);
        }
        for (Object other : others) channel.writeOutbound(other);
        return tasks;
    }

    /** The transactions sent, each with its ping's kind and tick. */
    private static List<String> sent(CultPlayer player) {
        var channel = (EmbeddedChannel) player.user.getChannel();
        var sent = new ArrayList<String>();
        Object message;
        while ((message = channel.readOutbound()) != null) {
            if (message instanceof CultWrite write && write.packet() instanceof ClientboundPing ping)
                sent.add(player.pingProvenance(ping.id()) + "");
            else sent.add(message.getClass().getSimpleName());
        }
        sent.add("last " + player.lastTransactionSent.get());
        return sent;
    }

    private static CultPlayer player() {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(
                new User.Profile(UUID.randomUUID(), ".Tick_End"),
                new EmbeddedChannel(),
                OfflineCultTestBootstrap.platformConnection());
        CultPlayer player = new CultPlayer(user);
        user.bind(Mockito.mock(PlatformPlayer.class));
        player.recordProvenance(true);
        return player;
    }

    private static void close(CultPlayer player) {
        Object channel = player.user.getChannel();
        player.onRemove();
        if (channel instanceof EmbeddedChannel embedded) {
            embedded.runPendingTasks();
            embedded.finishAndReleaseAll();
        }
    }
}
