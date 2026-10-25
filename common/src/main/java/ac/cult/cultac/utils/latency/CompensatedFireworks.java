package ac.cult.cultac.utils.latency;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

public class CompensatedFireworks extends CultProcessor implements PostPredictionListener {
    private static final int NONE = Integer.MAX_VALUE;

    /** The client's view of one rocket, from the first target the server names for it. */
    static final class Rocket {
        /** The transaction that proves the client holds the rocket. */
        final int presentAt;
        /** FireworkRocketEntity#tick may have resolved the player as the entity it boosts. */
        boolean mayHoldPlayer;

        /** FireworkRocketEntity#tick may have resolved another entity, which it keeps. */
        boolean mayHoldOther;

        /** FireworkRocketEntity#tick has resolved the player. */
        boolean holdsPlayer;

        /** The rocket may have flown off on its own or been discarded, so it may no longer tick. */
        boolean mayBeGone;

        /**
         * The rocket may have missed a tick in a chunk the client has not loaded, so it may still sit where it
         * stopped until the client applies a position update for it.
         */
        boolean mayBeLeftBehind;

        /** The last transaction the client had answered at a tick the rocket may have missed. */
        int leftBehindAt;

        /** Whether the newest DATA_ATTACHED_TO_TARGET names an entity. */
        boolean targeted;

        /** The transaction that proves the client applied the newest target. */
        int targetAt;

        /** Whether a target the client may hold before the newest names an entity. */
        boolean mayBeTargetedBefore;

        /** Whether every target the client may hold before the newest names an entity. */
        boolean surelyTargetedBefore;

        /** The transaction that proves the client applied the rocket's removal. */
        int removedAt = NONE;

        Rocket(int presentAt) {
            this.presentAt = presentAt;
        }

        boolean mayBeTargeted(int received) {
            return targeted || received < targetAt && mayBeTargetedBefore;
        }

        boolean surelyTargeted(int received) {
            return targeted && (received >= targetAt || surelyTargetedBefore);
        }
    }

    // As this is sync to one player, this does not have to be concurrent
    /** Each rocket a target has named, by entity id. */
    final Int2ObjectMap<Rocket> rockets = new Int2ObjectOpenHashMap<>();

    int boostsNextTick;
    int certainBoostsNextTick;
    private final SimpleCollisionBox box = new SimpleCollisionBox();
    private final SimpleCollisionBox scratch = new SimpleCollisionBox();
    /** Where a rocket holding the player was placed at the last tick the rockets ran: the player's position. */
    private boolean placed;

    private double placedX, placedY, placedZ;
    private float placedYaw;

    /** Whether the player was fall flying at that tick, so the rocket sat at its hand. */
    private boolean placedAtHand;

