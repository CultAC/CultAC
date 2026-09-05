package ac.cult.cultac.bedrock.prediction.model;

public record EquipmentState(
    int depthStriderLevel,
    int soulSpeedLevel,
    int swiftSneakLevel,
    int riptideLevel,
    boolean leatherBoots,
    boolean elytraEquipped
) {
    public static final EquipmentState NONE = new EquipmentState(0, 0, 0, 0, false, false);

    public EquipmentState {
        if (depthStriderLevel < 0 || soulSpeedLevel < 0 || swiftSneakLevel < 0 || riptideLevel < 0) {
            throw new IllegalArgumentException("equipment levels must be non-negative");
        }
    }

    public EquipmentState(
        int depthStriderLevel,
        int soulSpeedLevel,
        int swiftSneakLevel,
        boolean leatherBoots,
        boolean elytraEquipped
    ) {
        this(depthStriderLevel, soulSpeedLevel, swiftSneakLevel, 0, leatherBoots, elytraEquipped);
    }
}
