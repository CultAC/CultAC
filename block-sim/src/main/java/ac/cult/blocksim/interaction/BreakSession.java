package ac.cult.blocksim.interaction;

import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.data.StateFacts;
import java.util.ArrayList;
import java.util.List;

/** MultiPlayerGameMode's tick-resolved breaking state. Packets describe the
 * client's actions; prediction does not send packets or infer elapsed ticks. */
public final class BreakSession {
    public enum Action { START, ABORT, STOP, CHANGE_DIRECTION }
    public record Packet(Action action, BlockPos pos, Direction direction) { }
    public record State(boolean destroying, BlockPos pos, Direction direction, SimItemStack item,
                        float progress, float ticks, int delay) {
        public static State initial(SimItemStack empty) {
            return new State(false, new BlockPos(-1, -1, -1), null, empty, 0, 0, 0);
        }
        public int stage() { return progress > 0.0F ? (int)(progress * 10.0F) : -1; }
    }
    public record Result(boolean accepted, State state, List<Packet> packets) {
        public Result { packets = List.copyOf(packets); }
    }
    private final BreakDriver driver;
    private final MiningSpeed mining;
    private State state;

    public BreakSession(BreakDriver driver, MiningSpeed mining, State state) {
        this.driver = java.util.Objects.requireNonNull(driver);
        this.mining = java.util.Objects.requireNonNull(mining);
        this.state = java.util.Objects.requireNonNull(state);
    }
    private boolean sameTarget(SimPlayer player, BlockPos pos) {
        return state.pos().equals(pos) && player.hand(Hand.MAIN_HAND).sameItemSameComponents(state.item());
    }
    private Result result(boolean accepted, List<Packet> packets) { return new Result(accepted, state, packets); }

    public Result start(SimLevel level, SimPlayer player, MiningSpeed.State speed, BlockPos pos, Direction face) {
        var packets = new ArrayList<Packet>();
        if (driver.restricted(level, player, pos) || !level.isWithinBorder(pos)) return result(false, packets);
        if (player.state().infiniteMaterials()) {
            driver.destroyBlock(level, player, pos);
            packets.add(new Packet(Action.START, pos, face));
            state = new State(state.destroying(), state.pos(), state.direction(), state.item(), state.progress(), state.ticks(), 5);
        } else if (!state.destroying() || !sameTarget(player, pos)) {
            if (state.destroying()) packets.add(new Packet(Action.ABORT, state.pos(), face));
            int block = level.stateAt(pos);
            boolean notAir = !level.registry().facts(block).has(StateFacts.AIR);
            if (notAir && state.progress() == 0.0F) level.behavior(block).attack(level, block, pos, player);
            if (notAir && mining.progress(player.hand(Hand.MAIN_HAND), block, speed) >= 1.0F) {
                driver.destroyBlock(level, player, pos);
            } else {
                state = new State(true, pos, face, player.hand(Hand.MAIN_HAND), 0.0F, 0.0F, state.delay());
            }
            packets.add(new Packet(Action.START, pos, face));
        }
        return result(true, packets);
    }

    public Result continueBreaking(SimLevel level, SimPlayer player, MiningSpeed.State speed, BlockPos pos, Direction face) {
        if (state.delay() > 0) {
            state = new State(state.destroying(), state.pos(), state.direction(), state.item(), state.progress(), state.ticks(), state.delay() - 1);
            return result(true, List.of());
        }
        if (player.state().infiniteMaterials() && level.isWithinBorder(pos)) {
            driver.destroyBlock(level, player, pos);
            state = new State(state.destroying(), state.pos(), state.direction(), state.item(), state.progress(), state.ticks(), 5);
            return result(true, List.of(new Packet(Action.START, pos, face)));
        }
        if (!sameTarget(player, pos)) return start(level, player, speed, pos, face);
        int block = level.stateAt(pos);
        if (level.registry().facts(block).has(StateFacts.AIR)) {
            state = new State(false, state.pos(), state.direction(), state.item(), state.progress(), state.ticks(), state.delay());
            return result(false, List.of());
        }
        float progress = state.progress() + mining.progress(player.hand(Hand.MAIN_HAND), block, speed);
        if (progress >= 1.0F) {
            driver.destroyBlock(level, player, pos);
            state = new State(false, state.pos(), null, state.item(), 0.0F, 0.0F, 5);
            return result(true, List.of(new Packet(Action.STOP, pos, face)));
        }
        var packets = state.direction() != face ? List.of(new Packet(Action.CHANGE_DIRECTION, pos, face)) : List.<Packet>of();
        state = new State(state.destroying(), state.pos(), face, state.item(), progress, state.ticks() + 1.0F, state.delay());
        return result(true, packets);
    }

    public Result abort() {
        if (!state.destroying()) return result(true, List.of());
        state = new State(false, state.pos(), state.direction(), state.item(), 0.0F, state.ticks(), state.delay());
        return result(true, List.of(new Packet(Action.ABORT, state.pos(), Direction.DOWN)));
    }
}
