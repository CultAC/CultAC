package ac.cult.cultac.companion.api;

final class Unsupported {
    private Unsupported() {}

    static UnsupportedOperationException method(String method) {
        return new UnsupportedOperationException(
                method + " is not available through the CultAC proxy companion; use a proxy plugin");
    }
}
