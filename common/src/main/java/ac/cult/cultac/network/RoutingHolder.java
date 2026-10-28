package ac.cult.cultac.network;

final class RoutingHolder {
    static volatile NetworkRouting current = new LocalRouting();

    private RoutingHolder() {}
}
