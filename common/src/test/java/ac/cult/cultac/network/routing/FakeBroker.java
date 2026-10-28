package ac.cult.cultac.network.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-memory pub/sub broker delivering each publish synchronously, recording every message it carried. */
final class FakeBroker {
    record Published(String channel, String text) {}

    private final Map<String, List<FakeTransport>> subscribers = new ConcurrentHashMap<>();
    final List<Published> published = new CopyOnWriteArrayList<>();

    FakeTransport transport() {
        return new FakeTransport();
    }

    List<Published> on(String channel) {
        List<Published> matching = new ArrayList<>();
        for (Published message : published) if (message.channel().equals(channel)) matching.add(message);
        return matching;
    }

    final class FakeTransport implements MessageTransport {
        private volatile Receiver receiver;
        private final List<String> channels = new ArrayList<>();
        boolean closed;

        @Override
        public String name() {
            return "Fake";
        }

        @Override
        public void start(List<String> subscribed, Receiver receiver) {
            this.receiver = receiver;
            channels.addAll(subscribed);
            for (String channel : subscribed)
                subscribers
                        .computeIfAbsent(channel, ignored -> new CopyOnWriteArrayList<>())
                        .add(this);
        }

        @Override
        public void publish(String channel, String text) {
            if (closed) return;
            published.add(new Published(channel, text));
            for (FakeTransport subscriber : subscribers.getOrDefault(channel, List.of()))
                subscriber.receiver.receive(channel, text);
        }

        @Override
        public void close() {
            closed = true;
            for (String channel : channels)
                subscribers.getOrDefault(channel, List.of()).remove(this);
        }
    }
}
