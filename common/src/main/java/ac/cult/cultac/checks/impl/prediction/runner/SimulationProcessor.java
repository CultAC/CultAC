package ac.cult.cultac.checks.impl.prediction.runner;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionDebug;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockSetbackJumpGuard;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.checks.impl.crash.CrashF;
import ac.cult.cultac.checks.impl.groundspoof.NoFallExecutor;
import ac.cult.cultac.checks.impl.prediction.AuthoredMovementFrame;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.PredictionResult.Flag;
import ac.cult.cultac.checks.impl.prediction.checks.PredCheckRunner;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.checks.impl.prediction.pipeline.MovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.MovementEngines;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfile;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.PositionListener;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.packet.NmsPacketUtil.MovePlayerData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.update.PositionUpdate;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import ac.cult.cultac.utils.anticheat.update.VehiclePositionUpdate;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.LastInstance;
import ac.cult.cultac.utils.data.HeadRotation;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.StuckSpeedData;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.lists.EvictingQueue;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.NmsBlockTags;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Generated;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

public class SimulationProcessor extends CultProcessor implements PositionListener, ClientTickEndListener {
   static long computeTimeNS = 300000L;
   private static final AtomicInteger flags = new AtomicInteger(0);
   private static final double TELEPORT_GROUND_UNCERTAIN_DISTANCE_SQ = 4.0E-8;
   private static final double TRAVEL_DEBUG_DISTANCE_SQR = 1.0;
   private static final double TRAVEL_FLAG_DISTANCE_SQR = 400.0;
   LastInstance lastTickSkip = new LastInstance(this.player);
   LastInstance lastOnGroundSkip = new LastInstance(this.player);
   EvictingQueue<Long> lastMovementTime = new EvictingQueue(3);
   boolean couldPotentiallyTickSkip = false;
   Set<Vec3> validPlayerStartingVels = new HashSet<>();
   PredictionResult lastPrediction = null;
   PredCheckRunner flagger = new PredCheckRunner(this.player);
   DesyncStatus lastOnGround = DesyncStatus.FALSE;
   LastInstance lastFlying = new LastInstance(this.player);
   boolean lastSneaking = false;
   float lastXRot = 0.0F;
   float lastYRot = 0.0F;
   boolean lastGliding = false;
   boolean lastMovementWasSetback = false;
   PredictionCarry profileCarry;
   private boolean bedrockSleepingStateObserved;
   private TeleportData bedrockTeleport;
   private PredictionSetbackState activeBedrockSetbackState;
   private final WorldStageBuilder worldStageBuilder = new WorldStageBuilder();

   public SimulationProcessor(CultPlayer playerData) {
      super(playerData);
   }

   public Set<Vec3> getValidPlayerStartingVels() {
      return this.validPlayerStartingVels;
   }

   public PredictionCommit getCurrentPredictionCommit() {
      return new PredictionCommit(this.profileCarry, this.validPlayerStartingVels);
   }

   public void applyAcknowledgedBedrockGliding(boolean gliding) {
      if (this.player.isBedrockMovement() && this.profileCarry != null) {
         this.profileCarry = MovementEngines.requireForProfile(MovementProfiles.forPlayer(this.player))
            .applyAcknowledgedGlidingToCarry(this.profileCarry, gliding);
      }
   }

   /** Apply on the player's packet thread at the acknowledged clientbound boundary. */
   public void applyAcknowledgedBedrockMetadata(Float width, Float height, Boolean gliding,
                                               Boolean crawling, Boolean swimming) {
      if (!this.player.isBedrockMovement() || this.player.bedrockState == null) {
         return;
      }
      this.player.bedrockState.applyAcknowledgedBoundingBoxMetadata(width, height);
      this.player.bedrockState.applyAcknowledgedPoseMetadata(crawling, swimming);
      if (gliding != null) {
         this.applyAcknowledgedBedrockGliding(gliding);
      }
   }

   public Set<Vec3> getLastStartingVelocitiesUsed() {
      return this.validPlayerStartingVels;
   }

   public Set<Vec3> getLastDerivedNextTickVelocities() {
      return this.validPlayerStartingVels;
   }

   public PredictionSetbackState getBedrockSetbackState() {
      if (this.activeBedrockSetbackState != null) {
         return this.activeBedrockSetbackState;
      } else {
         return this.player.isBedrockMovement() && this.profileCarry != null
            ? MovementEngines.requireForProfile(MovementProfiles.forPlayer(this.player))
               .captureSetbackState(new PredictionCommit(this.profileCarry, this.validPlayerStartingVels))
            : null;
      }
   }

