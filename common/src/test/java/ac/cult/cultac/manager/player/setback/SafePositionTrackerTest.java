package ac.cult.cultac.manager.player.setback;

import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A Java movement staged before a placement or answer replaced the safe position was predicted from the state that
 * replacement overrides. Committing it after its checks ran would roll the setback anchor back past the authoritative
 * position.
 */
class SafePositionTrackerTest {
    private static final Vec3 A = new Vec3(0.5, 64, 0.5);
    private static final Vec3 B = new Vec3(0.5, 64, 0.8);
    private static final Vec3 C = new Vec3(20.5, 64, 0.5);
    private static final SetbackEntity VEHICLE = SetbackEntity.of(42);

    @Test
    void aStagedVehicleMovementDoesNotRollBackAProvenServerPlacement() {
        OfflinePlayers.withSafePlayer(A, player -> {
            player.lastTransactionReceived.set(5);
            SafePositionTracker tracker = new SafePositionTracker(player, new Unblocked(VEHICLE));
            tracker.offer(at(A, VEHICLE, SafeProof.placement(5)), SafePositionTracker.Write.VEHICLE_PLACEMENT);

            tracker.hold();
            tracker.stage(at(B, VEHICLE, SafeProof.movement(5)));
            // CompensatedVehicleState proves the server's position of a vehicle the client has already received.
            SetbackPosWithVector placed = at(C, VEHICLE, SafeProof.placement(5));
            tracker.offer(placed, SafePositionTracker.Write.VEHICLE_PLACEMENT);
            tracker.release();

            assertSame(placed, tracker.current(), "the movement staged before the placement replaced it");
        });
    }

    @Test
    void aStagedMovementDoesNotRollBackAnAnswer() {
        OfflinePlayers.withSafePlayer(A, player -> {
            SafePositionTracker tracker = new SafePositionTracker(player, new Unblocked(SetbackEntity.PLAYER));
            tracker.offer(at(A, SetbackEntity.PLAYER, SafeProof.answer(1)), SafePositionTracker.Write.ANSWER);

            tracker.stage(at(B, SetbackEntity.PLAYER, SafeProof.movement(1)));
            SetbackPosWithVector answered = at(C, SetbackEntity.PLAYER, SafeProof.answer(3));
            tracker.offer(answered, SafePositionTracker.Write.ANSWER);
            tracker.commit();

            assertSame(answered, tracker.current(), "the movement staged before the answer replaced it");
        });
    }

    @Test
    void aStagedMovementCommitsOverTheSafePositionItWasStagedOver() {
        OfflinePlayers.withSafePlayer(A, player -> {
            SafePositionTracker tracker = new SafePositionTracker(player, new Unblocked(SetbackEntity.PLAYER));
            tracker.offer(at(A, SetbackEntity.PLAYER, SafeProof.answer(1)), SafePositionTracker.Write.ANSWER);

            SetbackPosWithVector moved = at(B, SetbackEntity.PLAYER, SafeProof.movement(1));
            tracker.hold();
            tracker.stage(moved);
            tracker.release();

            assertSame(moved, tracker.current());
        });
    }

    private static SetbackPosWithVector at(Vec3 pos, SetbackEntity owner, SafeProof proof) {
        return new SetbackPosWithVector(pos, Vec3.ZERO).provenBy(owner, proof);
    }

    /** No correction is in flight: only the staging order can reject the movement. */
    private record Unblocked(SetbackEntity setbackEntity) implements SafePositionTracker.Corrections {
        @Override
        public boolean safePositionUnblocked() {
            return true;
        }

        @Override
        public boolean isPendingSetback() {
            return false;
        }
    }
}
