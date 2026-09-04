package ac.grim.grimac.manager.player;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.event.events.GrimPlayerSetbackEvent;
import ac.grim.grimac.api.event.events.GrimTeleportEvent;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsN;
import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.PredictionSetbackState;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.checks.impl.prediction.pipeline.MovementEngines;
import ac.grim.grimac.checks.impl.prediction.profile.MovementProfiles;
import ac.grim.grimac.checks.impl.prediction.stage.UncertaintyPipeline;
import ac.grim.grimac.checks.impl.prediction.stage.VelocityTransformer;
import ac.grim.grimac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.grim.grimac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.grim.grimac.checks.impl.prediction.runner.KnockbackHandler;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsB;
import ac.grim.grimac.checks.impl.prediction.stage.uncertainty.UncertaintyHelper;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.events.packets.patch.ResyncWorldUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.NumFormatter;
import ac.grim.grimac.utils.anticheat.update.PositionUpdate;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.*;
import ac.grim.grimac.utils.lists.EvictingQueue;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.VectorUtils;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.utils.nmsutil.IsUsingItem;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import ac.grim.grimac.network.protocol.teleport.RelativeFlag;
import net.minecraft.world.phys.Vec3;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import org.bukkit.GameMode;

import java.util.Collections;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

public class SetbackTeleportUtil extends GrimProcessor implements PostPredictionListener {
    private static final class Channels {
        private static final GrimTeleportEvent.Channel TELEPORT =
                GrimAPI.INSTANCE.getEventBus().get(GrimTeleportEvent.class);
        private static final GrimPlayerSetbackEvent.Channel PLAYER_SETBACK =
                GrimAPI.INSTANCE.getEventBus().get(GrimPlayerSetbackEvent.class);
    }

    private static final float ROTATION_TELEPORT_EPSILON = 1.0E-4F;

    // Sync to netty
    public final ConcurrentLinkedDeque<TeleportData> pendingTeleports = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedQueue<VehicleTeleport> vehicleTeleports = new ConcurrentLinkedQueue<>();
    private final AtomicLong bedrockTeleportRevision = new AtomicLong();

    // has the player fully joined the server yet
    public boolean hasFullyJoined = false;

    // Sync to netty, a player MUST accept a teleport to spawn into the world
    // A teleport is used to end the loading screen.  Some cheats pretend to never end the loading screen
    // in an attempt to disable the anticheat.  Be careful.
    // We fix this by blocking serverbound movements until the player is out of the loading screen.
    // TODO: Inspect the join packets to ensure that the player has sent player loaded packet
    public boolean hasFullyLoaded = false;
    // Was there a ghost block that forces us to block offsets until the player accepts their teleport?
    public boolean blockOffsets = false;
    // This required setback data is the head of the teleport.
    // It is set by both bukkit and netty due to going on the bukkit thread to setback players
    private volatile SetBackData requiredSetBack = null;
    public SetbackPosWithVector lastKnownGoodPosition;
    // The position of the last Bedrock movement packet actually forwarded to
    // Paper (Geyser's Java projection or a teleport echo), captured at the
    // packet level after all cancellation decisions. Never derived from
    // engine/canonical state, so it always equals Paper's lastGood* values.
    private Vec3 bedrockPaperVisiblePosition;

    public void setBedrockPaperVisiblePosition(Vec3 position) {
        this.bedrockPaperVisiblePosition = position;
    }
    // Are we currently sending setback stuff?
    public boolean isSendingSetback = false;
    private long lastWorldResync = 0;
    private int freeze = 5000;
    @Getter @Setter private boolean debug = false;


    public SetbackTeleportUtil(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        // If this is a regular teleport when on a vehicle, we may not set player variables
        // Therefore, read it from the position update given to us.
        Vec3 to = new Vec3(player.x, player.y, player.z);
        final PositionUpdate positionUpdate = predictionComplete.getPositionUpdate();
        if (positionUpdate != null) {
            to = positionUpdate.getTo();
        } else if (player.isBedrockMovement()
                && predictionComplete.getPredictionResult() != null
                && predictionComplete.getPredictionResult().isTeleport()
                && predictionComplete.getPredictionResult().getSetBackData() != null) {
            to = predictionComplete.getPredictionResult().getSetBackData().getLocation();
        }

        Vec3 afterTickFriction = Vec3.ZERO;
        PredictionSetbackState profileState = null;
        if (player.isBedrockMovement()) {
            profileState = MovementEngines.requireForProfile(MovementProfiles.forPlayer(player))
                    .captureSetbackState(predictionComplete.getPreparedCommit());
            if (profileState != null) {
                to = profileState.position();
                afterTickFriction = profileState.velocity();
            }
        } else if (!predictionComplete.isTeleport()) {
            final PredictionResult result = predictionComplete.getPredictionResult();
            PredVector initialVel = result.getInitialStartingVel();
            final SimulationContext context = result.getSimulationContext();
            final PredictionResult previousResult = player.checkManager.getSimulationProcessor().getLastPrediction();

            MovementTrace trace = MovementTrace.start(initialVel);
            for (UncertaintyHandler modifier : UncertaintyPipeline.MODIFIERS_FOR_SETBACKS) {
                trace = modifier.handleMovementTrace(player, result.getValidMovements(), result, context, previousResult, trace, context.getTarget());
            }
            initialVel = trace.position();
            final boolean usingItem = IsUsingItem.isUsingItem(player);
            float speed = context.getMaxSpeed(player);
            if (usingItem) {
                speed = (float) (speed * 0.2);
            } else if (player.isGliding) {
                speed = 0.0f;
            }
            initialVel = UncertaintyHelper.handleCircular(initialVel, context.getTarget(), speed);

            afterTickFriction = simulateFriction(initialVel, context.getWorldData().getInWater().determineOptimistically(), context.getWorldData().getInLava().determineOptimistically(), context.usesFallFlyingMovement(), context.getLastOnGround().determineOptimistically(), context.getWorldData().getStuckSpeed().getStuckSpeedMultiplier() != null);
        } else {
            final TransactionVel sentKnockback = player.checkManager.getKnockbackHandler().getLastSent();
            if (sentKnockback != null) {
                afterTickFriction = sentKnockback.getVel();
            }
        }

        // We must first check if the player has accepted their setback
        // If the setback isn't complete, then this position is illegitimate
        // if we are currently blocking offsets, then the player is desync'd in a vehicle and needs to be teleported soon
        final PredictionResult completedPrediction = predictionComplete.getPredictionResult();
        if (completedPrediction != null && completedPrediction.getSetBackData() != null) {
            // Teleport, let velocity be reset
            lastKnownGoodPosition = new SetbackPosWithVector(
                    to, afterTickFriction, player.totalFlyingPacketsSent, profileState);
        } else if ((!player.isBedrockMovement() || profileState != null)
                && (requiredSetBack == null || requiredSetBack.isComplete()
                && !blockOffsets && !player.getSetbackTeleportUtil().insideUnloadedChunk())) {
            // TODO: When flagging immediately after a setback, we don't give uncertainty or allow inputs. This is too strict.
            // No simulation... we can do that later. We just need to know the valid position.
            // As we didn't setback here, the new position is known to be safe!
            lastKnownGoodPosition = new SetbackPosWithVector(
                    to, afterTickFriction, player.totalFlyingPacketsSent, profileState);
        }

        if (requiredSetBack != null) requiredSetBack.tick();
    }