   private boolean canSpeculatePointThreeFromMovementPacket() {
      return this.player.isBedrockMovement()
         ? false
         : this.player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9) && this.player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2);
   }

   private boolean isPassengerRotationOnlyTick(ServerboundMovePlayerPacket packet) {
      return packet instanceof Rot
         && (
            this.player.packetStateData.hasPassengerRotationThisClientTick()
               || this.player.compensatedEntities.getSelf().inVehicle()
               || this.player.compensatedEntities.vehicles.hasClientObservedServerVehicle()
               || this.player.compensatedEntities.vehicles.hasPlayerPassengerState()
         );
   }

   private boolean isPacketHandlerRotationTeleport(ServerboundMovePlayerPacket packet) {
      return packet instanceof Rot && this.player.packetStateData.lastPacketWasTeleport;
   }

   public void onPlayerTickEnd(PacketReceiveEvent event) {
      if (!this.player.compensatedEntities.vehicles.hasPlayerPassengerState() && !this.player.packetStateData.hasPassengerRotationThisClientTick()) {
         if (!this.player.isBedrockMovement()) {
            if (!this.player.packetStateData.receivedMovementThisClientTick && !this.player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) {
               PredictionResult result = this.advanceNoPositionClientTick(
                  new Vec3(this.player.x, this.player.y, this.player.z), this.player.xRot, this.player.yRot
               );
               if (this.isAcceptedNoPositionTick(result)) {
                  this.couldPotentiallyTickSkip = true;
               }
            }
            // Minecraft#tick runs piston block entities after LocalPlayer#tick
            // and its movement packet. Entity#move(PISTON) can change onGround
            // at that later boundary. Preserve the existing push approximation
            // in the carried ground state, including across handleMovePlayer's
            // position corrections, which do not assign Entity#onGround.
            // Do this after idle travel too, so it affects the following tick.
            if (this.currentPredictionVehicle() == null
               && this.player.compensatedWorld.pistons.mayHavePushedOnLastClientTick(
                  GetBoundingBox.getCollisionBoxForPlayer(this.player, this.player.x, this.player.y, this.player.z))) {
               this.lastOnGround = DesyncStatus.UNKNOWN;
            }
         }
      }
   }

   private void handleMovePlayer(ServerboundMovePlayerPacket packet) {
      if (!this.player.isBedrockMovement()) {
         MovePlayerData flying = NmsPacketUtil.readMovePlayer(packet, this.player);
         if (!flying.hasPositionChanged()) {
            if (!this.isPacketHandlerRotationTeleport(packet)) {
               if (!this.isPassengerRotationOnlyTick(packet)) {
                  this.player.onGround = flying.onGround();
                  PredictionResult result = this.advanceNoPositionClientTick(
                     new Vec3(this.player.x, this.player.y, this.player.z), this.player.xRot, this.player.yRot
                  );
                  boolean isPointThree = this.isAcceptedNoPositionTick(result);
                  if (isPointThree) {
                     this.couldPotentiallyTickSkip = true;
                  }

                  boolean didFlagNoFall = result.hasFlag(NoFallPseudo.class);
                  if (didFlagNoFall) {
                     Boolean desiredOnGround = result.getDesiredOnGround();
                     if (desiredOnGround != null) {
                        this.player.packetStateData.stageDesiredOnGround(desiredOnGround);
                     }

                     if (!result.isExempt()) {
                        ((NoFallExecutor)this.player.checkManager.getListener(NoFallExecutor.class)).flag("nopos,g=" + flying.onGround());
                     }
                  }

                  if (!didFlagNoFall || result.isExempt()) {
                     this.lastOnGround = DesyncStatus.fromBoolean(flying.onGround());
                     if (!this.lastOnGround.is(flying.onGround()) && this.lastPrediction != null) {
                        this.lastOnGroundSkip.reset();
                     }
                  }
               }
            }
         }
      }
   }

   @CultPacketHandler
   @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
   public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
      this.handleMovePlayer(packet);
   }

   public void onPositionUpdate(PositionUpdate positionUpdate) {
      if (positionUpdate.getTeleportData().isTeleport()) {
         this.handleTeleport(positionUpdate.getTeleportData());
         if (this.canSpeculatePointThreeFromMovementPacket() && !this.couldPotentiallyTickSkip) {
            this.couldPotentiallyTickSkip = this.tryToAchievePointThree(positionUpdate.getFrom(), this.player.xRot, this.player.yRot)
               .getFlags()
               .stream()
               .allMatch(flag -> flag.getCheck() instanceof NoFallPseudo);
         }
      } else {
         if (!this.player.isBedrockMovement()
            && (this.player.checkManager.getKnockbackHandler().hasPacketVelocity() || this.player.checkManager.getExplosionHandler().hasPacketVelocity())) {
            this.tryToAchievePointThree(new Vec3(this.player.x, this.player.y, this.player.z), this.player.xRot, this.player.yRot);
         }

         boolean packetOnGround = this.movementPacketOnGround(positionUpdate.isOnGround(), positionUpdate.getAuthoredMovementFrame());
         PredictionResult result = this.callPrediction(
            positionUpdate.getTo(), positionUpdate.getFrom(), packetOnGround, this.player.xRot, this.player.yRot, positionUpdate.getAuthoredMovementFrame()
         );
         boolean didFlagNoFall = result != null && !result.isExempt() && result.hasFlag(NoFallPseudo.class);
         if (didFlagNoFall) {
            Boolean desiredOnGround = result.getDesiredOnGround();
            if (desiredOnGround != null) {
               this.player.packetStateData.stageDesiredOnGround(desiredOnGround);
               this.lastOnGround = DesyncStatus.fromBoolean(desiredOnGround);
            } else {
               this.lastOnGround = DesyncStatus.fromBoolean(!positionUpdate.isOnGround());
            }
         } else {
            this.lastOnGround = DesyncStatus.fromBoolean(packetOnGround);
         }

         this.player.onGround = packetOnGround;
         boolean didSkip = this.canSpeculatePointThreeFromMovementPacket()
            && !this.couldPotentiallyTickSkip
            && this.tryToAchievePointThree(positionUpdate.getFrom(), this.player.xRot, this.player.yRot).getFlags().isEmpty();
         if (didSkip) {
            this.couldPotentiallyTickSkip = true;
         }
      }
   }

   private void handleTeleport(TeleportAcceptData teleportAcceptData) {
      TeleportData teleportData = teleportAcceptData.getTeleportData();
      this.player.compensatedWorld.pistons.onLegacyPlayerTeleport();
      PredictionResult result = new PredictionResult(this.player, null, null, null, teleportData, new ArrayList(), new ArrayList());
      result.setTeleport(true);
      this.player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(
         this.player, teleportData.getLocation().x, teleportData.getLocation().y, teleportData.getLocation().z
      );
      if (!this.player.compensatedEntities.getSelf().inVehicle()) {
         if (this.player.isBedrockMovement() || this.player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
            Set<Vec3> existingVectors = this.validPlayerStartingVels;
            this.validPlayerStartingVels = new HashSet<>();

            for (Vec3 vector : existingVectors) {
               this.validPlayerStartingVels.add(this.player.isBedrockMovement() ? Vec3.ZERO : teleportData.applyToVelocity(vector));
            }

            if (!teleportData.isRelativeDeltaX() && !teleportData.isRelativeDeltaY() && !teleportData.isRelativeDeltaZ()) {
               this.lastFlying.setRaw(20);
            }
         } else {
            Set<Vec3> existingVectors = this.validPlayerStartingVels;
            this.validPlayerStartingVels = new HashSet<>();
            ClientVersion serverVersion = ClientVersion.fromProtocolVersion(net.minecraft.SharedConstants.getProtocolVersion());
            for (Vec3 vector : existingVectors) {
               this.validPlayerStartingVels.add(ac.cult.cultac.utils.nmsutil.LegacyTeleportVelocity.apply(
                     this.player.getClientVersion(), serverVersion, teleportData, vector));
            }
            if (this.validPlayerStartingVels.isEmpty()) {
               this.validPlayerStartingVels.add(ac.cult.cultac.utils.nmsutil.LegacyTeleportVelocity.apply(
                     this.player.getClientVersion(), serverVersion, teleportData, Vec3.ZERO));
            }
            if (teleportData.isAbsolute() || this.player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) this.lastFlying.setRaw(20);
         }
      }

      PredictionCommit teleportCommit = null;
      if (this.player.isBedrockMovement()) {
         teleportCommit = MovementEngines.requireForProfile(MovementProfiles.forPlayer(this.player)).applyTeleportToCarry(this.profileCarry, teleportData);
      }

      this.callPredictionEndListeners(result, teleportCommit);
      // A transport-only acknowledgement can follow the actual Cult setback
      // before any Bedrock travel. It must not consume that setback's guard.
      if (!this.player.isBedrockMovement() || teleportAcceptData.getSetback() != null || !teleportData.isBedrockTransportOnly()) {
         this.lastMovementWasSetback = teleportAcceptData.getSetback() != null && !teleportAcceptData.getSetback().isPlugin();
      }
      this.couldPotentiallyTickSkip = false;
      if (!this.player.isBedrockMovement() && teleportAcceptData.getSetback() != null && teleportAcceptData.getSetback().getVelocity() != null) {
         this.validPlayerStartingVels.clear();
         this.validPlayerStartingVels.add(teleportAcceptData.getSetback().getVelocity());
      }

      if (this.player.isBedrockMovement()) {
         if (teleportCommit != null) {
            this.applyProfileCommit(teleportCommit);
         } else {
            this.profileCarry = null;
         }
      }

      // Bedrock actions and item-use clocks belong to the next actor tick.
      // Chunk filtering can defer that tick past the teleport acknowledgement.
      if (!this.player.isBedrockMovement()) {
         MovementProfiles.forPlayer(this.player).resetQueuedAuthoredInput(this.player);
      }
   }

   public void applyAcceptedJavaTeleport(TeleportAcceptData accepted) {
      if (accepted == null || !accepted.isTeleport() || accepted.getTeleportData() == null) return;
      this.handleTeleport(accepted);
      this.commitPlayerPosition(accepted.getTeleportData().getLocation());
   }

   public void applyAcceptedBedrockTeleport(TeleportAcceptData teleportAcceptData) {
      if (teleportAcceptData != null && teleportAcceptData.isTeleport() && teleportAcceptData.getTeleportData() != null) {
         this.handleTeleport(teleportAcceptData);
         TeleportData teleport = teleportAcceptData.getTeleportData();
         this.commitPlayerPosition(teleport.getLocation());
      }
   }

   public PredictionResult onVehiclePositionUpdate(VehiclePositionUpdate positionUpdate) {
      if (positionUpdate.getTeleportAcceptData().isTeleport()) {
         this.handleTeleport(positionUpdate.getTeleportAcceptData());
         return null;
      }

      if (!this.player.isBedrockMovement() && !positionUpdate.isHasOnGround()) {
         PacketEntity vehicle = this.currentPredictionVehicle();
         if (vehicle != null && (this.lastPrediction == null
         || this.lastPrediction.getSimulationContext().getVehicle() != vehicle)) {
            this.lastOnGround = DesyncStatus.fromBoolean(vehicle.onGround);
         }
      }

      PredictionResult result = this.callPrediction(
         positionUpdate.getTo(), positionUpdate.getFrom(), positionUpdate.isOnGround(), positionUpdate.getXRot(), positionUpdate.getYRot()
      );
      if (positionUpdate.isHasOnGround()) {
         this.lastOnGround = DesyncStatus.fromBoolean(positionUpdate.isOnGround());
      }

      return result;
   }

   @Nullable
   public PredictionResult processBedrockAuthInputFrame(BedrockAuthInputFrame frame, BedrockPredictionTrigger trigger) {
      return processBedrockAuthInputFrame(frame, trigger, null);
   }

   public PredictionResult processBedrockAuthInputFrame(BedrockAuthInputFrame frame, BedrockPredictionTrigger trigger, @Nullable TeleportData acceptedTeleport) {
      this.bedrockTeleport = acceptedTeleport;
      try {
         return processBedrockAuthInputTick(frame, trigger);
      } finally {
         this.bedrockTeleport = null;
      }
   }

   private PredictionResult processBedrockAuthInputTick(BedrockAuthInputFrame frame, BedrockPredictionTrigger trigger) {
      if (!this.player.isBedrockMovement()
         || this.player.bedrockState == null
         || this.player.compensatedEntities.vehicles.hasPlayerPassengerState()
         || frame == null) {
         return null;
      } else if (this.bedrockSleepingStateObserved) {
         this.recordProcessedBedrockAuthInputFrame(frame, trigger);
         this.applyBedrockImmobileState(frame);
         return null;
      } else {
         return this.processAuthoredInputFrame(frame, trigger);
      }
   }

   public void handleBedrockSleepingStateChange(boolean sleeping) {
      if (this.player.isBedrockMovement() && this.bedrockSleepingStateObserved != sleeping) {
         this.bedrockSleepingStateObserved = sleeping;
         if (sleeping) {
            this.player.packetStateData.clearBedrockTranslatedMovementPermit();
            this.applyBedrockImmobileState(null);
         }

         MovementProfiles.forPlayer(this.player).resetQueuedAuthoredInput(this.player);
      }
   }

   public boolean isBedrockSleepingStateObserved() {
      return this.bedrockSleepingStateObserved;
   }

   private void applyBedrockImmobileState(@Nullable BedrockAuthInputFrame frame) {
      MovementProfile movementProfile = MovementProfiles.forPlayer(this.player);
      SimulationContext context = null;
      if (frame != null) {
         Vec3 unchangedPosition = movementProfile.authoredPredictionStart(
            this.player, frame, new Vec3(this.player.x, this.player.y, this.player.z), this.profileCarry
         );
         float xRot = frame.hasRotation() ? frame.getYaw() : this.player.xRot;
         float yRot = frame.hasRotation() ? frame.getPitch() : this.player.yRot;
         context = this.createSimulationContext(
            this.player,
            this.lastPrediction,
            Vec3.ZERO,
            unchangedPosition,
            unchangedPosition,
            this.lastTickSkip,
            false,
            xRot,
            yRot,
            frame,
            movementProfile,
            false
         );
      }

      PredictionCommit immobileCommit = MovementEngines.requireForProfile(movementProfile)
         .applyImmobileStateToCarry(this.player, this.profileCarry, frame, context);
      if (immobileCommit == null) {
         this.validPlayerStartingVels.clear();
         this.validPlayerStartingVels.add(Vec3.ZERO);
      } else {
         this.applyProfileCommit(immobileCommit);
      }
   }

   @Nullable
   public PredictionResult processQueuedAuthoredInput() {
      MovementProfile movementProfile = MovementProfiles.forPlayer(this.player);
      if (!this.player.compensatedEntities.vehicles.hasPlayerPassengerState()
         && movementProfile.shouldProcessQueuedAuthoredInputWithoutPosition(this.player)
         && movementProfile.hasQueuedAuthoredInput(this.player)) {
         PredictionResult latestResult = null;
         int processed = 0;

         while (movementProfile.hasQueuedAuthoredInput(this.player) && processed++ < 32) {
            AuthoredMovementFrame frame = movementProfile.pollQueuedAuthoredInput(this.player);
            if (frame == null) {
               return latestResult;
            }

            PredictionResult result = this.processAuthoredInputFrame(frame);
            if (result != null) {
               latestResult = result;
            }
         }

         return latestResult;
      } else {
         return null;
      }
   }

   @Nullable
   public PredictionResult processQueuedAuthoredInputBefore(AuthoredMovementFrame boundaryFrame) {
      MovementProfile movementProfile = MovementProfiles.forPlayer(this.player);
      if (boundaryFrame != null && !this.player.compensatedEntities.vehicles.hasPlayerPassengerState() && movementProfile.hasQueuedAuthoredInput(this.player)) {
         PredictionResult latestResult = null;
         int processed = 0;

         while (processed++ < 32) {
            AuthoredMovementFrame frame = movementProfile.pollQueuedAuthoredInputBefore(this.player, boundaryFrame);
            if (frame == null) {
               return latestResult;
            }

            if (movementProfile.shouldProcessQueuedAuthoredInputBeforeBoundary(this.player, boundaryFrame)) {
               PredictionResult result = this.processAuthoredInputFrame(frame);
               if (result != null) {
                  latestResult = result;
               }
            }
         }

         return latestResult;
      } else {
         return null;
      }
   }

   @Nullable
   private PredictionResult processAuthoredInputFrame(AuthoredMovementFrame frame) {
      return this.processAuthoredInputFrame(frame, null);
   }

   @Nullable
   private PredictionResult processAuthoredInputFrame(AuthoredMovementFrame frame, BedrockPredictionTrigger trigger) {
      if (frame == null) {
         return null;
      }

      Vec3 currentPosition = frame.getPosition();
      if (currentPosition == null) {
         return null;
      }

      this.recordProcessedBedrockAuthInputFrame(frame, trigger);
      MovementProfile movementProfile = MovementProfiles.forPlayer(this.player);
      Vec3 previousPosition = movementProfile.authoredPredictionStart(
         this.player, frame, new Vec3(this.player.x, this.player.y, this.player.z), this.profileCarry
      );
      boolean onGround = this.movementPacketOnGround(this.player.onGround, frame);
      float xRot = frame.hasRotation() ? frame.getYaw() : this.player.xRot;
      float yRot = frame.hasRotation() ? frame.getPitch() : this.player.yRot;
      PredictionResult result = this.callPrediction(currentPosition, previousPosition, onGround, xRot, yRot, frame);
      if (result != null && (!this.player.isBedrockMovement() || !this.player.getSetbackTeleportUtil().isPendingSetback())) {
         boolean canonicalGround = canonicalBedrockGround(result, onGround);
         if (trigger == BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE && frame instanceof BedrockAuthInputFrame bedrockFrame) {
            this.player.packetStateData.grantBedrockTranslatedMovementPermit(bedrockFrame.getClientTick(), bedrockFrame.isProjectedOnGround(), canonicalGround);
         }

         this.lastOnGround = DesyncStatus.fromBoolean(canonicalGround);
         this.player.onGround = canonicalGround;
         this.commitAuthoredInputPlayerState(frame, currentPosition, xRot, yRot);
      }

      return result;
   }

   private boolean movementPacketOnGround(boolean fallback, AuthoredMovementFrame frame) {
      return frame instanceof BedrockAuthInputFrame ? fallback : fallback;
   }

   private static boolean canonicalBedrockGround(PredictionResult result, boolean fallback) {
      BedrockPredictionResult bedrockResult = (BedrockPredictionResult)result.getProfileResult(BedrockPredictionResult.class);
      return bedrockResult != null && bedrockResult.nextTickBaseState() != null ? bedrockResult.nextTickBaseState().movementGrounded() : fallback;
   }

   private void recordProcessedBedrockAuthInputFrame(AuthoredMovementFrame frame, BedrockPredictionTrigger trigger) {
      if (this.player.bedrockState != null && frame instanceof BedrockAuthInputFrame bedrockFrame) {
         this.player.bedrockState.recordProcessedAuthInputFrame(bedrockFrame, trigger);
      }
   }

   private void commitAuthoredInputPlayerState(AuthoredMovementFrame frame, Vec3 position, float xRot, float yRot) {
      this.commitPlayerPosition(position);
      if (frame.hasRotation()) {
         HeadRotation from = new HeadRotation(this.player.xRot, this.player.yRot);
         if (this.player.xRot != xRot || this.player.yRot != yRot) {
            this.player.lastTickXRot = this.player.xRot;
            this.player.lastTickYRot = this.player.yRot;
         }

         this.player.xRot = xRot;
         this.player.yRot = yRot;
         RotationUpdate update = new RotationUpdate(
                 from,
                 new HeadRotation(this.player.xRot, this.player.yRot),
                 this.player.xRot - from.yaw(),
                 this.player.yRot - from.pitch()
         );
         if (update.getDeltaXRot() != 0 || update.getDeltaYRot() != 0) {
            this.player.lastRotated = System.currentTimeMillis();
         }
         this.player.checkManager.onRotationUpdate(update);
      }
   }

   private void commitPlayerPosition(Vec3 position) {
      Vec3 clampedPosition = VectorUtils.clampVector(position);
      this.player.lastX = this.player.x;
      this.player.lastY = this.player.y;
      this.player.lastZ = this.player.z;
      this.player.x = clampedPosition.x;
      this.player.y = clampedPosition.y;
      this.player.z = clampedPosition.z;
      this.player.packetStateData.clientSidePosition = clampedPosition;
      this.player.getMovementData().handle(clampedPosition.x, clampedPosition.y, clampedPosition.z);
   }

   private boolean movementStartOnGround(boolean packetOnGround) {
      PacketEntity vehicle = this.player.compensatedEntities.vehicles.getVelocityMovementVehicle();
      if (vehicle == null) {
         return packetOnGround;
      } else if (this.player.packetStateData.isVehicleMovementFromClientTick()) {
         return this.player.packetStateData.vehicleMovementOnGroundPresent
            ? this.player.packetStateData.vehicleMovementStartOnGround
            : this.lastOnGround.determineOptimistically();
      } else {
         return packetOnGround;
      }
   }

   @Nullable
   public PredictionResult callPrediction(Vec3 to, Vec3 from, boolean onGround, float xRot, float yRot) {
      return this.callPrediction(to, from, onGround, xRot, yRot, null);
   }

   @Nullable
   public PredictionResult callPrediction(Vec3 to, Vec3 from, boolean onGround, float xRot, float yRot, AuthoredMovementFrame authoredMovementFrame) {
      this.activeBedrockSetbackState = null;
      long start = System.nanoTime();
      this.lastMovementTime.add(start);
      Vec3 diff = to.subtract(from);
      double length = diff.lengthSqr();
      if (length > 1.0) {
         Check crashCheck = (Check)this.player.checkManager.getListener(CrashF.class);
         crashCheck.debug(() -> "length=" + length);
      }

      if (length > 400.0) {
         if (this.player.getSetbackTeleportUtil().hasFullyLoaded) {
            Check crashCheck = (Check)this.player.checkManager.getListener(CrashF.class);
            crashCheck.flag("len " + diff.length());
         }

         this.player.getSetbackTeleportUtil().executeViolationSetback();
         return null;
      } else {
         boolean predictionOnGround = this.movementStartOnGround(onGround);
         this.player.onGround = predictionOnGround;
         MovementProfile movementProfile = MovementProfiles.forPlayer(this.player);
         PredictionResult result = this.callPrediction(diff, from, to, false, xRot, yRot, authoredMovementFrame, movementProfile);
         if (result.getSimulationContext() != null && result.getSimulationContext().getVehicle() != null) {
            this.player.onGround = onGround;
         }

         this.handlePossibleRealities(result);
         if (this.player.isBedrockMovement()) {
            this.activeBedrockSetbackState = MovementEngines.requireForProfile(movementProfile).prepareSetbackState(this.player, result);
         }

         boolean desync = !result.isExempt() && this.isDesync(result);
         if (desync) {
            SetbackTeleportUtil setbackUtil = this.player.getSetbackTeleportUtil();
            if (setbackUtil.isPendingSetback()) {
               setbackUtil.blockOffsets = true;
            }

            setbackUtil.executeForceResync("sim desync");
            result.exempt();
            this.lastOnGround = DesyncStatus.fromBoolean(onGround);
         }

         boolean createProfileVerboseLog = movementProfile.shouldCreateVerboseLog(this.player, result);
         boolean recordProfileDebug = BedrockPredictionDebug.shouldRecordMovementDebug(this.player, result);
         this.player.compensatedEntities.vehicles.consumeVehicleSwitchPredictionOffset(result);
         if (!result.isExempt() && result.getFlagSeverity() > 0.0) {
            this.dumpRecentFlagsWhenDebugging(result);
            this.player.getSetbackTeleportUtil().resyncWorld();
            boolean bedrockMovementFlag = this.player.isBedrockMovement() && result.hasFlag(BedrockMovement.class);
            if (this.player.shouldEnforceMovementSetbacks() || createProfileVerboseLog || bedrockMovementFlag) {
               result.setIdentifier(this.nextDebugIdentifier());
               result.setProfileVerboseLog(createProfileVerboseLog);
            }
         }

         if (result.getIdentifier() == 0 && (createProfileVerboseLog || recordProfileDebug)) {
            result.setIdentifier(this.nextDebugIdentifier());
            result.setProfileVerboseLog(createProfileVerboseLog);
         }

         PredictionCommit preparedCommit = this.prepareBedrockCommit(result, diff, movementProfile);
         this.callPredictionEndListeners(result, preparedCommit);
         if (!this.player.isBedrockMovement()
            && this.player.bukkitPlayer != null
            && this.player.isGliding
            && result.getInitialStartingVel().hasReason("Jumping sprinting")
            && !CultAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("exploit.allow-sprint-jumping-when-using-elytra", true)) {
            SetbackPosWithVector lastKnownGoodPosition = this.player.getSetbackTeleportUtil().lastKnownGoodPosition;
            lastKnownGoodPosition.setVector(lastKnownGoodPosition.getVector().multiply(new Vec3(0.546, 1.0, 0.546)));
            this.player.getSetbackTeleportUtil().executeNonSimulatingSetback();
         }

         this.commitPredictionState(result, to, diff, xRot, yRot, movementProfile, preparedCommit);
         this.activeBedrockSetbackState = null;
         long took = System.nanoTime() - start;
         computeTimeNS = (long)(computeTimeNS * 499L / 500.0 + took / 500.0);
         return result;
      }
   }

   private PredictionCommit prepareBedrockCommit(PredictionResult result, Vec3 acceptedDiff, MovementProfile movementProfile) {
      return this.player.isBedrockMovement()
            && result != null
            && result.getSimulationContext() != null
            && result.getSimulationContext().hasTrustedAuthoredInput()
         ? MovementEngines.requireForProfile(movementProfile).commitNextTick(this.player, result, this.lastPrediction, acceptedDiff, this.profileCarry)
         : null;
   }

   private void commitPredictionState(PredictionResult result, Vec3 to, Vec3 diff, float xRot, float yRot, MovementProfile movementProfile) {
      this.commitPredictionState(result, to, diff, xRot, yRot, movementProfile, null);
   }

   private void commitPredictionState(
      PredictionResult result, Vec3 to, Vec3 diff, float xRot, float yRot, MovementProfile movementProfile, PredictionCommit preparedCommit
   ) {
      if (preparedCommit == null || !this.player.isBedrockMovement() || !this.player.getSetbackTeleportUtil().isPendingSetback()) {
         if (!this.player.isBedrockMovement() || BedrockSetbackJumpGuard.advancesTravel(result)) {
            this.lastMovementWasSetback = false;
         }
         this.validPlayerStartingVels.clear();
         PredictionCommit commit = preparedCommit == null
            ? MovementEngines.requireForProfile(movementProfile).commitNextTick(this.player, result, this.lastPrediction, diff, this.profileCarry)
            : preparedCommit;
         this.applyProfileCommit(commit);
         this.lastPrediction = result;
         this.lastXRot = xRot;
         this.lastYRot = yRot;
         this.player.minPlayerAttackSlow = 0;
         this.player.maxPlayerAttackSlow = 0;
         if (this.player.isFlying) {
            this.lastFlying.reset();
         }

         this.lastSneaking = this.player.isSneaking;
         this.lastGliding = this.player.isGliding;
         this.player.packetStateData.riptideLevel = 0;
         this.player.riptideSpinAttackTicks = Math.max(0, this.player.riptideSpinAttackTicks - 1);
         this.couldPotentiallyTickSkip = false;
         this.player.compensatedEntities.fishingRodPulls.clear();
         this.player.refreshPlayerPose();
      } else {
         PredictionCommit rejectedCommit = MovementEngines.requireForProfile(movementProfile).commitRejectedTick(result, this.profileCarry);
         if (rejectedCommit != null) {
            this.applyProfileCommit(rejectedCommit);
         }
      }
   }

   private void applyProfileCommit(PredictionCommit commit) {
      if (commit == null) {
         this.validPlayerStartingVels.clear();
         this.profileCarry = null;
      } else {
         this.validPlayerStartingVels = new HashSet<>(commit.startingVelocities());
         this.profileCarry = commit.carry();
      }
   }

   public void seedVehicleStartingVelocity(Vec3 velocity) {
      this.seedStartingVelocity(velocity);
   }

   public void seedStartingVelocity(Vec3 velocity) {
      this.seedStartingVelocities(List.of(velocity));
   }

   public void seedStartingVelocities(Collection<Vec3> velocities) {
      this.validPlayerStartingVels = new HashSet<>(velocities);
   }

   private int nextDebugIdentifier() {
      return flags.getAndIncrement() % 128 + 1;
   }

   private void handlePossibleRealities(PredictionResult result) {
      SimpleCollisionBox valid = null;
      if (this.lastPrediction != null) {
         valid = this.lastPrediction.getRealitiesExtent();
         if (valid != null) {
            float blockFriction = BlockProperties.getMaterialFriction(result.getSimulationContext().getWorldData().getOnBlock());
            double trueFriction = result.getSimulationContext().getLastOnGround().determinePessimistically() ? blockFriction * 0.91 : 0.91;
            if (result.getSimulationContext().getWorldData().getInLava().determinePessimistically()) {
               trueFriction = 0.5;
            }

            if (result.getSimulationContext().getWorldData().getInWater().determineOptimistically()) {
               trueFriction = 0.96;
            }

            if (result.getSimulationContext().usesFallFlyingMovement()) {
               trueFriction = 0.99;
            }

            valid = new SimpleCollisionBox(valid.minX * trueFriction, 0.0, valid.minZ * trueFriction, valid.maxX * trueFriction, 0.0, valid.maxZ * trueFriction);
         }
      }

      for (PredictionResult reality : result.getRealities()) {
         if (reality.getInitialStartingVel().packetModifiersLength() != 0) {
            SimpleCollisionBox knockbackVel = new SimpleCollisionBox(reality.getInitialStartingVel(), reality.getInitialStartingVel());
            if (valid == null) {
               valid = knockbackVel;
            } else {
               valid = valid.union(knockbackVel);
            }
         }

         if (reality.getInitialStartingVel().isTickSkip() && reality.getFlagSeverity() == 0.0) {
            this.lastTickSkip.reset();
         }
      }

      result.setRealitiesExtent(valid);
   }

   public PredictionResult tryToAchievePointThree(Vec3 withPos, float xRot, float yRot) {
      return this.callPrediction(Vec3.ZERO, withPos, withPos, true, xRot, yRot, null, MovementProfiles.forPlayer(this.player));
   }

   private PredictionResult advanceNoPositionClientTick(Vec3 position, float xRot, float yRot) {
      // Grim 3.0 keeps pre-tick-end clients anchored to their last reported
      // position. The next position packet includes their unreported movement;
      // committing zero here destroys that velocity/position relationship.
      // 1.8 supplies a status/rotation packet every tick, so this probe (rather
      // than speculative skipped packets) establishes its PointThree candidate.
      if (!this.player.supportsEndTick()) {
         return this.tryToAchievePointThree(position, xRot, yRot);
      }
      MovementProfile movementProfile = MovementProfiles.forPlayer(this.player);
      PredictionResult result = this.callPrediction(Vec3.ZERO, position, position, true, xRot, yRot, null, movementProfile);
      this.handlePossibleRealities(result);
      this.player.compensatedEntities.vehicles.consumeVehicleSwitchPredictionOffset(result);
      if (result.getFlags().isEmpty() && !result.isExempt()) {
         this.callPredictionEndListeners(result, null);
         this.commitPredictionState(result, position, Vec3.ZERO, xRot, yRot, movementProfile);
      }

      return result;
   }

   private boolean isAcceptedNoPositionTick(PredictionResult result) {
      return result.getFlags().isEmpty() || result.isExempt();
   }

   private PredictionResult callPrediction(
      Vec3 target, Vec3 from, Vec3 to, boolean testing, float xRot, float yRot, AuthoredMovementFrame authoredMovementFrame, MovementProfile movementProfile
   ) {
      PredictionResult result = this.getPredictionResult(
         this.player,
         this.validPlayerStartingVels,
         this.lastPrediction,
         target,
         from,
         to,
         this.lastTickSkip,
         testing,
         xRot,
         yRot,
         authoredMovementFrame,
         movementProfile
      );
      if (this.isExempt(movementProfile, result.getSimulationContext())) {
         result.exempt();
      }

      return result;
   }

   private boolean isDesync(PredictionResult result) {
      if (this.lastMovementWasSetback && result.getInitialStartingVel().isJump()) {
         boolean onGround = this.player.isBedrockMovement()
            ? BedrockSetbackJumpGuard.hasSupportAtStart(this.player, result)
            : Collisions.collide(this.player, 0.0, -1.0E-7, 0.0).y == 0.0;
         if (!onGround) {
            this.logDesyncTrace("T0");
            return true;
         }
      }

      if (this.player.getSetbackTeleportUtil().tooFarFromUnloadedChunk()) {
         this.logDesyncTrace("T1");
         return true;
      }

      if (this.player.getSetbackTeleportUtil().blockOffsets) {
         SetbackTeleportUtil setbackUtil = this.player.getSetbackTeleportUtil();
         boolean awaitingSetback = setbackUtil.isPendingSetback();
         if (!awaitingSetback) {
            setbackUtil.executeForceResync("offsets");
            this.logDesyncTrace("T2 | " + awaitingSetback);
         }

         return true;
      } else {
         return false;
      }
   }

   private void logDesyncTrace(String marker) {
      if (this.player.getSetbackTeleportUtil().isDebug()) {
         LogUtil.warn(this.player.getName() + " : " + marker);
      }
   }

   private void dumpRecentFlagsWhenDebugging(PredictionResult prediction) {
      if (this.player.getSetbackTeleportUtil().isDebug()) {
         LogUtil.info("Recent flags: (" + prediction.getFlagSeverity() + ") ");

         for (Flag entry : prediction.getFlags()) {
            String verboseText = entry.getVerbose().getString();
            LogUtil.info(entry.getCheck().getCheckName() + ": severity=" + entry.getSeverity() + " verbose=" + verboseText);
         }
      }
   }

   private void callPredictionEndListeners(PredictionResult result) {
      this.callPredictionEndListeners(result, null);
   }

   private void callPredictionEndListeners(PredictionResult result, PredictionCommit preparedCommit) {
      this.player.compensatedEntities.vehicles.consumeVehicleSwitchPredictionOffset(result);
      PredictionComplete complete = new PredictionComplete(result, preparedCommit);
      this.player.checkManager.onPredictionFinish(complete);
   }

   public boolean isExempt() {
      MovementProfile movementProfile = MovementProfiles.forPlayer(this.player);
      return this.isExempt(movementProfile, null);
   }

   private boolean isExempt(MovementProfile movementProfile, SimulationContext context) {
      boolean flyingExempt = movementProfile.usesFlyingExemption(this.player)
         && (this.player.isFlying || this.lastFlying.hasOccurredSince(2) || movementProfile.startsFlyingExemptionThisFrame(this.player, context));
      return !this.player.isBedrockMovement() && this.player.isInBed
         || flyingExempt
         || this.player.getSetbackTeleportUtil().shouldBlockMovement()
         || this.player.gamemode == GameMode.SPECTATOR;
   }

   public void handleRespawn() {
      this.lastOnGround = DesyncStatus.FALSE;
      this.lastFlying.setRaw(20);
      this.lastSneaking = false;
      this.lastGliding = false;
      this.bedrockSleepingStateObserved = false;
      this.profileCarry = null;
      this.player.packetStateData.clearBedrockTranslatedMovementPermit();
      if (this.player.bedrockState != null) {
         this.lastMovementWasSetback = false;
         this.player.bedrockState.clearMovementInputState();
      }

      MovementProfiles.forPlayer(this.player).resetQueuedAuthoredInput(this.player);
   }

   public PredictionResult getPredictionResult(
      CultPlayer player,
      Set<Vec3> startingVel,
      PredictionResult lastPrediction,
      Vec3 target,
      Vec3 start,
      Vec3 end,
      LastInstance lastTickSkip,
      boolean testing,
      float xRot,
      float yRot
   ) {
      return this.getPredictionResult(
         player, startingVel, lastPrediction, target, start, end, lastTickSkip, testing, xRot, yRot, null, MovementProfiles.forPlayer(player)
      );
   }

   public PredictionResult getPredictionResult(
      CultPlayer player,
      Set<Vec3> startingVel,
      PredictionResult lastPrediction,
      Vec3 target,
      Vec3 start,
      Vec3 end,
      LastInstance lastTickSkip,
      boolean testing,
      float xRot,
      float yRot,
      AuthoredMovementFrame authoredMovementFrame,
      MovementProfile movementProfile
   ) {
      List<PredictionResult> alternativeRealities = new ArrayList<>();
      List<PredictionResult> invalidOrValidRealities = new ArrayList<>();
      List<ValidMovements> valid = new ArrayList<>();
      MovementEngine engine = MovementEngines.requireForProfile(movementProfile);
      Map<CollisionProbeKey, CollideAxisData> collisionProbeCache = new HashMap<>();

      for (SimulationContext curSimulationContext : this.createSimulationContexts(
         player, lastPrediction, target, start, end, lastTickSkip, testing, xRot, yRot, authoredMovementFrame, movementProfile
      )) {
         List<PredVector> contextPacketStart = engine.applyModifiers(
            new MovementModifiers(), player, startingVel, curSimulationContext, lastPrediction, this.couldPotentiallyTickSkip
         );
         List<PredVector> validInitialVelocities = engine.startingVelocities(
            new VelocityTransformer(curSimulationContext), player, contextPacketStart, curSimulationContext
         );
         SimulationContext sortContext = curSimulationContext;
         validInitialVelocities.sort(
            Comparator.comparing(PredVector::tickSkippingComparator)
               .thenComparing(
                  Comparator.comparing(PredVector::packetModifiersLength).thenComparing(predVector -> predVector.distanceToSqr(sortContext.getTarget()))
               )
         );

         for (PredVector initialStartingVel : validInitialVelocities) {
            PredictionResult thisResult = new PredictionResult(
               player, initialStartingVel, curSimulationContext, curSimulationContext.getTarget(), null, alternativeRealities, invalidOrValidRealities
            );
            ValidMovements validMovement = engine.createValidMovements(thisResult.getInitialStartingVel(), player, thisResult, false)
               .computeExtents(lastPrediction);
            valid.add(validMovement);
         }
      }

      PredictionResult bestResult = null;

      for (ValidMovements validMovements : valid) {
         boolean allowStep = false;

         do {
            PredictionResult thisResult = validMovements.getResult();
            SimpleCollisionBox attemptedExtents = validMovements.getCollisionIgnoredMaxStartingVelExtents();
            boolean useSelectedMovement = engine == JavaMovementEngine.INSTANCE && !validMovements.isCanStep()
               && !thisResult.getSimulationContext().isTestingPointThree();
            Vec3 collisionTarget = useSelectedMovement
               ? validMovements.computeCollisionIgnoredMovement(lastPrediction)
               : thisResult.getSimulationContext().getTarget();
            CollideAxisData collisionData = probeCollisionsCached(
               player, engine, thisResult, attemptedExtents, collisionTarget, validMovements.isCanStep(), collisionProbeCache
            );

            thisResult.setCollideAxisData(collisionData);
            if (useSelectedMovement) {
               validMovements.applyCollisionsToSelectedMovement(lastPrediction);
            } else {
               validMovements.computeClosest(lastPrediction);
            }
            engine.evaluateCandidate(player, thisResult);
            this.flagger.handleResult(thisResult, lastPrediction);
            bestResult = engine.isBetterCandidate(thisResult, bestResult) ? thisResult : bestResult;
            if (thisResult.getFlagSeverity() == 0.0) {
               alternativeRealities.add(thisResult);
            }

            invalidOrValidRealities.add(thisResult);
            if (allowStep) {
               break;
            }

            CollideAxisData probedCollisions = thisResult.getCollideAxisData();
            allowStep = thisResult.getInitialStartingVel().maxUpStep(player) > 0.0
               && (
                  thisResult.getSimulationContext().getLastOnGround().determineOptimistically()
                     || probedCollisions.getYNeg() != null
                        && probedCollisions.getYNeg().isLikelyCollide()
                        && probedCollisions.getYNeg().getResult() + 0.001 > thisResult.getValidMovements().getCollisionIgnoredMaxStartingVelExtents().minY
               );
            if (allowStep) {
               // Entity#collide tests horizontal obstruction on the move before
               // stepping. A landing-to-step packet can end above that obstacle.
               // Preserve the lower probe for step discovery only; its contacts
               // must not replace the ordinary move's collision/velocity state.
               CollideAxisData stepEligibility = probedCollisions;
               if (engine == JavaMovementEngine.INSTANCE && !stepEligibility.couldCollideHorizontally()) {
                  stepEligibility = probeCollisionsCached(player, engine, thisResult, attemptedExtents,
                     thisResult.getSimulationContext().getTarget(), true, collisionProbeCache);
               }
               allowStep = stepEligibility.couldCollideHorizontally();
            }
            if (allowStep) {
               thisResult = new PredictionResult(
                  player,
                  thisResult.getInitialStartingVel(),
                  thisResult.getSimulationContext(),
                  thisResult.getSimulationContext().getTarget(),
                  null,
                  alternativeRealities,
                  invalidOrValidRealities
               );
               validMovements = engine.createValidMovements(thisResult.getInitialStartingVel(), player, thisResult, false).computeExtents(lastPrediction);
               validMovements.setCanStep(true);
            }
         } while (allowStep);
      }

      if (!alternativeRealities.contains(bestResult)) {
         alternativeRealities.add(bestResult);
      }

      assert bestResult != null;
      movementProfile.evaluatePredictionResult(player, bestResult);
      return bestResult;
   }

   private CollideAxisData probeCollisionsCached(
      CultPlayer player, MovementEngine engine, PredictionResult result, SimpleCollisionBox attemptedExtents,
      Vec3 collisionTarget, boolean canStep, Map<CollisionProbeKey, CollideAxisData> cache
   ) {
      SimulationContext context = result.getSimulationContext();
      CollisionProbeKey key = new CollisionProbeKey(
         context, attemptedExtents.minY, collisionTarget, context.getStart(), result.getInitialStartingVel(), attemptedExtents, canStep
      );
      CollideAxisData collisions = cache.get(key);
      if (collisions == null) {
         collisions = engine.probeCollisions(
            new CollisionModifier(), player, context, attemptedExtents.minY, collisionTarget, context.getStart(),
            result.getInitialStartingVel(), attemptedExtents, canStep
         );
         cache.put(key, collisions);
      }
      return collisions;
   }

   private List<SimulationContext> createSimulationContexts(
      CultPlayer player,
      PredictionResult lastPrediction,
      Vec3 target,
      Vec3 start,
      Vec3 end,
      LastInstance lastTickSkip,
      boolean testing,
      float xRot,
      float yRot,
      AuthoredMovementFrame authoredMovementFrame,
      MovementProfile movementProfile
   ) {
      if (!player.isBedrockMovement() && JavaMovementEngine.supportsExactEffects(player.getClientVersion())
            && this.profileCarry instanceof ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry carry
            && !carry.states().isEmpty()
            && carry.actor() == (currentPredictionVehicle() == null ? player.compensatedEntities.getSelf() : currentPredictionVehicle())) {
         List<SimulationContext> contexts = new ArrayList<>();
         for (var state : carry.states()) {
            SimulationContext context = this.createSimulationContext(player, lastPrediction, target, start, end,
                  lastTickSkip, testing, xRot, yRot, authoredMovementFrame, movementProfile, false);
            context.setProfileCarry(new ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry(
                  carry.actor(), state.fallDistance(), List.of(state)));
            Vec3 stuck = player.boatData.adjustStuckSpeedMultiplierForCurrentTick(player, context, state.stuckSpeed());
            context.setRequiredCurrentMoveStuckSpeed(stuck);
            context.handleLastRequiredStuckSpeed(stuck);
            contexts.add(context);
         }
         return contexts;
      }
      boolean carryLastRequiredStuckSpeed = movementProfile.usesJavaEntityMoveStuckSpeed(player) && this.shouldCarryLastRequiredStuckSpeed(lastPrediction);
      SimulationContext primary = this.createSimulationContext(
         player, lastPrediction, target, start, end, lastTickSkip, testing, xRot, yRot, authoredMovementFrame, movementProfile, carryLastRequiredStuckSpeed
      );
      return List.of(primary);
   }

   private boolean shouldCarryLastRequiredStuckSpeed(PredictionResult lastPrediction) {
      if (lastPrediction != null && lastPrediction.getSimulationContext() != null) {
         PacketEntity lastVehicle = lastPrediction.getSimulationContext().getVehicle();
         PacketEntity currentVehicle = this.currentPredictionVehicle();
         return lastVehicle != null && currentVehicle != null ? lastVehicle.getEntityId() == currentVehicle.getEntityId() : lastVehicle == currentVehicle;
      } else {
         return false;
      }
   }

   private SimulationContext createSimulationContext(
      CultPlayer player,
      PredictionResult lastPrediction,
      Vec3 target,
      Vec3 start,
      Vec3 end,
      LastInstance lastTickSkip,
      boolean testing,
      float xRot,
      float yRot,
      AuthoredMovementFrame authoredMovementFrame,
      MovementProfile movementProfile,
      boolean applyLastRequiredStuckSpeed
   ) {
      PacketEntity riding = this.currentPredictionVehicle();
      Vec3 vehicleInputs = new Vec3(player.boatData.vehicleHoriz, 0.0, player.boatData.vehicleForward);
      SimulationContext context = new SimulationContext(
         start,
         end,
         target,
         player.getClientVersion(),
         player.trigHandler,
         player.compensatedEntities,
         riding,
         xRot,
         this.lastXRot,
         yRot,
         this.lastYRot,
         player.onGround,
         player.isSneaking,
         this.lastSneaking,
         riding == null && player.isGliding,
         player.compensatedEntities.getPotionLevelForPlayer(PotionEffectType.JUMP_BOOST),
         this.getDepthStriderLevel(),
         this.getSwiftSneakLevel(),
         riding != null ? DesyncStatus.FALSE : DesyncStatus.UNKNOWN,
         player.minPlayerAttackSlow,
         player.maxPlayerAttackSlow,
         player.packetStateData.riptideLevel,
         lastTickSkip,
         testing,
         vehicleInputs,
         player.packetStateData.clientMovementInputKnown,
         player.packetStateData.clientMovementInput,
         lastPrediction == null ? new MainSupportingBlockData(null, false) : lastPrediction.getSimulationContext().getWorldData().getMainSupportingBlockPos(),
         player.pose,
         player.getScale()
      );
      context.setProfileCarry(this.profileCarry);
      context.setBedrockTeleport(player.isBedrockMovement() ? this.bedrockTeleport : null);
      context = movementProfile.createContext(player, context, authoredMovementFrame);
      DesyncStatus contextLastOnGround = riding == null
         ? this.lastOnGround
         : (
            player.packetStateData.vehicleMovementOnGroundPresent
               ? DesyncStatus.fromBoolean(player.packetStateData.vehicleMovementStartOnGround)
               : this.lastOnGround
         );
      MovementEngine engine = MovementEngines.requireForProfile(movementProfile);
      context.setWorldData(engine.buildWorld(this.worldStageBuilder, player, context, lastPrediction, contextLastOnGround));
      if (applyLastRequiredStuckSpeed && lastPrediction != null) {
         Vec3 lastRequiredStuckSpeed = lastPrediction.getSimulationContext().getWorldData().getStuckSpeed().getStuckSpeedMultiplier();
         Vec3 requiredCurrentMoveStuckSpeed = player.boatData.adjustStuckSpeedMultiplierForCurrentTick(player, context, lastRequiredStuckSpeed);
         context.setRequiredCurrentMoveStuckSpeed(requiredCurrentMoveStuckSpeed);
         context.handleLastRequiredStuckSpeed(requiredCurrentMoveStuckSpeed);
      }

      return context;
   }

   @Nullable
   private PacketEntity currentPredictionVehicle() {
      PacketEntity riding = this.player.compensatedEntities.getSelf().getRiding();
      if (riding != null) {
         return riding;
      } else {
         return !this.player.packetStateData.isVehicleMovementFromClientTick() ? null : this.player.compensatedEntities.vehicles.getVelocityMovementVehicle();
      }
   }

   private int getSwiftSneakLevel() {
      if (this.player.getClientVersion().isOlderThan(ClientVersion.V_1_19)) {
         return 0;
      }

      ItemStack leggings = this.player.getInventory().getLeggings();
      return !leggings.isEmpty() && !leggings.getEnchantments().isEmpty() ? leggings.getEnchantmentLevel(Enchantment.SWIFT_SNEAK) : 0;
   }

   private float getDepthStriderLevel() {
      if (this.player.getClientVersion().isOlderThan(ClientVersion.V_1_8)) {
         return 0.0F;
      }

      ItemStack boots = this.player.getInventory().getBoots();
      if (!boots.isEmpty() && !boots.getEnchantments().isEmpty()) {
         float level = boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER);
         if (level > 3.0F) {
            level = 3.0F;
         }

         if (this.lastOnGround == DesyncStatus.FALSE) {
            level *= 0.5F;
         }

         return level;
      } else {
         return 0.0F;
      }
   }

   public void handleBlockChange(BlockPos pos, BlockData to) {
      if (this.lastPrediction != null) {
         if (!this.player.compensatedEntities.getSelf().inVehicle()) {
            SimulationContext context = this.lastPrediction.getSimulationContext();
            if (context != null) {
               SimpleCollisionBox blockCollisionBox = new SimpleCollisionBox(pos);
               SimpleCollisionBox playerMaxExtent = context.getToMaximumExtent();
               playerMaxExtent.expand(1.0);
               if (blockCollisionBox.isIntersected(playerMaxExtent)) {
                  if (NmsBlockTags.isWater(to)) {
                     this.lastPrediction.getSimulationContext().getWorldData().setInWater(DesyncStatus.UNKNOWN);
                     this.lastPrediction.getSimulationContext().getWorldData().setInFlowingLiquid(DesyncStatus.UNKNOWN);
                  }

                  if (to.getMaterial() == Material.LAVA) {
                     this.lastPrediction.getSimulationContext().getWorldData().setInLava(DesyncStatus.UNKNOWN);
                     this.lastPrediction.getSimulationContext().getWorldData().setInFlowingLiquid(DesyncStatus.UNKNOWN);
                     this.lastPrediction.getSimulationContext().getWorldData().setWeirdFourteenFifteenLava(DesyncStatus.UNKNOWN);
                  }

                  if (NmsBlockTags.toNmsState(to).is(BlockTags.CLIMBABLE)
                     || to.getMaterial() == Material.POWDER_SNOW && this.player.getInventory().getBoots().getType() == Material.LEATHER_BOOTS) {
                     this.lastPrediction.getSimulationContext().getWorldData().setClimbing(DesyncStatus.UNKNOWN);
                  }

                  Vec3 stuckSpeed = Collisions.getStuckSpeedForBlock(this.player, to, this.changedBlockCanApplyPowderSnowStuckSpeed(context, pos, to));
                  if (stuckSpeed != null && this.couldChangedStuckSpeedBlockAffectLastMovement(context, pos)) {
                     this.lastPrediction.getSimulationContext().getWorldData().setStuckSpeed(new StuckSpeedData(null, stuckSpeed));
                     this.validPlayerStartingVels.add(Vec3.ZERO);
                  }

                  if (this.canSpeculatePointThreeFromMovementPacket() && !this.couldPotentiallyTickSkip) {
                     boolean isPointThree = this.tryToAchievePointThree(
                           new Vec3(this.player.x, this.player.y, this.player.z), this.player.xRot, this.player.yRot
                        )
                        .getFlags()
                        .isEmpty();
                     if (isPointThree) {
                        this.couldPotentiallyTickSkip = true;
                     }
                  }
               }
            }
         }
      }
   }

   private boolean changedBlockCanApplyPowderSnowStuckSpeed(SimulationContext context, BlockPos pos, BlockData to) {
      if (to.getMaterial() != Material.POWDER_SNOW) {
         return true;
      }

      BlockPos rootBlockPosition = new BlockPos(CultMath.floor(context.getEnd().x), CultMath.floor(context.getEnd().y), CultMath.floor(context.getEnd().z));
      return rootBlockPosition.equals(pos);
   }

   private boolean couldChangedStuckSpeedBlockAffectLastMovement(SimulationContext context, BlockPos pos) {
      return Collisions.canFullBlockAffectInsideBlockMovement(context.getFromMinimumExtent(), context.getToMinimumExtent(), context.getTarget(), pos)
         || Collisions.canFullBlockAffectInsideBlockMovement(context.getFromMaximumExtent(), context.getToMaximumExtent(), context.getTarget(), pos);
   }

   @Generated
   public static long getComputeTimeNS() {
      return computeTimeNS;
   }

   @Generated
   public LastInstance getLastTickSkip() {
      return this.lastTickSkip;
   }

   @Generated
   public LastInstance getLastOnGroundSkip() {
      return this.lastOnGroundSkip;
   }

   @Generated
   public EvictingQueue<Long> getLastMovementTime() {
      return this.lastMovementTime;
   }

   @Generated
   public PredictionResult getLastPrediction() {
      return this.lastPrediction;
   }

   @Generated
   public void setLastOnGround(DesyncStatus lastOnGround) {
      this.lastOnGround = lastOnGround;
   }

   private static final class CollisionProbeKey {
      private final SimulationContext context;
      private final long minY;
      private final Vec3 target;
      private final Vec3 playerPos;
      private final PredVector initialStartingVelocity;
      private final long attemptedMinX;
      private final long attemptedMinY;
      private final long attemptedMinZ;
      private final long attemptedMaxX;
      private final long attemptedMaxY;
      private final long attemptedMaxZ;
      private final boolean canStep;

      private CollisionProbeKey(
         SimulationContext context,
         double minY,
         Vec3 target,
         Vec3 playerPos,
         PredVector initialStartingVelocity,
         SimpleCollisionBox attemptedMovementExtents,
         boolean canStep
      ) {
         this.context = context;
         this.minY = Double.doubleToLongBits(minY);
         this.target = target;
         this.playerPos = playerPos;
         this.initialStartingVelocity = initialStartingVelocity;
         this.attemptedMinX = Double.doubleToLongBits(attemptedMovementExtents.minX);
         this.attemptedMinY = Double.doubleToLongBits(attemptedMovementExtents.minY);
         this.attemptedMinZ = Double.doubleToLongBits(attemptedMovementExtents.minZ);
         this.attemptedMaxX = Double.doubleToLongBits(attemptedMovementExtents.maxX);
         this.attemptedMaxY = Double.doubleToLongBits(attemptedMovementExtents.maxY);
         this.attemptedMaxZ = Double.doubleToLongBits(attemptedMovementExtents.maxZ);
         this.canStep = canStep;
      }

      @Override
      public boolean equals(Object other) {
         if (this == other) {
            return true;
         }
         return other instanceof CollisionProbeKey key
            && context == key.context
            && minY == key.minY
            && target.equals(key.target)
            && playerPos.equals(key.playerPos)
            && initialStartingVelocity == key.initialStartingVelocity
            && attemptedMinX == key.attemptedMinX
            && attemptedMinY == key.attemptedMinY
            && attemptedMinZ == key.attemptedMinZ
            && attemptedMaxX == key.attemptedMaxX
            && attemptedMaxY == key.attemptedMaxY
            && attemptedMaxZ == key.attemptedMaxZ
            && canStep == key.canStep;
      }

      @Override
      public int hashCode() {
         int result = System.identityHashCode(context);
         result = 31 * result + Long.hashCode(minY);
         result = 31 * result + target.hashCode();
         result = 31 * result + playerPos.hashCode();
         result = 31 * result + System.identityHashCode(initialStartingVelocity);
         result = 31 * result + Long.hashCode(attemptedMinX);
         result = 31 * result + Long.hashCode(attemptedMinY);
         result = 31 * result + Long.hashCode(attemptedMinZ);
         result = 31 * result + Long.hashCode(attemptedMaxX);
         result = 31 * result + Long.hashCode(attemptedMaxY);
         result = 31 * result + Long.hashCode(attemptedMaxZ);
         return 31 * result + Boolean.hashCode(canStep);
      }
   }
}