    public CompensatedFireworks(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (predictionComplete.isTeleport()) {
            // SimulationProcessor#handleTeleport applies the teleport to the boosted velocities.
            boostsNextTick = 0;
            certainBoostsNextTick = 0;
            return;
        }

        int received = player.lastTransactionReceived.get();
        rockets.values().removeIf(rocket -> received >= rocket.removedAt);

        SimulationContext context = predictionComplete.getPredictionResult().getSimulationContext();
        boolean fallFlying = context != null && context.isGliding() && !context.stopsFallFlyingAfterTravel();
        // TickRateManager#isEntityFrozen keeps the rockets from ticking while the server's ticks are frozen.
        boolean ticks = !(player.packetStateData.serverTicksFrozen
                && player.packetStateData.serverFrozenTickStepsRemaining == 0);
        // FireworkRocketEntity#tick runs after this tick's travel and boosts the player only while it is fall flying.
        boolean boosts = !player.isBedrockMovement() && fallFlying && ticks;
        double belowWorld = player.compensatedWorld.getMinY() - 64;

        int possible = 0;
        int certain = 0;
        for (ObjectIterator<Int2ObjectMap.Entry<Rocket>> entries =
                        rockets.int2ObjectEntrySet().iterator();
                entries.hasNext(); ) {
            Int2ObjectMap.Entry<Rocket> entry = entries.next();
            Rocket rocket = entry.getValue();
            if (!rocket.mayHoldPlayer) continue;
            boolean located = locate(entry.getIntKey(), rocket, box);
            // ClientLevel#tickEntities ticks only the entities in loaded chunks.
            boolean inProvenColumns = located && provenColumns(box);
            if (ticks && !inProvenColumns) {
                rocket.mayBeLeftBehind = true;
                rocket.leftBehindAt = received;
            }
            boolean surelyTicks = ticks
                    && inProvenColumns
                    && !rocket.mayBeLeftBehind
                    && !rocket.mayBeGone
                    && rocket.removedAt == NONE
                    && received >= rocket.presentAt;
            boolean surelyTargeted = rocket.surelyTargeted(received);
            // An unresolved rocket resolves its target in its first tick that finds one.
            boolean holdsPlayer = rocket.holdsPlayer || !rocket.mayHoldOther && surelyTargeted;
            if (rocket.mayBeTargeted(received)) possible++;
            if (surelyTicks && surelyTargeted && holdsPlayer) {
                certain++;
                rocket.holdsPlayer = true;
            }
            // Entity#checkBelowWorld discards the rocket in Entity#baseTick; the rest of that tick still boosts.
            if (ticks && located && box.minY < belowWorld) {
                if (surelyTicks && box.maxY < belowWorld) entries.remove();
                else rocket.mayBeGone = true;
            }
        }
        boostsNextTick = boosts ? possible : 0;
        certainBoostsNextTick = boosts ? certain : 0;

        if (ticks) {
            placed = true;
            placedX = player.x;
            placedY = player.y;
            placedZ = player.z;
            placedYaw = player.xRot;
            placedAtHand = fallFlying;
        }
    }

    /**
     * Sets {@code box} to every position the client's rocket can have: where the server last placed it, and where
     * FireworkRocketEntity#tick placed it at the player. False when they are unknown.
     */
    private boolean locate(int entityID, Rocket rocket, SimpleCollisionBox box) {
        PacketEntity entity = player.compensatedEntities.getEntity(entityID);
        if (entity != null) {
            // The rocket's possible positions are the centres of its possible boxes.
            double halfWidth = BoundingBoxSize.getWidth(player, entity) / 2.0;
            entity.getPossibleCollisionBoxes(box, scratch).inflate(-halfWidth, 0, -halfWidth);
        }
        // Before the rockets' first tick, a rocket is where the server spawned it.
        if (!placed) return entity != null;
        if (entity == null) box.set(placedX, placedY, placedZ, placedX, placedY, placedZ);
        else include(box, placedX, placedY, placedZ);
        if (placedAtHand) {
            // Entity#getHandHoldingItemAngle, for either arm, in either trig table.
            for (int fastMath = 0; fastMath < 2; fastMath++) {
                for (int side = -1; side <= 1; side += 2) {
                    Vector3dm hand = ReachUtils.getLook(player, placedYaw + side * 80, 0);
                    include(box, placedX + hand.getX() * 0.5, placedY, placedZ + hand.getZ() * 0.5);
                }
                player.trigHandler.toggleShitMath();
            }
        }
        return true;
    }

    private static void include(SimpleCollisionBox box, double x, double y, double z) {
        box.unionX(x);
        box.unionY(y);
        box.unionZ(z);
    }

