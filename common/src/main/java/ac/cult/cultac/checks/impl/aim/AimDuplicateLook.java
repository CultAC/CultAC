package ac.cult.cultac.checks.impl.aim;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.RotationListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import ac.cult.cultac.utils.data.HeadRotation;

@CheckData(
        name = "AimDuplicateLook",
        stableKey = "cult.aim.duplicate_look",
        description = "Sent a duplicate rotation update without changing look direction")
public class AimDuplicateLook extends Check implements RotationListener {
    private boolean exempt;
    // The last update's rotation: LocalPlayer#yRotLast and xRotLast, which a server look leaves as they were.
    private HeadRotation lastReported;

    public AimDuplicateLook(CultPlayer player) {
        super(player);
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        HeadRotation reported = lastReported;
        lastReported = rotationUpdate.getTo();
        if (rotationUpdate.isTeleport() || player.compensatedEntities.getSelf().getRiding() != null) {
            exempt = true;
            return;
        }

        if (exempt) { // Exempt for a tick on teleport
            exempt = false;
            return;
        }

        // LocalPlayer#sendPosition sends only a rotation that differs from the last one it sent.
        if (rotationUpdate.getFrom().equals(rotationUpdate.getTo()) && repeats(reported, rotationUpdate.getTo())) {
            flag();
        }
    }

    private static boolean repeats(HeadRotation sent, HeadRotation to) {
        return sent == null || (sent.yaw() == to.yaw() && sent.pitch() == to.pitch());
    }
}
