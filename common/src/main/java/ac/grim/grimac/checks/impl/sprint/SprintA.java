package ac.grim.grimac.checks.impl.sprint;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;

@CheckData(name = "SprintA", stableKey = "grim.sprint.hunger", description = "Sprinting with too low hunger", setback = 0)
public class SprintA extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of("hunger={uint}");

    public SprintA(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {

        if (predictionComplete.isTeleport() || predictionComplete.isExempt()) return;

        // Players can sprint if they're able to fly
        // Players can also sprint if they are on a camel, regardless of their hunger level
        PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        if (player.canFly || (riding != null && riding.type == EntityTypesCompat.CAMEL)) return;

        if (player.food <= 6.0F) {
            if (player.isSprinting) {
                flagWithSetback(V.write(verbose()).uint(player.food));
            } else {
                reward();
            }
        }
    }
}
