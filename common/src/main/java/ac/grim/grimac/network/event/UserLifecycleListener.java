package ac.grim.grimac.network.event;

public class UserLifecycleListener {
    private final PacketListenerPriority priority;

    public UserLifecycleListener() {
        this(PacketListenerPriority.NORMAL);
    }

    public UserLifecycleListener(PacketListenerPriority priority) {
        this.priority = priority;
    }

    public PacketListenerPriority getPriority() {
        return priority;
    }

    public void onUserConnect(UserConnectEvent event) {
    }

    public void onUserLogin(UserLoginEvent event) {
    }

    public void onUserDisconnect(UserDisconnectEvent event) {
    }
}