    private boolean provenColumns(SimpleCollisionBox box) {
        for (int chunkX = CultMath.floor(box.minX) >> 4; chunkX <= CultMath.floor(box.maxX) >> 4; chunkX++) {
            for (int chunkZ = CultMath.floor(box.minZ) >> 4; chunkZ <= CultMath.floor(box.maxZ) >> 4; chunkZ++) {
                if (!player.compensatedWorld.isColumnProven(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    /**
     * An entity data update setting a rocket's DATA_ATTACHED_TO_TARGET to {@code target}, which the client has
     * applied once it answers {@code provenAt}. FireworkRocketEntity#tick boosts while a target is set, and keeps
     * the entity it first resolves for one.
     */
    public void attachmentChanged(int entityID, OptionalInt target, int provenAt) {
        boolean toPlayer = target.isPresent() && target.getAsInt() == player.entityID;
        Rocket rocket = rockets.get(entityID);
        // The server adds a rocket again under the same id after its removal, as when it tracks it again.
        if (rocket == null || rocket.removedAt != NONE) {
            if (target.isEmpty()) return;
            rocket = new Rocket(provenAt);
            rockets.put(entityID, rocket);
        }
        int received = player.lastTransactionReceived.get();
        rocket.mayBeTargetedBefore = rocket.mayBeTargeted(received);
        rocket.surelyTargetedBefore = rocket.surelyTargeted(received);
        rocket.targeted = target.isPresent();
        rocket.targetAt = provenAt;
        if (target.isEmpty()) {
            // Without a target the rocket flies on its own.
            rocket.mayBeGone = true;
        } else if (!rocket.holdsPlayer) {
            if (toPlayer) rocket.mayHoldPlayer = true;
            else rocket.mayHoldOther = true;
        }
    }

    /**
     * The client has applied a position update from the server for {@code entityID}, which places it there. The
     * client applied it after every tick at which it had not yet answered {@code appliedAfter}.
     */
    public void positionApplied(int entityID, int appliedAfter) {
        Rocket rocket = rockets.get(entityID);
        if (rocket != null && rocket.leftBehindAt < appliedAfter) rocket.mayBeLeftBehind = false;
    }

    /**
     * A remove_entities for {@code entityIDs}, which the client has applied once it answers {@code provenAt}. It
     * runs once the client may have applied it.
     */
    public void removalSent(List<Integer> entityIDs, int provenAt) {
        for (int entityID : entityIDs) {
            Rocket rocket = rockets.get(entityID);
            if (rocket != null && rocket.removedAt == NONE) rocket.removedAt = provenAt;
        }
    }

    /**
     * A replacement client level holds none of the old level's rockets. The boosts they gave stay in the stored
     * velocity, which ClientPacketListener#handleRespawn copies with KEEP_ENTITY_DATA.
     */
    public void clearLevel() {
        rockets.clear();
        placed = false;
    }

    /**
     * The stored velocities a teleport received before this tick finds: each of {@code velocities} after the last
     * tick's boosts with the look {@code xRot}/{@code yRot}, in either trig table.
     */
    public Set<Vec3> boosted(Set<Vec3> velocities, float xRot, float yRot) {
        if (boostsNextTick == 0) return velocities;
        Set<Vec3> boosted = certainBoostsNextTick == 0 ? new HashSet<>(velocities) : new HashSet<>();
        for (int fastMath = 0; fastMath < 2; fastMath++) {
            Vector3dm look = ReachUtils.getLook(player, xRot, yRot);
            for (Vec3 velocity : velocities) {
                Vec3 carried = velocity;
                for (int i = 1; i <= boostsNextTick; i++) {
                    carried = ElytraTransform.boostFirework(carried, look);
                    if (i >= certainBoostsNextTick) boosted.add(carried);
                }
            }
            player.trigHandler.toggleShitMath();
        }
        return boosted;
    }

    /**
     * ClientPacketListener#handleRespawn replaces the LocalPlayer; the rockets keep boosting the old one. The new
     * player copies the old delta movement only with {@code keepsVelocity}.
     */
    public void localPlayerReplaced(boolean keepsVelocity) {
        rockets.clear();
        placed = false;
        if (!keepsVelocity) {
            boostsNextTick = 0;
            certainBoostsNextTick = 0;
        }
    }

    /** The rockets that can have boosted the stored velocity after the last tick's travel. */
    public int getMaxFireworksAppliedPossible() {
        return boostsNextTick;
    }

    /** The rockets that boosted the stored velocity after the last tick's travel. */
    public int getCertainFireworksApplied() {
        return certainBoostsNextTick;
    }
}
