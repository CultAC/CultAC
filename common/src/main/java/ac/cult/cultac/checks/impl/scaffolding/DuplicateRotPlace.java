package ac.cult.cultac.checks.impl.scaffolding;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.checks.type.RotationListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "DuplicateRotPlace",
        stableKey = "cult.scaffolding.duplicate_rot_place",
        description = "Repeated the same rotation delta while placing blocks",
        experimental = true)
public class DuplicateRotPlace extends BlockPlaceCheck implements RotationListener {
    private static final Verbose V = Verbose.of("x={f64} xdots={f64} repeats={uint} y={f64}");

    private final RepeatedTurns turns = new RepeatedTurns();
    private float deltaX, deltaY;
    private double deltaDotsX;
    private boolean rotated = false;

    public DuplicateRotPlace(CultPlayer player) {
        super(player);
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        deltaX = rotationUpdate.getDeltaXRotABS();
        deltaY = rotationUpdate.getDeltaYRotABS();
        deltaDotsX = rotationUpdate.getProcessor().deltaDotsX;
        rotated = true;
    }

    @Override
    public void onPostFlyingBlockPlace(BlockPlace place) {
        if (rotated && !ridesBoat()) {
            if (turns.place(deltaX, player.totalFlyingPacketsSent)) {
                flag(V.write(verbose())
                        .f64(deltaX)
                        .f64(deltaDotsX)
                        .uint(turns.repeats())
                        .f64(deltaY));
            } else {
                reward();
            }
            rotated = false;
        }
    }

    // AbstractBoat#positionRider adds the boat's turn to its rider's, which is not a whole number of mouse counts.
    private boolean ridesBoat() {
        PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        return riding != null && riding.isBoat();
    }

    /**
     * Tracks the yaw turn before each rotated block place and counts how many places in a row repeated the turn of
     * the place before them.
     */
    static final class RepeatedTurns {
        static final float MIN_TURN = 2;
        static final float SAME_TURN = 0.0001F;
        // Vanilla turns by whole mouse counts, so two places with an equal turn are common; a run of them is not.
        static final int MIN_REPEATS = 3;
        static final int MAX_PLACE_GAP_TICKS = 20;

        private float lastTurn;
        private int lastTick;
        private int repeats;

        /** Records a rotated place at client tick {@code tick}; true when it extends a run of at least MIN_REPEATS. */
        boolean place(float turn, int tick) {
            boolean repeat =
                    turn > MIN_TURN && Math.abs(turn - lastTurn) < SAME_TURN && tick - lastTick <= MAX_PLACE_GAP_TICKS;
            repeats = repeat ? repeats + 1 : 0;
            lastTurn = turn;
            lastTick = tick;
            return repeats >= MIN_REPEATS;
        }

        int repeats() {
            return repeats;
        }
    }
}