    public void executeForceResync(String cause) {
        if (!player.shouldEnforceMovementSetbacks()) return;
        if (player.isDisabled() || player.gamemode == GameMode.SPECTATOR)
            return; // We don't care about spectators, they don't flag
        if (lastKnownGoodPosition == null) return; // Player hasn't spawned yet
        if (isPendingSetback()) return; // Don't spam setbacks // TODO: Remove plugin teleport status
        if (player.getSetbackTeleportUtil().debug) {
            LogUtil.warn("Setback sent to " + player.getName() + ", resync: " + cause);
        }
        blockMovementsUntilResync(true, // simulate next tick
                true, // full resync
                false); // ignore if no forced change
    }

    public void executeNonSimulatingSetback() {
        if (!player.shouldEnforceMovementSetbacks()) return;
        if (player.isDisabled() || player.gamemode == GameMode.SPECTATOR)
            return; // We don't care about spectators, they don't flag
        if (isPendingSetback()) return; // Don't spam setbacks // TODO: Remove plugin teleport status
        if (lastKnownGoodPosition == null) return; // Player hasn't spawned yet
        if (player.getSetbackTeleportUtil().debug) {
            LogUtil.warn("Setback sent to " + player.getName() + ", non simulating");
        }
        blockMovementsUntilResync(false, // simulate next tick
                false, // full resync
                false); // ignore if no forced change
    }

    public boolean executeViolationSetback() {
        if (isExempt()) return false;
        if (isPendingSetback()) { // Don't spam setbacks // TODO: Remove plugin teleport status
            return false;
        }
        if (player.getSetbackTeleportUtil().debug) {
            LogUtil.warn("Setback sent to " + player.getName() + ", violation");
        }
        blockMovementsUntilResync(true, // simulate next tick
                false, // full resync
                false); // ignore if no forced change
        return true;
    }

    public boolean executeTooHighLatencySetback(String cause) {
        if (isExempt()) return false;
        if (player.getSetbackTeleportUtil().debug) {
            LogUtil.warn("Setback sent to " + player.getName() + ", high latency, " + cause);
        }
        blockMovementsUntilResync(true, // simulate next tick
                false, // full resync
                true); // ignore if no forced change
        return true;
    }

    private boolean isExempt() {
        // Not exempting spectators here because timer check for spectators is actually valid.
        // Player hasn't spawned yet
        if (lastKnownGoodPosition == null) return true;
        // Setbacks aren't allowed
        if (player.isDisabled()) {
            return true;
        }
        if (!player.shouldEnforceMovementSetbacks()) return true;
        // Player has permission to cheat, permission not given to OP by default.
        return player.noSetbackPermission && player.bukkitPlayer != null;
    }

    // Only let us full resync once every five seconds to prevent unneeded bukkit load
    public void resyncWorld() { if (System.currentTimeMillis() - lastWorldResync > 5 * 1000) {
            final SimpleCollisionBox expandedBox = player.boundingBox.copy().expand(1);
            ResyncWorldUtil.resyncPositions(player, expandedBox);
            lastWorldResync = System.currentTimeMillis();
        }
    }

