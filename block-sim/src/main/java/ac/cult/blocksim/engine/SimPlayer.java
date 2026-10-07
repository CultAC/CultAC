package ac.cult.blocksim.engine;

import ac.cult.blocksim.interaction.Hand;

/** Proven tick state and action-local hands. It never reads a server player. */
public final class SimPlayer {
    public enum GameMode { SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR }
    public record State(Vec3 position, float yaw, float pitch, boolean secondaryUseActive,
                        boolean infiniteMaterials, boolean mayBuild, boolean gameMaster, GameMode gameMode,
                        boolean invulnerable, int foodLevel, float saturationLevel) {
        /** Standard normally constructed player state used by the source fixtures. */
        public State(Vec3 position, float yaw, float pitch, boolean secondaryUseActive, boolean infiniteMaterials,
                     boolean mayBuild, boolean gameMaster, GameMode gameMode) {
            this(position, yaw, pitch, secondaryUseActive, infiniteMaterials, mayBuild, gameMaster, gameMode,
                gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR, 20, 5.0F);
        }
    }
    private final State state;
    private final SimInventory inventory;
    public record Sight(Vec3 eyePosition, double blockInteractionRange) { }
    private final Sight sight;
    private final EntityCollisionContext collision;
    public record Movement(boolean fallFlying) { }
    private final Movement movement;
    /** Folded enchantment effects and the actor's wet state at this action's tick boundary. */
    public record TridentUse(boolean inWaterOrRain, float mainHandStrength, float offHandStrength) {
        public float strength(Hand hand) { return hand == Hand.MAIN_HAND ? mainHandStrength : offHandStrength; }
    }
    private final TridentUse tridentUse;
    private int foodLevel;
    private float saturationLevel;
    public record ActiveUse(Hand hand, SimItemStack stack, int remainingTicks) { }
    private ActiveUse activeUse;

    public SimPlayer(State state, SimItemStack mainHand, SimItemStack offHand) {
        this(state, mainHand, offHand, null);
    }
    public SimPlayer(State state, SimItemStack mainHand, SimItemStack offHand, ActiveUse activeUse) {
        this(state, SimInventory.fixture(mainHand, offHand, state.infiniteMaterials()), activeUse);
    }
    public SimPlayer(State state, SimInventory inventory, ActiveUse activeUse) {
        this(state, inventory, activeUse, null);
    }
    /** Production supplies the pose-resolved eye position and compensated range attribute. */
    public SimPlayer(State state, SimInventory inventory, ActiveUse activeUse, Sight sight) {
        this(state, inventory, activeUse, sight, null);
    }
    public SimPlayer(State state, SimInventory inventory, ActiveUse activeUse, Sight sight, EntityCollisionContext collision) {
        this(state, inventory, activeUse, sight, collision, null);
    }
    public SimPlayer(State state, SimInventory inventory, ActiveUse activeUse, Sight sight, EntityCollisionContext collision, Movement movement) {
        this(state, inventory, activeUse, sight, collision, movement, null);
    }
    public SimPlayer(State state, SimInventory inventory, ActiveUse activeUse, Sight sight, EntityCollisionContext collision, Movement movement,
                     TridentUse tridentUse) {
        this.state = java.util.Objects.requireNonNull(state);
        this.inventory = java.util.Objects.requireNonNull(inventory);
        if (state.infiniteMaterials() != inventory.infiniteMaterials()) throw new IllegalArgumentException("Inconsistent ability snapshot");
        foodLevel = state.foodLevel(); saturationLevel = state.saturationLevel();
        this.activeUse = activeUse;
        this.sight = sight;
        this.collision = collision;
        this.movement = movement;
        this.tridentUse = tridentUse;
    }
    public State state() { return state; }
    public Sight sight() { return java.util.Objects.requireNonNull(sight, "Item raycast requires compensated eye position and interaction range"); }
    public EntityCollisionContext collision() { return java.util.Objects.requireNonNull(collision, "Collision raycast requires compensated entity facts"); }
    public Movement movement() { return java.util.Objects.requireNonNull(movement, "Firework use requires compensated gliding state"); }
    public TridentUse tridentUse() { return java.util.Objects.requireNonNull(tridentUse, "Trident use requires folded effects and compensated wet state"); }
    /** Copy one action using a shared stack copier, including aliases in active use and breaking. */
    public SimPlayer copy(java.util.function.UnaryOperator<SimItemStack> copy) {
        var slots = inventory.slots().stream().map(copy).toList();
        var copiedInventory = new SimInventory(slots, inventory.selected(), state.infiniteMaterials(), copy.apply(inventory.empty()));
        var use = activeUse == null ? null : new ActiveUse(activeUse.hand(), copy.apply(activeUse.stack()), activeUse.remainingTicks());
        var result = new SimPlayer(state, copiedInventory, use, sight, collision, movement, tridentUse);
        result.foodLevel = foodLevel; result.saturationLevel = saturationLevel;
        return result;
    }
    public int foodLevel() { return foodLevel; }
    public float saturationLevel() { return saturationLevel; }
    public ActiveUse activeUse() { return activeUse; }
    public void startUsingItem(Hand hand, int duration) {
        var stack = hand(hand);
        if (!stack.isEmpty() && activeUse == null) activeUse = new ActiveUse(hand, stack, duration);
    }
    public boolean canEat(boolean canAlwaysEat) { return state.invulnerable() || canAlwaysEat || foodLevel < 20; }
    public void eat(int food, float saturationModifier) {
        addFood(food, food * saturationModifier * 2.0F);
    }
    public void addFood(int food, float saturationPoints) {
        foodLevel = Math.min(Math.max(food + foodLevel, 0), 20);
        float saturation = saturationPoints + saturationLevel;
        saturationLevel = saturation < 0.0F ? 0.0F : Math.min(saturation, foodLevel);
    }
    public SimInventory inventory() { return inventory; }
    public SimItemStack hand(Hand hand) { return inventory.get(hand == Hand.MAIN_HAND ? inventory.selected() : 40); }
    public void hand(Hand hand, SimItemStack stack) { inventory.set(hand == Hand.MAIN_HAND ? inventory.selected() : 40, stack); }
}