    private void maybeThrowSetbackDebug(boolean simulateNext, boolean fullResync, boolean ignoreUnchanged) {
        if (!player.throwError) return;
        try {
            final String setbackDebug = "Setback: " + simulateNext + ", " + fullResync + ", " + ignoreUnchanged;
            throw new RuntimeException(setbackDebug);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void blockMovementsUntilResync(boolean simulateNext, boolean fullResync, boolean ignoreUnchanged) {
        if (lastKnownGoodPosition == null) return; // Hasn't spawned

        // If the chunk hasn't loaded, we can't simulate their movement yet
        if (player.getSetbackTeleportUtil().insideUnloadedChunk()) {
            simulateNext = false;
            fullResync = true;
        }

        if (requiredSetBack != null) {
            requiredSetBack.setPlugin(false); // The player has illegal movement, block from vanilla ac override
        }
        maybeThrowSetbackDebug(simulateNext, fullResync, ignoreUnchanged);
        this.resyncWorld();

        Vec3 clientVel = lastKnownGoodPosition.getVector();
        Vec3 position = lastKnownGoodPosition.getPos();
        if (player.isBedrockMovement() && bedrockPaperVisiblePosition != null) {
            // Anchor to the last position packet actually forwarded to Paper
            // (== Paper's lastGood*), never the canonical prediction. A
            // canonical-vs-authored gap would otherwise make the setback echo
            // an upward move and wipe fallDistance (movedUpwards ->
            // resetFallDistance; the anchor-desync NoFall bypass).
            position = bedrockPaperVisiblePosition;
        }
        PredictionSetbackState anchorProfileState = lastKnownGoodPosition.getProfileState();
        PredictionSetbackState profileState = player.isBedrockMovement()
                ? player.checkManager.getSimulationProcessor().getBedrockSetbackState()
                : anchorProfileState;
        if (profileState == null) {
            profileState = anchorProfileState;
        }

        TransactionVel sentKnockback = player.checkManager.getKnockbackHandler().getLastSent();
        TransactionVel sentExplosion = player.checkManager.getExplosionHandler().getLastSent();
        if (sentKnockback != null) {
            clientVel = sentKnockback.getVel();
        }
        // Don't apply explosions multiple times when spamming violations by looking at transaction order
        final boolean explosionIsNewer = sentExplosion != null && (sentKnockback == null || sentExplosion.getTransaction() > sentKnockback.getTransaction());
        if (explosionIsNewer) {
            clientVel = clientVel.add(sentExplosion.getVel());
        }

        PredictionResult lastPrediction = player.checkManager.getSimulationProcessor().getLastPrediction();
        boolean expectedOnGround = anchorProfileState != null
                ? anchorProfileState.expectedOnGround()
                : !player.isBedrockMovement() && lastPrediction != null
                && lastPrediction.getSimulationContext().getLastOnGround().determinePessimistically();

        // Mini prediction engine - simulate collisions
        if (!player.isBedrockMovement() && simulateNext && lastPrediction != null
                && !player.compensatedEntities.getSelf().inVehicle()) {
            SimpleCollisionBox oldBB = player.boundingBox;
            player.boundingBox = GetBoundingBox.getPlayerBoundingBox(player, position.x, position.y, position.z);

            final PredVector thresholdInput = new PredVector(clientVel);
            clientVel = VelocityTransformer.applyMovementThreshold(Collections.singletonList(thresholdInput), player.getClientVersion()).get(0);

            Vec3 collide = Collisions.collide(player, clientVel.x, clientVel.y, clientVel.z);

            // We don't want to make it IMPOSSIBLE for high ping players to play after taking knockback
            // If the player isn't really moving at all, don't bother
            if (ignoreUnchanged && collide.lengthSqr() < 0.001 * 0.001) {
                return;
            }

            SimulationContext context = lastPrediction.getSimulationContext();

            Vec3 stuckSpeedMultiplier = context.getWorldData().getStuckSpeed().getStuckSpeedMultiplier();

            position = new Vec3(position.x + collide.x, position.y, position.z);
            // 1.8 players need the collision epsilon to not phase into blocks when being setback
            // Due to simulation, this will not allow a flight bypass by sending a billion invalid movements
            position = new Vec3(position.x, position.y + collide.y, position.z);
            position = new Vec3(position.x, position.y, position.z + collide.z);

            if (stuckSpeedMultiplier != null) {
                final Vec3 slowedVel = clientVel.multiply(stuckSpeedMultiplier);
                clientVel = slowedVel;
            }

            if (clientVel.x != collide.x) clientVel = new Vec3(0, clientVel.y, clientVel.z);
            if (clientVel.y != collide.y) clientVel = new Vec3(clientVel.x, 0, clientVel.z);
            if (clientVel.z != collide.z) clientVel = new Vec3(clientVel.x, clientVel.y, 0);


            clientVel = simulateFriction(clientVel, context.getWorldData().getInWater().determineOptimistically(), context.getWorldData().getInLava().determineOptimistically(), context.usesFallFlyingMovement(), context.getLastOnGround().determineOptimistically(), stuckSpeedMultiplier != null);

            player.boundingBox = oldBB; // reset back to the new bounding box
        }

        if (!hasFullyLoaded) { clientVel = null; } // if the player hasn't spawned... don't force kb

        // Something weird has occurred in the player's movement, block offsets until we resync
        if (fullResync) {
            blockOffsets = true;
        }

        if (debug) { LogUtil.info("Teleported " + player.getName() + " to " + position + " with vel " + clientVel + " using " + simulateNext + " " + fullResync + " " + ignoreUnchanged); }

        SetBackData data = new SetBackData(
                new TeleportData(position, new RelativeFlag(0b11000), player.lastTransactionSent.get(), 0),
                player.xRot,
                player.yRot,
                clientVel,
                player.compensatedEntities.getSelf().getRiding() != null,
                false,
                expectedOnGround,
                profileState);
        sendSetback(data);
        if (debug) { LogUtil.info("Teleport sent | resync=" + fullResync + ", infc=" + ignoreUnchanged + ", simNext=" + simulateNext); }
    }

    private Vec3 simulateFriction(Vec3 input, boolean water, boolean lava, boolean gliding, boolean wasOnGround, boolean stuckSpeed) {
        double gravity = 0.08D;
        final boolean noGravity = !player.compensatedEntities.getEntityInControl().hasGravity;
        final boolean slowFalling = input.y < 0 && player.compensatedEntities.getSlowFallingAmplifier() != null;
        final boolean ridingBoat = player.compensatedEntities.getSelf().inVehicle() && player.compensatedEntities.getSelf().getRiding().isBoat();
        if (noGravity) {
            gravity = 0.0D;
        } else if (slowFalling) {
            gravity = 0.01D;
        } else if (ridingBoat) {
            gravity = 0.04D;
        }

        // We must always do this before simulating positions, as this is the last actual (safe) movement
        // We must not do this for knockback or explosions, as they are at the start of the tick
        Vec3 simulated;
        if (stuckSpeed) {
            simulated = new Vec3(0, -gravity, 0);
        } else if (water) {
            simulated = input.scale(0.8f).subtract(0.0D, gravity / 16.0f, 0.0D);
        } else if (lava) {
            simulated = input.scale(0.5D).subtract(0.0D, gravity / 4.0D, 0.0D);
        } else if (gliding) {
            simulated = input.multiply(0.99F, 0.98F, 0.99F);
        } else { // Gliding doesn't have friction, we handle it differently
            final double horizFriction = wasOnGround ? 0.91F * 0.6F : 0.91F;
            simulated = input.subtract(0.0D, gravity, 0.0D).multiply(horizFriction, 0.98f, horizFriction);
        }

        // stop 1.8 players from stepping onto 1.25 high blocks, because why not?
        final PredVector thresholdOutput = new PredVector(simulated);
        return VelocityTransformer.applyMovementThreshold(Collections.singletonList(thresholdOutput), player.getClientVersion()).get(0);
    }


    private final Random random = new Random();

    public int nextTeleportId() {
        return random.nextInt() | Integer.MIN_VALUE;
    }

    private void sendSetback(SetBackData data) {
        isSendingSetback = true;
        Vec3 position = data.getTeleportData().getLocation();

        try {
            double y = position.y;

            // Send a transaction now to make sure there's always transactions around teleport
            player.sendTransaction();

            // Min value is 10000000000000000000000000000000 in binary, this makes sure the number is always < 0
            int teleportId = random.nextInt() | Integer.MIN_VALUE;
            data.setPlugin(false);
            data.getTeleportData().setTeleportId(teleportId);
            data.getTeleportData().setTransaction(player.lastTransactionSent.get());

            final int vehicleEntityId = resolveSetbackVehicleId();

            // Player is in a vehicle
            if (vehicleEntityId != Integer.MIN_VALUE) {
                // Don't setback the wrong vehicle
                if (player.compensatedEntities.vehicles.serverPlayerVehicle == null || player.compensatedEntities.vehicles.serverPlayerVehicle != vehicleEntityId) {
                    return;
                }

                Vec3 vehiclePosition = new Vec3(position.x, position.y, position.z);
                // PacketServerTeleport observes this packet and queues the exact
                // vanilla snap/echo response position for the vehicle teleport.
                GrimAPI.INSTANCE.getNetworkManager().sendPacket(player.user.getChannel(),
                        ac.grim.grimac.network.packet.NmsPacketUtil.clientboundMoveVehiclePacket(
                                vehiclePosition, player.xRot, player.yRot), false);
            } else {
                // Use provided transaction ID to make sure it can never desync, although there's no reason to do this
                addSentTeleport(new Vec3(position.x, y, position.z), data.getTeleportData().getTransaction(), new RelativeFlag(0b11000), false, teleportId);
                // Receive the player's position packet to make setbacks appear smooth for other players (and to stop vanilla ac setbacks)
                GrimAPI.INSTANCE.getNetworkManager().receivePacket(player.user.getChannel(),
                        ac.grim.grimac.network.packet.NmsPacketUtil.positionPacket(
                                position.x, y, position.z, data.isExpectedOnGround(), false), true);
                // Send after tracking to fix race condition
                // The entity teleport mirrors position/onGround state; keep look relative like the player teleport.
                GrimAPI.INSTANCE.getNetworkManager().sendPacket(
                        player.user.getChannel(),
                        ac.grim.grimac.network.packet.NmsPacketUtil.playerPositionPacket(
                                teleportId,
                                position.x,
                                position.y,
                                position.z,
                                0.0F,
                                0.0F,
                                data.getTeleportData().getFlags().getMask()
                        ),
                        true
                );
                GrimAPI.INSTANCE.getNetworkManager().sendPacket(
                        player.user.getChannel(),
                        ac.grim.grimac.network.packet.NmsPacketUtil.modernEntityTeleportPacket(
                                player.entityID,
                                position.x,
                                position.y,
                                position.z,
                                0.0F,
                                0.0F,
                                data.getTeleportData().getFlags().getMask(),
                                data.isExpectedOnGround()
                        ),
                        true
                );
                //
            }

            // required setback applies for both our own vehicle teleports and regular teleports
            requiredSetBack = data;

            // Preserve the public packet-level and semantic setback signals from
            // the PacketEvents path after the teleport is tracked and emitted.
            long now = System.currentTimeMillis();
            Channels.TELEPORT.fire(player, teleportId, now);
            Channels.PLAYER_SETBACK.fire(player, teleportId, position.x, position.y, position.z, now);

            final KnockbackHandler knockbackHandler = player.checkManager.getKnockbackHandler();
            if (data.getVelocity() != null && (data.getVelocity().lengthSqr() > 0 || vehicleEntityId != Integer.MIN_VALUE)) {
                knockbackHandler.setSetbackVal(true);
                player.user.sendPacket(new ClientboundSetEntityMotionPacket(
                        vehicleEntityId == Integer.MIN_VALUE ? player.entityID : vehicleEntityId,
                        new Vec3(data.getVelocity().x, data.getVelocity().y, data.getVelocity().z)
                ));
                knockbackHandler.setSetbackVal(false);
            } else {
                player.sendTransaction();
            }
        } finally {
            isSendingSetback = false;
        }
    }

    private int resolveSetbackVehicleId() {
        Integer serverVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
        if (serverVehicle != null
                && player.compensatedEntities.vehicles.isServerPlayerPassengerOf(serverVehicle)) {
            return serverVehicle;
        }

        // The compensated graph trails the packet stream. It remains the
        // client-visible authority during an ordered dismount transition.
        return player.getRidingVehicleId();
    }

    /**
     * @param x - Player X position
     * @param y - Player Y position
     * @param z - Player Z position
     * @return - Whether the player has completed a teleport by being at this position
     */
    public TeleportAcceptData checkTeleportQueue(double px, double py, double pz) {
        return checkTeleportQueue(new Vec3(px, py, pz));
    }

    public boolean matchesPendingBedrockTeleportPosition(Vec3 physicalFeetPosition) {
        if (!player.isBedrockMovement()) {
            return false;
        }

        for (TeleportData teleport : pendingTeleports) {
            if (teleport.isRotationOnly()) {
                continue;
            }

            Vec3 expected = VectorUtils.clampVector(new Vec3(
                    (teleport.isRelativeX() ? player.x : 0.0D) + teleport.getLocation().x,
                    (teleport.isRelativeY() ? player.y : 0.0D) + teleport.getLocation().y,
                    (teleport.isRelativeZ() ? player.z : 0.0D) + teleport.getLocation().z));
            double xTolerance = teleport.isRelativeX() ? player.getMovementThreshold() : 0.0D;
            double yTolerance = teleport.isRelativeY() ? player.getMovementThreshold() : 0.0D;
            double zTolerance = teleport.isRelativeZ() ? player.getMovementThreshold() : 0.0D;
            if (Math.abs(expected.x - physicalFeetPosition.x) <= xTolerance
                    && Math.abs(expected.y - physicalFeetPosition.y) <= yTolerance
                    && Math.abs(expected.z - physicalFeetPosition.z) <= zTolerance) {
                return true;
            }
        }
        return false;
    }

    public TeleportAcceptData acknowledgeBedrockTeleportFrame(Vec3 physicalFeetPosition) {
        return acknowledgeBedrockTeleportFrame(physicalFeetPosition, true);
    }

    public TeleportAcceptData acknowledgeBedrockTeleportFrame(Vec3 physicalFeetPosition,
                                                               boolean handlesTeleport) {
        TeleportAcceptData accepted = new TeleportAcceptData();
        if (!player.isBedrockMovement() || physicalFeetPosition == null || !handlesTeleport) {
            return accepted;
        }

        Vec3 actual = VectorUtils.clampVector(physicalFeetPosition);
        TeleportData matching = null;
        for (TeleportData pending : pendingTeleports) {
            if (!pending.isRotationOnly()
                    && pending.getBedrockTransportRevision() >= 0L
                    && player.lastTransactionReceived.get() >= pending.getTransaction()
                    && samePosition(VectorUtils.clampVector(pending.getLocation()), actual)) {
                // One HANDLE_TELEPORT acknowledges the latest identical
                // outbound teleport. Reliable packet order proves that every
                // earlier identical boundary was processed first, so retaining
                // those entries would wait for acknowledgements Geyser consumes
                // and never projects to Java.
                matching = pending;
            }
        }
        if (matching == null) {
            return accepted;
        }

        // The ordered reliable channel plus this exact echo proves the client
        // processed every earlier packet too. Earlier still-pending transport
        // entries can never be echoed once the client's proven position has
        // moved past this teleport, so they are superseded and dropped in
        // queue order. This does not relax violation enforcement: a Grim
        // setback's requiredSetBack survives the drop and keeps blocking
        // movement until its own transaction-matching completion.
        while (pendingTeleports.peek() != null && pendingTeleports.peek() != matching) {
            TeleportData head = pendingTeleports.peek();
            if (head.isRotationOnly() || head.getBedrockTransportRevision() < 0L) {
                break;
            }
            pendingTeleports.poll();
        }

        if (!pendingTeleports.remove(matching)) {
            return accepted;
        }

        // This is the existing Java teleport proof: its pre-teleport Grim ping
        // has been answered and Geyser supplied the exact Bedrock echo. Consume
        // the echo as a correction boundary without invoking ActorMove.
        return completeTeleport(matching, actual, true);
    }

    public boolean hasPendingBedrockTransportTeleport() {
        return pendingTeleports.stream().anyMatch(
                pending -> !pending.isRotationOnly()
                        && pending.getBedrockTransportRevision() >= 0L);
    }

    private static boolean samePosition(Vec3 first, Vec3 second) {
        return Math.abs(first.x - second.x) <= BEDROCK_WIRE_POSITION_EPSILON
                && Math.abs(first.y - second.y) <= BEDROCK_WIRE_POSITION_EPSILON
                && Math.abs(first.z - second.z) <= BEDROCK_WIRE_POSITION_EPSILON;
    }

    // The Bedrock wire position is float32 with Geyser's 1.62f eye offset while
    // the bridge canonicalizes through the double 1.6200103759765625 constant,
    // so the same physical position can surface ~2e-5 apart. The match window
    // only needs to exclude a client that did not adopt the teleport at all.
    private static final double BEDROCK_WIRE_POSITION_EPSILON = 1.0E-4D;

    private TeleportAcceptData completeTeleport(TeleportData teleport, Vec3 location,
                                                boolean matchedPosition) {
        TeleportAcceptData accepted = new TeleportAcceptData();
        accepted.setMatchedTeleportPosition(matchedPosition);
        if (requiredSetBack != null
                && requiredSetBack.getTeleportData().getTransaction() == teleport.getTransaction()) {
            blockOffsets = false;
            accepted.setSetback(requiredSetBack);
            accepted.setInitialSpawnTeleport(!hasFullyLoaded);
            requiredSetBack.setComplete(true);
            this.hasFullyLoaded = true;
            this.hasFullyJoined = true;
        }
        accepted.setTeleportData(teleport.copyWithLocation(location));
        accepted.setTeleport(true);
        return accepted;
    }

    private TeleportAcceptData checkTeleportQueue(Vec3 physicalFeetPosition) {
        // Support teleports without teleport confirmations
        // If the player is in a vehicle when teleported, they will exit their vehicle
        TeleportAcceptData teleportData = new TeleportAcceptData();

        TeleportData teleportPos;
        while ((teleportPos = pendingTeleports.peek()) != null) {
            if (teleportPos.isRotationOnly()) {
                if (player.lastTransactionReceived.get() >= teleportPos.getTransaction()) {
                    pendingTeleports.poll();
                    continue;
                }
                break;
            }

            if (teleportPos.getBedrockTransportRevision() >= 0L) {
                // Bedrock transport entries are completed only by the exact raw
                // auth-input echo after their normal Grim transaction proof. A
                // translated Java move is not a second acknowledgement channel.
                break;
            }

            double trueTeleportX = (teleportPos.isRelativeX() ? player.x : 0) + teleportPos.getLocation().x;
            double trueTeleportY = (teleportPos.isRelativeY() ? player.y : 0) + teleportPos.getLocation().y;
            double trueTeleportZ = (teleportPos.isRelativeZ() ? player.z : 0) + teleportPos.getLocation().z;

            // There seems to be a version difference in teleports past 30 million... just clamp the vector
            // I'm not sure how teleports work on 26.1
            Vec3 clamped = VectorUtils.clampVector(new Vec3(trueTeleportX, trueTeleportY, trueTeleportZ));

            // There was some previous claims that rounding was performed here, I see no evidence of this.
            // TODO: Investigate vehicle teleports working for players too on 26.1?

            double xDiff = Math.abs(clamped.x - physicalFeetPosition.x);
            double yDiff = Math.abs(clamped.y - physicalFeetPosition.y);
            double zDiff = Math.abs(clamped.z - physicalFeetPosition.z);

            // idk why mojang just doesn't set the movement threshold to 0, but I'm not fighting over 0.0002
            boolean xPass = xDiff <= (teleportPos.isRelativeX() ? player.getMovementThreshold() : 0);
            boolean yPass = yDiff <= (teleportPos.isRelativeY() ? player.getMovementThreshold() : 0);
            boolean zPass = zDiff <= (teleportPos.isRelativeZ() ? player.getMovementThreshold() : 0);

            int receivedTransaction = player.lastTransactionReceived.get();
            if (debug) LogUtil.info("dx=" + xDiff + " dy=" + yDiff + " dz=" + zDiff + " | trans=" + receivedTransaction + " | " + teleportPos.getTransaction());

            boolean exactTeleportPosition = xPass && yPass && zPass;
            if ((exactTeleportPosition && (teleportPos.isPositionOnly()
                    || receivedTransaction == teleportPos.getTransaction()))) {
                pendingTeleports.poll();
                return completeTeleport(teleportPos, clamped, true);
            } else if (teleportPos.isPositionOnly() && receivedTransaction <= teleportPos.getTransaction()) {
                break;
            } else if (teleportPos.isPositionOnly() || receivedTransaction > teleportPos.getTransaction()) {
                if (debug) { LogUtil.info("TP ignored: xd=" + xDiff + " yd=" + yDiff + " zd=" + zDiff); }

                boolean currentRequiredSetback = requiredSetBack != null
                        && requiredSetBack.getTeleportData().getTransaction() == teleportPos.getTransaction();
                boolean vehicleTransitionCanIgnoreTeleport = teleportPos.isSentWhileVehicle()
                        && isVehicleTransitionTeleportContext();

                // MCP-Reborn ClientPacketListener#handleMovePlayer always
                // confirms the teleport id, but only applies the position when
                // minecraft.player is not a passenger. A mounted confirmation
                // is therefore valid protocol, but it is not proof that a
                // Grim-owned setback position was applied.
                if (!vehicleTransitionCanIgnoreTeleport) {
                    final String teleportDiffs = "xd=" + NumFormatter.formatNumberStandard(xDiff) + " yd=" + NumFormatter.formatNumberStandard(yDiff) + " zd=" + NumFormatter.formatNumberStandard(zDiff);
                    player.checkManager.getListener(BadPacketsN.class).flag(teleportDiffs);
                }
                pendingTeleports.poll();
                if (currentRequiredSetback) {
                    requiredSetBack.setPlugin(false);
                }
                if (!vehicleTransitionCanIgnoreTeleport && pendingTeleports.isEmpty() && requiredSetBack != null) {
                    resendIgnoredRequiredSetback("player-position-ignored");
                }
                continue;
            }
            // No farther setbacks before the player's transactoin
            break;
        }

        return teleportData;
    }

    public TeleportAcceptData checkRotationTeleportQueue(float yaw, float pitch) {
        TeleportAcceptData teleportData = new TeleportAcceptData();

        TeleportData teleportPos;
        while ((teleportPos = pendingTeleports.peek()) != null) {
            if (!teleportPos.isRotationOnly()) {
                return teleportData;
            }

            if (matchesRotationTeleport(teleportPos, yaw, pitch)) {
                pendingTeleports.poll();
                teleportData.setTeleportData(teleportPos.copyWithLocation(new Vec3(player.x, player.y, player.z)));
                teleportData.setTeleport(true);
                return teleportData;
            }

            if (player.lastTransactionReceived.get() >= teleportPos.getTransaction()) {
                pendingTeleports.poll();
                continue;
            }

            return teleportData;
        }

        return teleportData;
    }

    private static boolean matchesRotationTeleport(TeleportData teleportPos, float yaw, float pitch) {
        return Math.abs(teleportPos.getFinalYaw() - yaw) <= ROTATION_TELEPORT_EPSILON
                && Math.abs(teleportPos.getFinalPitch() - pitch) <= ROTATION_TELEPORT_EPSILON;
    }

    private boolean isVehicleTransitionTeleportContext() {
        return player.compensatedEntities.vehicles.serverPlayerVehicle != null
                || player.compensatedEntities.getSelf().inVehicle();
    }

    public TeleportAcceptData checkMountedTeleportQueue(int teleportId) {
        TeleportAcceptData teleportData = new TeleportAcceptData();
        TeleportData teleportPos = matchingMountedTeleport(teleportId);
        if (teleportPos == null) {
            return teleportData;
        }

        // Vanilla clients do not apply ClientboundPlayerPositionPacket while mounted.
        // They confirm the teleport ID, then send PosRot with their unchanged passenger position.
        pendingTeleports.remove(teleportPos);

        boolean currentRequiredSetback = requiredSetBack != null
                && requiredSetBack.getTeleportData().getTransaction() == teleportPos.getTransaction();
        if (currentRequiredSetback && requiredSetBack.isPlugin()) {
            blockOffsets = false;

            teleportData.setSetback(requiredSetBack);
            teleportData.setInitialSpawnTeleport(!this.hasFullyLoaded);
            requiredSetBack.setComplete(true);

            this.hasFullyLoaded = true;
            this.hasFullyJoined = true;
        } else if (currentRequiredSetback) {
            // The vanilla client acknowledged this ClientboundPlayerPositionPacket
            // while mounted, but did not apply its position. Do not let a
            // malicious client turn that valid mounted ignore into a completed
            // Grim setback.
            requiredSetBack.setPlugin(false);
            resendIgnoredRequiredSetback("mounted-player-position-ignored");
        }

        Vec3 clamped = VectorUtils.clampVector(new Vec3(
                (teleportPos.isRelativeX() ? player.x : 0) + teleportPos.getLocation().x,
                (teleportPos.isRelativeY() ? player.y : 0) + teleportPos.getLocation().y,
                (teleportPos.isRelativeZ() ? player.z : 0) + teleportPos.getLocation().z
        ));
        teleportData.setTeleportData(teleportPos.copyWithLocation(clamped));
        teleportData.setTeleport(true);
        return teleportData;
    }

    private TeleportData matchingMountedTeleport(int teleportId) {
        TeleportData teleport = matchingVehicleTeleport(teleportId);
        return teleport != null && teleport.isSentWhileVehicle() && !teleport.isSentDuringVehicleDismount() ? teleport : null;
    }

    public boolean hasPendingVehicleDismountTeleport(int teleportId) {
        for (TeleportData teleport : pendingTeleports) {
            if (teleport.getTeleportId() == teleportId) {
                return teleport.isSentDuringVehicleDismount();
            }
        }
        return false;
    }

    public boolean markVehicleDismountTeleportIdAccepted(int teleportId) {
        TeleportData teleport = matchingVehicleTeleport(teleportId);
        if (teleport == null || !teleport.isSentDuringVehicleDismount()) {
            return false;
        }

        teleport.setPositionOnly(true);
        return true;
    }

    private TeleportData matchingVehicleTeleport(int teleportId) {
        while (true) {
            TeleportData head = pendingTeleports.peek();
            if (head == null) {
                return null;
            }

            if (head.getTeleportId() == teleportId) {
                return head;
            }

            if (!head.isSentWhileVehicle()) {
                return null;
            }

            TeleportData skipped = pendingTeleports.poll();
            if (isRequiredSetbackTeleport(skipped) && requiredSetBack != null) {
                requiredSetBack.setPlugin(false);
            }
        }
    }

    /**
     * @param x - Player X position
     * @param y - Player Y position
     * @param z - Player Z position
     * @return - Whether the player has completed a teleport by being at this position
     */
    public TeleportAcceptData checkVehicleTeleportQueue(Integer vehicleId, double x, double y, double z) {
        TeleportAcceptData acceptData = new TeleportAcceptData();

        int lastTransaction = player.lastTransactionReceived.get();

        while (true) {
            VehicleTeleport teleportPos = vehicleTeleports.peek();
            if (teleportPos == null) break;
            if (vehicleId != null && teleportPos.vehicleId() != vehicleId) {
                if (lastTransaction >= teleportPos.transaction()) {
                    final VehicleTeleport staleEntry = vehicleTeleports.poll();
                    continue;
                }
                break;
            }

            Vec3 position = teleportPos.position();

            if (debug) { LogUtil.info("Vehicle queue: id: " + teleportPos.vehicleId() + " pt: " + teleportPos.transaction() + " lt: " + lastTransaction + " | x=" + (position.x - x) + " y=" + (position.y - y) + " z=" + (position.z - z)); }

            if (matchesVehicleTeleportPosition(position, x, y, z, teleportPos.responseDistanceTolerance())) {
                final VehicleTeleport matchedEntry = vehicleTeleports.poll();
                TeleportData acceptedTeleport = new TeleportData(position, new RelativeFlag(0), lastTransaction, 0);

                if (debug) { LogUtil.info("Vehicle queue accepted (size: " + vehicleTeleports.size() + ")"); }

                // The player completed all teleports
                if (requiredSetBack != null && requiredSetBack.getTeleportData().getTransaction() == teleportPos.transaction()) {
                    if (debug) { LogUtil.info("Removed vehicle blockOffsets, previous=" + blockOffsets); }
                    blockOffsets = false;
                    requiredSetBack.setComplete(true);
                    acceptData.setSetback(requiredSetBack);
                    acceptedTeleport = requiredSetBack.getTeleportData();
                }

                acceptData.setTeleportData(acceptedTeleport);
                acceptData.setVehicleTeleportData(teleportPos.state());
                acceptData.setTeleport(true);
                return acceptData;
            }

            // MCP-Reborn sends exact MoveVehicle responses from the packet
            // handler for local-authoritative vehicle teleports. A coordinate
            // match above is proof; otherwise wait for transaction ordering.
            if (lastTransaction < teleportPos.transaction()) {
                break;
            } else if (lastTransaction > teleportPos.transaction() + 1) {
                boolean ignoredRequiredSetbackVehicleTeleport = isRequiredSetbackVehicleTeleport(teleportPos);
                final VehicleTeleport ignoredEntry = vehicleTeleports.poll();

                if (ignoredRequiredSetbackVehicleTeleport) {
                    resendIgnoredRequiredSetback("vehicle-position-ignored");
                }

                // Vehicles have terrible netcode so just ignore it if the teleport wasn't from us setting the player back
                // Players don't have to respond to vehicle teleports if they aren't controlling the entity anyways
                continue;
            }

            break;
        }

        return acceptData;
    }

    private boolean matchesVehicleTeleportPosition(Vec3 position, double x, double y, double z, double responseDistanceTolerance) {
        return (matchesVehicleTeleportCoordinate(position.x, x)
                && matchesVehicleTeleportCoordinate(position.y, y)
                && matchesVehicleTeleportCoordinate(position.z, z))
                || matchesVehicleTeleportDistance(position, x, y, z, responseDistanceTolerance);
    }

    private boolean matchesVehicleTeleportCoordinate(double expected, double actual) {
        if (!Double.isFinite(expected) || !Double.isFinite(actual)) {
            return false;
        }

        // Grim sometimes reconstructs the same vanilla vehicle coordinate from
        // its collision-box shadow. One ULP covers that arithmetic representation
        // drift without accepting any measurable movement.
        return expected == actual || Math.abs(expected - actual) <= Math.max(Math.ulp(expected), Math.ulp(actual));
    }

    private boolean matchesVehicleTeleportDistance(Vec3 expected, double x, double y, double z, double tolerance) {
        if (tolerance <= 0.0D || !Double.isFinite(tolerance)) {
            return false;
        }
        if (!Double.isFinite(expected.x) || !Double.isFinite(expected.y) || !Double.isFinite(expected.z)
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }
        // MCP-Reborn ClientPacketListener#handleMoveVehicle only snaps when the
        // packet is farther than 1.0E-5 from the current interpolation position.
        // The queued response for that clientbound packet is therefore bounded to
        // the same no-snap radius; other vehicle teleports pass zero tolerance.
        return expected.distanceTo(new Vec3(x, y, z)) <= tolerance;
    }

    private boolean isRequiredSetbackVehicleTeleport(VehicleTeleport teleport) {
        return requiredSetBack != null
                && !requiredSetBack.isComplete()
                && requiredSetBack.getTeleportData().getTransaction() == teleport.transaction();
    }

    private boolean isRequiredSetbackTeleport(TeleportData teleport) {
        return requiredSetBack != null
                && !requiredSetBack.isComplete()
                && requiredSetBack.getTeleportData().getTransaction() == teleport.getTransaction();
    }

    private void resendIgnoredRequiredSetback(String reason) {
        if (requiredSetBack == null || requiredSetBack.isPlugin()) {
            return;
        }

        requiredSetBack.setPlugin(false);
        if (debug) { LogUtil.info("Reissuing ignored Grim setback for " + player.getName() + ": " + reason); }
        sendSetback(requiredSetBack);
    }

    public void addVehicleTeleport(int vehicleId, int transaction, Vec3 position) {
        addVehicleTeleport(vehicleId, transaction, position, null);
    }

    public void addVehicleTeleport(int vehicleId, int transaction, Vec3 position, double responseDistanceTolerance) {
        addVehicleTeleport(vehicleId, transaction, position, null, responseDistanceTolerance);
    }

    public void addVehicleTeleport(int vehicleId, int transaction, Vec3 position, VehicleTeleportData state) {
        addVehicleTeleport(vehicleId, transaction, position, state, 0.0D);
    }

    public void addVehicleTeleport(int vehicleId, int transaction, Vec3 position, VehicleTeleportData state, double responseDistanceTolerance) {
        vehicleTeleports.add(new VehicleTeleport(vehicleId, transaction, position, state, responseDistanceTolerance));
    }

    public void clearVehicleTeleports() {
        vehicleTeleports.clear();
    }

    private record VehicleTeleport(int vehicleId, int transaction, Vec3 position, VehicleTeleportData state,
                                   double responseDistanceTolerance) {
    }

    /**
     * @return If the player is in a desync state and is waiting on information from the server
     */
    public boolean shouldBlockMovement() {
        // This is required to ensure protection from servers teleporting from CREATIVE to SURVIVAL
        // I should likely refactor
        boolean block = (!hasFullyLoaded || blockOffsets || isPendingSetback()) && !insideUnloadedChunk();
        return tooFarFromUnloadedChunk() || block;
    }

    public boolean hasPendingPlayerPositionTeleport() {
        TeleportData teleport = pendingTeleports.peek();
        return teleport != null && !teleport.isRotationOnly();
    }

    public boolean shouldBlockVehicleMovement() {
        if (tooFarFromUnloadedChunk()) return true;
        if (insideUnloadedChunk()) return false;

        // Vehicle packets move the controlled root entity, not the player body. SetbackBlocker has
        // already proved current server mount/control and active Grim vehicle setbacks separately.
        return !hasFullyLoaded || isPendingSetback();
    }

    public boolean hasUnacknowledgedSetbackVehicleTeleport() {
        if (requiredSetBack == null || requiredSetBack.isPlugin() || requiredSetBack.isComplete()) {
            return false;
        }

        for (VehicleTeleport teleport : vehicleTeleports) {
            if (isRequiredSetbackVehicleTeleport(teleport)
                    && player.lastTransactionReceived.get() < teleport.transaction()) {
                return true;
            }
        }
        return false;
    }

    public int queuedVehicleTeleportCount() {
        return vehicleTeleports.size();
    }

    public String getDebugStrings() { final String setbackState = requiredSetBack == null ? "null" : !requiredSetBack.isComplete() + " x: " + player.x + ", y: " + player.y + " z: " + player.z;
        return "tooFar: " + tooFarFromUnloadedChunk() + ", insideUnloadedChunk: " + insideUnloadedChunk() + ", hasFullyLoaded: " + hasFullyLoaded + ", blockOffsets: " + blockOffsets + ", requiredSetBack: " + setbackState;
    }

    public boolean isPendingSetback() { if (requiredSetBack == null || requiredSetBack.isPlugin() || requiredSetBack.isComplete()) {
            // Relative setbacks shouldn't count
            return false;
        }
        if (requiredSetBack.getTeleportData().isRelativeX() || requiredSetBack.getTeleportData().isRelativeY() || requiredSetBack.getTeleportData().isRelativeZ()) {
            return false;
        }
        // The setback is not complete
        return true;
    }

    /**
     * If a player is inside of an unloaded chunk AND are too far away from the teleport so that we shouldn't consider
     * their current position valid.
     * <p>
     * This prevents us from harassing players on teleport
     * <p>
     * PLEASE BE CAREFUL!!!
     * <p>
     * Unloaded chunks are the only exemption not bound by a teleport.
     * This is required to prevent people from disabling grim.
     */
    public boolean tooFarFromUnloadedChunk() { if (requiredSetBack == null || !insideUnloadedChunk()) return false;
        // check if the player is too far away from the teleport vertically
        Vec3 teleportTarget = requiredSetBack.getTeleportData().getLocation();
        if (player.y > teleportTarget.y) {
            if (debug) LogUtil.info(player.getName() + " : T1 : y= " + player.y + " > " + teleportTarget.y);
            return true;
        }
        // only check x and z because of players falling through the world
        // can a hacked client actually abuse flying downwards in an unloaded chunk?
        Vec3 copy = new Vec3(teleportTarget.x, 0, teleportTarget.z);
        double distance = copy.distanceTo(new Vec3(player.x, 0, player.z));
        if (debug) { LogUtil.info(player.getName() + " : T1 : dist= " + distance); }
        return distance > 4.0D; // this could probably be reduced further now that it's only checking x and z
    }

    /**
     * When the player is inside an unloaded chunk, they simply fall through the void which shouldn't be checked
     *
     * @return Whether the player has loaded the chunk and accepted a teleport to correct movement or not
     */
    public boolean insideUnloadedChunk() {
        CompensatedWorld.CachedChunk column = player.compensatedWorld.getChunk(GrimMath.floor(player.x) >> 4, GrimMath.floor(player.z) >> 4);

        // If true, the player is in an unloaded chunk
        return !player.isDisabled() && (column == null || column.getTransaction() >= player.lastTransactionReceived.get());
    }

    /**
     * @return The current data for the setback, regardless of whether it is complete or not
     */
    public SetBackData getRequiredSetBack() { return this.requiredSetBack; }

    public void updateSafeVehiclePosition(Vec3 pos) {
        this.lastKnownGoodPosition = new SetbackPosWithVector(pos, Vec3.ZERO, player.totalFlyingPacketsSent);
    }

    public void addSentTeleport(Vec3 position, int transaction, RelativeFlag flags, boolean plugin, int teleportId) {
        addSentTeleport(position, Vec3.ZERO, transaction, flags, plugin, teleportId);
    }

    public void addSentTeleport(Vec3 position, Vec3 deltaMovement, int transaction, RelativeFlag flags, boolean plugin, int teleportId) {
        addSentTeleport(position, deltaMovement, transaction, flags, plugin, teleportId, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    public void addSentTeleport(Vec3 position, Vec3 deltaMovement, int transaction, RelativeFlag flags, boolean plugin, int teleportId, float sourceYaw, float sourcePitch, float finalYaw, float finalPitch) {
        TeleportData data = new TeleportData(position, flags, deltaMovement, transaction, teleportId, sourceYaw, sourcePitch, finalYaw, finalPitch);
        data.setSentWhileVehicle(player.compensatedEntities.vehicles.serverPlayerVehicle != null
                || player.compensatedEntities.getSelf().inVehicle());
        data.setSentDuringVehicleDismount(player.compensatedEntities.vehicles.hasPendingServerDismount());

        Vec3 safePosition = position;

        // We must convert relative teleports to avoid them becoming client controlled in the case of setback
        if (flags.isSet(RelativeFlag.X.getMask())) { safePosition = new Vec3(safePosition.x + lastKnownGoodPosition.getPos().x, safePosition.y, safePosition.z); }

        if (flags.isSet(RelativeFlag.Y.getMask())) { safePosition = new Vec3(safePosition.x, safePosition.y + lastKnownGoodPosition.getPos().y, safePosition.z); }

        if (flags.isSet(RelativeFlag.Z.getMask())) { safePosition = new Vec3(safePosition.x, safePosition.y, safePosition.z + lastKnownGoodPosition.getPos().z); }

        pendingTeleports.add(data);

        data = data.copyWithLocation(safePosition);
        this.requiredSetBack = new SetBackData(data, player.xRot, player.yRot, null, false, plugin);

        if (!player.inVehicle()) {
            this.lastKnownGoodPosition = new SetbackPosWithVector(safePosition, Vec3.ZERO, player.totalFlyingPacketsSent);
        }
    }

    public void addImmediatePlayerTeleport(Vec3 position, Vec3 deltaMovement, RelativeFlag flags,
                                           int proofTransaction,
                                           float sourceYaw, float sourcePitch, float finalYaw, float finalPitch) {
        // ClientboundTeleportEntityPacket has no teleport id. When the vanilla
        // client remaps a removed vehicle teleport onto the player, the following
        // PosRot is the only acknowledgement. Accept the exact echo immediately,
        // but keep the trailing proof transaction to detect an ignored echo.
        int transaction = proofTransaction < 0 ? Integer.MAX_VALUE : proofTransaction;
        TeleportData data = new TeleportData(
                position,
                flags,
                deltaMovement,
                transaction,
                0,
                sourceYaw,
                sourcePitch,
                finalYaw,
                finalPitch
        );
        data.setPositionOnly(true);
        pendingTeleports.addFirst(data);
    }

    public long addImmediateBedrockTransportTeleport(Vec3 physicalFeetPosition, boolean onGround) {
        long revision = bedrockTeleportRevision.incrementAndGet();
        Vec3 clamped = VectorUtils.clampVector(physicalFeetPosition);
        for (TeleportData pending : pendingTeleports) {
            if (!pending.isRotationOnly() && pending.getBedrockTransportRevision() < 0L) {
                pending.applyBedrockTransportBoundary(clamped, onGround, revision);
                return revision;
            }
        }

        // Grim's internal Java-side move can consume the Java queue entry
        // before Geyser writes the translated Bedrock packet. Packet order,
        // not coordinate proximity, associates the next outbound position
        // boundary with the still-pending required teleport.
        if (requiredSetBack != null && !requiredSetBack.isComplete()) {
            TeleportData requiredTeleport = requiredSetBack.getTeleportData();
            TeleportData data = requiredTeleport.copyWithLocation(clamped);
            data.applyBedrockTransportBoundary(clamped, onGround, revision);
            data.setBedrockTransportOnly(false);
            pendingTeleports.add(data);
            return revision;
        }

        // Geyser can author a client-visible position boundary without a Java
        // ClientboundPlayerPositionPacket. Keep that boundary in the normal
        // FIFO so the exact HANDLE_TELEPORT frame rebases Bedrock simulation,
        // but do not promote it to an authoritative rollback target. Only the
        // Java teleport paths above may own requiredSetBack/lastKnownGoodPosition.
        TeleportData data = new TeleportData(
                clamped,
                new RelativeFlag(0),
                Vec3.ZERO,
                player.lastTransactionSent.get(),
                0
        );
        data.setPositionOnly(true);
        data.setBedrockOnGround(onGround);
        data.setBedrockTransportOnly(true);
        data.setBedrockTransportRevision(revision);
        pendingTeleports.add(data);
        return revision;
    }

    public void addImmediatePlayerRotationTeleport(RelativeFlag flags, int proofTransaction,
                                                   float sourceYaw, float sourcePitch, float finalYaw, float finalPitch) {
        int transaction = proofTransaction < 0 ? Integer.MAX_VALUE : proofTransaction;
        TeleportData data = new TeleportData(
                Vec3.ZERO,
                flags,
                Vec3.ZERO,
                transaction,
                0,
                sourceYaw,
                sourcePitch,
                finalYaw,
                finalPitch
        );
        data.setRotationOnly(true);
        pendingTeleports.add(data);
        if (proofTransaction >= 0) {
            player.latencyUtils.addRealTimeTask(transaction, () -> {
                // A matching Rot response removes this entry in
                // checkRotationTeleportQueue. If it is still pending after
                // the trailing transaction, the client processed the forced
                // rotation without acknowledging it. This is the NMS-port
                // equivalent of the old pendingRotations BadPacketsB call.
                if (pendingTeleports.remove(data)) {
                    player.checkManager.getCheck(BadPacketsB.class).flag();
                }
            });
        }
    }

    @Override
    public void reload() {
        super.reload(); this.freeze = getConfig().getIntElse("max-freeze-time", 5000);
        this.debug = getConfig().getBooleanElse("debug-teleports", false);
    }


    // Every 60 ms we check if a player has taken knockback
    // We do 60 ms instead of 50 ms to try to mitigate any advantages gained by abusing this
    //
    // We will assume that velocity is more sensitive to not moving than general movement
    public void checkIfMustMove() { final EvictingQueue<Long> movementTimes = player.checkManager.getSimulationProcessor().getLastMovementTime();
        if (movementTimes.isEmpty()) return;
        if (player.inVehicle()) return;
        final KnockbackHandler knockback = player.checkManager.getKnockbackHandler();
        if (knockback.lastSent != null) return;

        final long lastMovement = Collections.max(movementTimes);
        final long currentTime = System.nanoTime();

        // If the player hasn't sent a movement in 1.5 seconds, setback (and send velocity to activate other part)
        final long elapsed = currentTime - lastMovement;
        final long freezeNanos = TimeUnit.NANOSECONDS.convert(freeze, TimeUnit.MILLISECONDS);
        if (elapsed > freezeNanos) {
            // if the player must move
            if (player.checkManager.getSimulationProcessor().tryToAchievePointThree(new Vec3(player.x, player.y, player.z), player.xRot, player.yRot).getFlags().isEmpty()) {
                return;
            }
            // This will activate the velocity part of this check
            final String latencyReason = "move, " + elapsed + TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms";
            executeTooHighLatencySetback(latencyReason);
        }
    }

}
