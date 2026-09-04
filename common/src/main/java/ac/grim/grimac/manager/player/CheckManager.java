package ac.grim.grimac.manager.player;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.impl.aim.*;
import ac.grim.grimac.checks.impl.aim.processor.AimProcessor;
import ac.grim.grimac.checks.impl.autoclicker.AutoclickerLimit;
import ac.grim.grimac.checks.impl.badpackets.*;
import ac.grim.grimac.checks.impl.baritone.Baritone;
import ac.grim.grimac.checks.impl.bedrock.BedrockMovement;
import ac.grim.grimac.checks.impl.breaking.*;
import ac.grim.grimac.checks.impl.chat.*;
import ac.grim.grimac.checks.impl.combat.FairReach;
import ac.grim.grimac.checks.impl.combat.Hitboxes;
import ac.grim.grimac.checks.impl.combat.MultiInteractA;
import ac.grim.grimac.checks.impl.combat.MultiInteractB;
import ac.grim.grimac.checks.impl.combat.Reach;
import ac.grim.grimac.checks.impl.combat.SelfInteract;
import ac.grim.grimac.checks.impl.crash.*;
import ac.grim.grimac.checks.impl.elytra.*;
import ac.grim.grimac.checks.impl.exploit.ExploitA;
import ac.grim.grimac.checks.impl.exploit.ExploitB;
import ac.grim.grimac.checks.impl.exploit.ExploitC;
import ac.grim.grimac.checks.impl.groundspoof.NoFallExecutor;
import ac.grim.grimac.checks.impl.misc.ClientBrand;
import ac.grim.grimac.checks.impl.misc.GhostBlockMitigation;
import ac.grim.grimac.checks.impl.movement.EntityControl;
import ac.grim.grimac.checks.impl.movement.SetbackBlocker;
import ac.grim.grimac.checks.impl.movement.VehiclePredictionRunner;
import ac.grim.grimac.checks.impl.movement.timer.DumbTimer;
import ac.grim.grimac.checks.impl.movement.timer.NegativeTimerCheck;
import ac.grim.grimac.checks.impl.movement.timer.TickTimer;
import ac.grim.grimac.checks.impl.movement.timer.TimerCheck;
import ac.grim.grimac.checks.impl.movement.timer.VehicleTimer;
import ac.grim.grimac.checks.impl.multiactions.*;
import ac.grim.grimac.checks.impl.packetorder.*;
import ac.grim.grimac.checks.impl.ping.PingA;
import ac.grim.grimac.checks.impl.ping.TransactionOrder;
import ac.grim.grimac.checks.impl.post.PostCheck;
import ac.grim.grimac.checks.impl.prediction.DebugHandler;
import ac.grim.grimac.checks.impl.prediction.FlagCaller;
import ac.grim.grimac.checks.impl.prediction.OffsetHandler;
import ac.grim.grimac.checks.impl.prediction.SuperDebug;
import ac.grim.grimac.checks.impl.prediction.profile.MovementProfiles;
import ac.grim.grimac.checks.impl.prediction.checks.NoSlow;
import ac.grim.grimac.checks.impl.prediction.checks.Phase;
import ac.grim.grimac.checks.impl.prediction.checks.ServerStateNoSlow;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.*;
import ac.grim.grimac.checks.impl.prediction.runner.ExplosionHandler;
import ac.grim.grimac.checks.impl.prediction.runner.KnockbackHandler;
import ac.grim.grimac.checks.impl.prediction.runner.SimulationProcessor;
import ac.grim.grimac.checks.impl.scaffolding.*;
import ac.grim.grimac.checks.impl.sprint.*;
import ac.grim.grimac.checks.impl.vehicle.*;
import ac.grim.grimac.checks.type.*;
import ac.grim.grimac.events.packets.*;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.utils.anticheat.update.PositionUpdate;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.anticheat.update.RotationUpdate;
import ac.grim.grimac.utils.anticheat.update.VehiclePositionUpdate;
import ac.grim.grimac.utils.latency.CompensatedCooldown;
import ac.grim.grimac.utils.latency.CompensatedInventory;
import ac.grim.grimac.utils.latency.KeepAliveProcessor;
import ac.grim.grimac.utils.lists.EvictingQueue;
import ac.grim.grimac.utils.team.TeamHandler;
import ac.grim.grimac.utils.nmsutil.BoundingBoxSize;
import ac.grim.grimac.network.PacketHandlerScanner;
import ac.grim.grimac.network.PacketReceiveHandler;
import ac.grim.grimac.network.PacketReceiveRoute;
import ac.grim.grimac.network.PacketSendHandler;
import ac.grim.grimac.network.PacketSendRoute;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.platform.api.permissions.PermissionDefaultValue;
import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.ImmutableClassToInstanceMap;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class CheckManager {
    private static final List<Class<? extends CheckListener>> SEND_DISPATCH_LISTENER_TYPES = List.of(
            CrashD.class,
            ActionManager.class,
            FairReach.class,
            PacketEntityReplication.class,
            PacketChangeGameState.class,
            CompensatedInventory.class,
            PacketPlayerAbilities.class,
            PacketServerTickingState.class,
            PacketWorldBorder.class,
            KeepAliveProcessor.class,
            BadPacketsO.class,
            BadPacketsP.class,
            BadPacketsM.class,
            TeamHandler.class,
            PostCheck.class,
            ExplosionHandler.class
    );

    private final GrimPlayer player;

    ClassToInstanceMap<CheckListener> packetChecks;
    ClassToInstanceMap<CheckListener> positionCheck;
    ClassToInstanceMap<RotationListener> rotationCheck;
    ClassToInstanceMap<VehicleListener> vehicleCheck;
    ClassToInstanceMap<CheckListener> identityChecks;
    ClassToInstanceMap<CheckListener> prePredictionChecks;

    ClassToInstanceMap<BlockPlaceCheck> blockPlaceCheck;
    ClassToInstanceMap<PostPredictionListener> postPredictionCheck;
    ClassToInstanceMap<PostPredictionListener> pseudoCheck;

    List<BlockPlace> placesToCheck = new EvictingQueue<>(20);

    private final List<CheckListener> blockBreakListeners = new ArrayList<>();
    private final List<CheckListener> postFlyingBlockBreakListeners = new ArrayList<>();
    private final List<BlockBreak> breaksToCheck = new EvictingQueue<>(20);

    public ClassToInstanceMap<CheckListener> allCheckListeners;

    public final Map<Class<? extends Check>, Check> allChecks = new HashMap<>();
    private final Map<Class<? extends Packet<?>>, List<PacketHandlerScanner.ReceiveRegistration>> prePredictionReceiveRegistrations =
            new HashMap<>();
    private final Map<Class<? extends Packet<?>>, List<PacketHandlerScanner.ReceiveRegistration>> receiveRegistrations =
            new HashMap<>();
    private final Map<Class<? extends Packet<?>>, List<PacketHandlerScanner.SendRegistration>> sendRegistrations =
            new HashMap<>();
    private final List<TickEndHandlerRegistration> tickEndHandlers = new ArrayList<>();
    private final List<EarlyReceiveHandlerRegistration> earlyReceiveHandlers = new ArrayList<>();
    private final List<DecodedPacketReceiveListener> decodedReceiveListeners = new ArrayList<>();
    private final List<OrderedPacketReceiveListener> orderedReceiveListeners = new ArrayList<>();
    private final Set<CheckListener> receiveDispatchListeners =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CheckListener> sendDispatchListeners =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private Map<Class<? extends Packet<?>>, PacketReceiveRoute> prePredictionReceiveRoutes = Map.of();
    private Map<Class<? extends Packet<?>>, PacketReceiveRoute> receiveRoutes = Map.of();
    private Map<Class<? extends Packet<?>>, PacketSendRoute> sendRoutes = Map.of();

    public Collection<Check> getAllChecks() {
        return allChecks.values();
    }

    public static List<Class<? extends Packet<?>>> sendDispatchPacketTypes() {
        LinkedHashSet<Class<? extends Packet<?>>> packetTypes = new LinkedHashSet<>();
        for (Class<? extends CheckListener> listenerType : SEND_DISPATCH_LISTENER_TYPES) {
            packetTypes.addAll(PacketHandlerScanner.sendPacketTypes(listenerType));
        }
        return List.copyOf(packetTypes);
    }

    public CheckManager(GrimPlayer player) {
        this.player = player;

        packetChecks = buildPacketChecks(player);
        positionCheck = buildPositionChecks(player);
        rotationCheck = buildRotationChecks(player);
        vehicleCheck = buildVehicleChecks(player);
        identityChecks = buildIdentityChecks(player);
        postPredictionCheck = buildPostPredictionChecks(player);
        blockPlaceCheck = buildBlockPlaceChecks(player);
        prePredictionChecks = buildPrePredictionChecks(player);
        pseudoCheck = buildPseudoChecks(player);

        allCheckListeners = ImmutableClassToInstanceMap.<CheckListener>builder()
                .putAll(packetChecks)
                .putAll(positionCheck)
                .putAll(rotationCheck)
                .putAll(vehicleCheck)
                .putAll(identityChecks)
                .putAll(postPredictionCheck)
                .putAll(blockPlaceCheck)
                .putAll(prePredictionChecks)
                .putAll(pseudoCheck)
                .build();

        for (CheckListener listener : allCheckListeners.values()) {
            if (listener instanceof BlockBreakListener) {
                blockBreakListeners.add(listener);
            }
            if (listener instanceof PostFlyingBlockBreakListener) {
                postFlyingBlockBreakListeners.add(listener);
            }
        }

        registerPacketDispatch();
        validatePacketDispatchCoverage();
        buildPacketRoutes();

        for (CheckListener listener : allCheckListeners.values()) {
            if (listener instanceof Check check) {
                allChecks.put(check.getClass(), check);
                check.reload();
            } else if (listener instanceof GrimProcessor processor) {
                processor.reload();
            }
        }

        registerCheckPermissionsOnce();
    }


    private static final AtomicBoolean checkPermissionsRegistered = new AtomicBoolean(false);

    private void registerCheckPermissionsOnce() {
        if (checkPermissionsRegistered.getAndSet(true)) return;

        final String[] permissions = {
                "grim.exempt.",
                "grim.nosetback.",
                "grim.nomodifypacket.",
        };

        for (final Check check : allChecks.values()) {
            if (check.getConfigName() == null) continue;
            final String id = check.getConfigName().toLowerCase();
            for (String permissionName : permissions) {
                GrimAPI.INSTANCE.getPermissionManager().registerPermission(permissionName + id, PermissionDefaultValue.FALSE);
            }
        }
    }

    // Keys each listener by its concrete class, preserving list order in the resulting map.
    @SuppressWarnings("unchecked")
    private static <T> ClassToInstanceMap<T> listenerMap(List<? extends T> listeners) {
        ImmutableClassToInstanceMap.Builder<T> builder = ImmutableClassToInstanceMap.builder();
        for (T listener : listeners) {
            // Guava's put(Class<S>, S) ties key and value to the same type variable; the
            // wildcard cast broke that inference on this Guava version, so cast to Class<T>.
            builder.put((Class<T>) listener.getClass(), listener);
        }
        return builder.build();
    }

    // Packet-dispatched listeners. SetbackBlocker must stay last in this map: while it
    // blocks packets, no check after it is allowed to observe the packet.
    private static ClassToInstanceMap<CheckListener> buildPacketChecks(GrimPlayer player) {
        return listenerMap(List.<CheckListener>of(
                new SimulationProcessor(player),
                player.actionManager,
                player.pluginChannelManager,
                new Reach(player),
                new Hitboxes(player),
                new FairReach(player),
                player.packetEntityReplication,
                new PacketChangeGameState(player),
                new CompensatedInventory(player),
                new PacketPlayerAbilities(player),
                new PacketServerTickingState(player),
                new PacketWorldBorder(player),
                player.keepAliveProcessor,
                new ChatA(player),
                new ChatB(player),
                new ChatC(player),
                new ChatD(player),
                new ClientBrand(player),
                new NoFallExecutor(player),
                new PingA(player),
                new TransactionOrder(player),
                new BadPacketsO(player),
                new BadPacketsA(player),
                new BadPacketsB(player),
                new BadPacketsC(player),
                new BadPacketsD(player),
                new BadPacketsE(player),
                new BadPacketsF(player),
                new BadPacketsG(player),
                new BadPacketsI(player),
                new BadPacketsJ(player),
                new BadPacketsVehicle(player),
                new BadPacketsK(player),
                new BadPacketsL(player),
                new BadPacketsM(player),
                new BadPacketsN(player),
                new BadPacketsP(player),
                new BadPacketsQ(player),
                new BadPacketsR(player),
                new BadPacketsS(player),
                new BadPacketsT(player),
                new BadPacketsU(player),
                new BadPacketsV(player),
                new BadPacketsW(player),
                new BadPacketsY(player),
                new BadPacketsZ(player),
                player.packetOrderProcessor,
                new PacketOrderB(player),
                new PacketOrderC(player),
                new PacketOrderD(player),
                new PacketOrderO(player),
                new SelfInteract(player),
                new MultiActionsA(player),
                new MultiActionsB(player),
                new MultiActionsC(player),
                new MultiActionsD(player),
                new MultiActionsE(player),
                new VehicleA(player),
                new VehicleB(player),
                new VehicleD(player),
                new VehicleE(player),
                new VehicleF(player),
                new TeamHandler(player),
                new AirLiquidBreak(player),
                new WrongBreak(player),
                new RotationBreak(player),
                new FastBreak(player),
                new NoSwingBreak(player),
                new FarBreak(player),
                new InvalidBreak(player),
                new PositionBreakA(player),
                new PositionBreakB(player),
                new AutoclickerLimit(player),
                new SetbackBlocker(player)));
    }

    private static ClassToInstanceMap<CheckListener> buildPositionChecks(GrimPlayer player) {
        return listenerMap(List.<CheckListener>of(
                new CompensatedCooldown(player)));
    }

    private static ClassToInstanceMap<RotationListener> buildRotationChecks(GrimPlayer player) {
        return listenerMap(List.<RotationListener>of(
                // AimProcessor attaches itself to the update before dependent checks consume it.
                new AimProcessor(player),
                new AimModulo360(player),
                new AimDuplicateLook(player),
                new Baritone(player)));
    }

    private static ClassToInstanceMap<VehicleListener> buildVehicleChecks(GrimPlayer player) {
        return listenerMap(List.<VehicleListener>of(
                new VehiclePredictionRunner(player)));
    }

    // Runtime check identities which deliberately own no callback in the
    // baseline. Keeping them out of callback lists preserves registration and
    // configuration without inventing behavior.
    private static ClassToInstanceMap<CheckListener> buildIdentityChecks(GrimPlayer player) {
        return listenerMap(List.<CheckListener>of(
                new VehicleC(player)));
    }

    private static ClassToInstanceMap<PostPredictionListener> buildPostPredictionChecks(GrimPlayer player) {
        return listenerMap(List.<PostPredictionListener>of(
                player.getGhostBlockMitigator(),
                new FlagCaller(player),
                new SuperDebug(player),
                new NoSlow(player),
                player.getServerStateNoSlow(),
                new Phase(player),
                new PostCheck(player),
                new NegativeTimerCheck(player),
                new BedrockMovement(player),
                new OffsetHandler(player),
                new DebugHandler(player),
                new EntityControl(player), // registered listener is currently a no-op
                new SetbackTeleportUtil(player), // setback state depends on current friction
                player.knockbackHandler,
                player.explosionHandler,
                player.compensatedFireworks,
                player.lastInstanceManager,
                new PacketOrderA(player),
                new PacketOrderE(player),
                new PacketOrderF(player),
                new PacketOrderG(player),
                new PacketOrderH(player),
                new PacketOrderI(player),
                new PacketOrderJ(player),
                new PacketOrderK(player),
                new PacketOrderL(player),
                new PacketOrderM(player),
                new BadPacketsX(player),
                new SprintA(player),
                new SprintB(player),
                new SprintC(player),
                new SprintD(player),
                new SprintE(player),
                new SprintF(player),
                new SprintG(player),
                new MultiInteractA(player),
                new MultiInteractB(player),
                new ElytraA(player),
                new ElytraB(player),
                new ElytraC(player),
                new ElytraD(player),
                new ElytraE(player),
                new ElytraF(player),
                new ElytraG(player),
                new ElytraH(player),
                new ElytraI(player),
                new MultiBreak(player)));
    }

    private static ClassToInstanceMap<BlockPlaceCheck> buildBlockPlaceChecks(GrimPlayer player) {
        return listenerMap(List.<BlockPlaceCheck>of(
                new BadPacketsH(player),
                new InvalidPlaceA(player),
                new InvalidPlaceB(player),
                new AirLiquidPlace(player),
                new MultiPlace(player),
                new MultiActionsF(player),
                new MultiActionsG(player),
                new CrashG(player),
                new FarPlace(player),
                new FabricatedPlace(player),
                new PositionPlace(player),
                new RotationPlace(player),
                new PacketOrderN(player),
                new DuplicateRotPlace(player),
                new GhostBlockMitigation(player)));
    }

    private static ClassToInstanceMap<CheckListener> buildPrePredictionChecks(GrimPlayer player) {
        return listenerMap(List.<CheckListener>of(
                new TimerCheck(player),
                new TickTimer(player),
                new DumbTimer(player),
                new CrashA(player),
                new CrashB(player),
                new CrashC(player),
                new CrashD(player),
                new CrashE(player),
                new CrashF(player),
                new CrashH(player),
                new CrashI(player),
                new ExploitA(player),
                new ExploitB(player),
                new ExploitC(player),
                new VehicleTimer(player)));
    }

    private static ClassToInstanceMap<PostPredictionListener> buildPseudoChecks(GrimPlayer player) {
        return listenerMap(List.<PostPredictionListener>of(
                new InvalidStep(player),
                new NoSneakSlow(player),
                new OmniSprint(player),
                new NoFallPseudo(player),
                new VerticalOffset(player),
                new ElytraPseudo(player),
                new RiptideOffset(player),
                new VehicleOffset(player),
                new BoatPseudo(player),
                new HappyGhastPseudo(player),
                new Speed(player),
                new Strafe(player),
                new Angle(player)));
    }

    private void registerPacketDispatch() {
        // These callbacks were pre-Via in the committed listener. The raw
        // packet identity is unavailable here, but decoded cancellation and
        // relative ordering are preserved ahead of every ordinary processor.
        registerEarlyReceive(packetChecks.get(ChatA.class));
        registerEarlyReceive(packetChecks.get(ChatB.class));
        registerEarlyReceive(packetChecks.get(ChatC.class));
        registerEarlyReceive(packetChecks.get(ChatD.class));
        registerEarlyReceive(packetChecks.get(BadPacketsA.class));
        registerEarlyReceive(packetChecks.get(BadPacketsC.class));
        registerEarlyReceive(packetChecks.get(BadPacketsF.class));
        registerEarlyReceive(packetChecks.get(BadPacketsG.class));
        registerEarlyReceive(packetChecks.get(BadPacketsI.class));
        registerEarlyReceive(packetChecks.get(BadPacketsK.class));
        registerEarlyReceive(packetChecks.get(BadPacketsM.class));
        registerEarlyReceive(packetChecks.get(BadPacketsW.class));
        registerEarlyReceive(packetChecks.get(BadPacketsY.class));
        registerEarlyReceive(packetChecks.get(BadPacketsZ.class));
        registerEarlyOrderedReceive(packetChecks.get(PacketOrderB.class));
        registerEarlyReceive(packetChecks.get(PacketOrderC.class));
        registerEarlyReceive(packetChecks.get(PacketOrderD.class));
        registerEarlyReceive(packetChecks.get(SelfInteract.class));
        registerEarlyReceive(packetChecks.get(MultiActionsA.class));
        registerEarlyOrderedReceive(packetChecks.get(MultiActionsE.class));
        registerEarlyReceive(packetChecks.get(FastBreak.class));
        registerEarlyReceive(postPredictionCheck.get(MultiBreak.class));
        registerEarlyReceive(packetChecks.get(NoSwingBreak.class));

        registerOrderedReceive(packetChecks.get(BadPacketsE.class));
        registerOrderedReceive(packetChecks.get(PacketOrderO.class));
        registerDecodedReceive(packetChecks.get(BadPacketsJ.class));
        registerDecodedReceive(postPredictionCheck.get(BadPacketsX.class));
        registerDecodedReceive(postPredictionCheck.get(ElytraC.class));

        registerPreReceive(prePredictionChecks.get(TimerCheck.class));
        registerPreReceive(prePredictionChecks.get(TickTimer.class));
        registerPreReceive(prePredictionChecks.get(DumbTimer.class));
        registerPreReceive(prePredictionChecks.get(CrashA.class));
        registerPreReceive(prePredictionChecks.get(CrashC.class));
        registerPreReceive(prePredictionChecks.get(CrashI.class));
        registerPreReceive(prePredictionChecks.get(VehicleTimer.class));
        registerPreReceive(packetChecks.get(SetbackBlocker.class));

        registerReceive(packetChecks.get(SimulationProcessor.class));
        registerReceive(packetChecks.get(ActionManager.class));
        registerReceive(packetChecks.get(PluginChannelManager.class));
        registerReceive(packetChecks.get(Reach.class));
        registerReceive(packetChecks.get(FairReach.class));
        registerReceive(packetChecks.get(CompensatedInventory.class));
        registerReceive(packetChecks.get(PacketPlayerAbilities.class));
        registerReceive(packetChecks.get(KeepAliveProcessor.class));
        registerReceive(packetChecks.get(BadPacketsO.class));
        registerReceive(packetChecks.get(ClientBrand.class));
        registerReceive(packetChecks.get(NoFallExecutor.class));
        registerReceive(prePredictionChecks.get(ExploitA.class));
        registerReceive(prePredictionChecks.get(ExploitB.class));
        registerReceive(packetChecks.get(BadPacketsD.class));
        registerReceive(packetChecks.get(BadPacketsJ.class));
        registerReceive(packetChecks.get(BadPacketsL.class));
        registerReceive(packetChecks.get(BadPacketsP.class));
        registerReceive(packetChecks.get(BadPacketsQ.class));
        registerReceive(packetChecks.get(BadPacketsR.class));
        registerReceive(packetChecks.get(BadPacketsS.class));
        registerReceive(packetChecks.get(BadPacketsT.class));
        registerReceive(packetChecks.get(BadPacketsU.class));
        registerReceive(packetChecks.get(BadPacketsV.class));
        registerReceive(blockPlaceCheck.get(BadPacketsH.class));
        registerReceive(packetChecks.get(PacketOrderProcessor.class));
        registerReceive(packetChecks.get(MultiActionsC.class));
        registerReceive(packetChecks.get(MultiActionsD.class));
        registerReceive(packetChecks.get(VehicleA.class));
        registerReceive(packetChecks.get(VehicleD.class));
        registerReceive(packetChecks.get(VehicleE.class));
        registerReceive(packetChecks.get(VehicleF.class));
        registerReceive(packetChecks.get(FarBreak.class));
        registerReceive(packetChecks.get(PositionBreakA.class));
        registerReceive(packetChecks.get(AutoclickerLimit.class));
        registerReceive(prePredictionChecks.get(CrashB.class));
        registerReceive(prePredictionChecks.get(CrashD.class));
        registerReceive(prePredictionChecks.get(CrashE.class));
        registerReceive(prePredictionChecks.get(CrashF.class));
        registerReceive(blockPlaceCheck.get(CrashG.class));
        registerReceive(prePredictionChecks.get(CrashH.class));

        registerTickEnd(packetChecks.get(ActionManager.class));
        registerTickEnd(packetChecks.get(Reach.class));
        registerTickEnd(vehicleCheck.get(VehiclePredictionRunner.class));
        // MCP-Reborn Minecraft#tick computes the attack hit result before
        // ClientLevel#tickEntities interpolates remote entities, then sends
        // ServerboundClientTickEndPacket. Reach consumes queued attacks first;
        // entity interpolation advances afterward for the next client tick.
        registerTickEnd(packetChecks.get(PacketEntityReplication.class));
        registerTickEnd(packetChecks.get(SimulationProcessor.class));
        registerTickEnd(packetChecks.get(AutoclickerLimit.class));

        registerReceive(postPredictionCheck.get(NoSlow.class));
        registerReceive(postPredictionCheck.get(ServerStateNoSlow.class));
        registerReceive(postPredictionCheck.get(Phase.class));
        registerReceive(postPredictionCheck.get(PostCheck.class));
        registerReceive(postPredictionCheck.get(NegativeTimerCheck.class));
        // AimDuplicateLook (rotation listener) declares no packet handlers; registering it for
        // receive dispatch would throw in registerReceive at CheckManager construction.

        for (Class<? extends PostPredictionListener> type : List.of(
                PacketOrderA.class, PacketOrderE.class, PacketOrderF.class, PacketOrderG.class,
                PacketOrderH.class, PacketOrderI.class, PacketOrderJ.class, PacketOrderK.class,
                PacketOrderL.class, PacketOrderM.class, BadPacketsX.class, SprintD.class,
                SprintE.class,
                MultiInteractA.class, MultiInteractB.class, ElytraB.class,
                ElytraC.class, ElytraD.class, ElytraE.class, ElytraF.class, ElytraG.class,
                ElytraH.class, ElytraI.class)) {
            registerReceive(postPredictionCheck.get(type));
        }
        for (Class<? extends BlockPlaceCheck> type : List.of(
                MultiPlace.class, MultiActionsF.class, MultiActionsG.class, PositionPlace.class,
                PacketOrderN.class)) {
            registerReceive(blockPlaceCheck.get(type));
        }

        for (Class<? extends CheckListener> listenerType : SEND_DISPATCH_LISTENER_TYPES) {
            registerSend(allCheckListeners.get(listenerType));
        }
    }

    private void registerPreReceive(CheckListener listener) {
        registerReceive(prePredictionReceiveRegistrations, listener);
    }

    private void registerEarlyReceive(CheckListener listener) {
        if (listener == null || !shouldRegister(listener)) {
            return;
        }
        receiveDispatchListeners.add(listener);

        List<PacketHandlerScanner.ReceiveRegistration> registrations = PacketHandlerScanner.receiveHandlers(listener);
        if (registrations.isEmpty()) {
            if (!PacketHandlerScanner.hasReceiveHandlerDeclaration(listener.getClass())) {
                throw new IllegalStateException("No receive @GrimPacketHandler methods on "
                        + listener.getClass().getName());
            }
            return;
        }

        for (PacketHandlerScanner.ReceiveRegistration registration : registrations) {
            PacketReceiveHandler<Packet<?>> handler = (event, player, packet) -> {
                if (shouldDispatch(listener)) {
                    registration.handler().handle(event, player, packet);
                }
            };
            earlyReceiveHandlers.add(new EarlyReceiveHandlerRegistration(registration.packetType(), handler));
        }
    }

    private void registerEarlyOrderedReceive(CheckListener listener) {
        if (listener == null || !shouldRegister(listener)) {
            return;
        }
        if (!(listener instanceof OrderedPacketReceiveListener orderedListener)) {
            throw new IllegalStateException(listener.getClass().getName()
                    + " is not an ordered packet receive listener");
        }
        earlyReceiveHandlers.add(new EarlyReceiveHandlerRegistration(null,
                (event, player, packet) -> {
                    if (shouldDispatch(listener)) {
                        orderedListener.onPacketReceive(event);
                    }
                }));
    }

    private void registerOrderedReceive(CheckListener listener) {
        if (listener == null || !shouldRegister(listener)) {
            return;
        }
        if (!(listener instanceof OrderedPacketReceiveListener orderedListener)) {
            throw new IllegalStateException(listener.getClass().getName()
                    + " is not an ordered packet receive listener");
        }
        orderedReceiveListeners.add(orderedListener);
    }

    private void registerDecodedReceive(CheckListener listener) {
        if (listener == null || !shouldRegister(listener)) {
            return;
        }
        if (!(listener instanceof DecodedPacketReceiveListener decodedListener)) {
            throw new IllegalStateException(listener.getClass().getName()
                    + " is not a decoded packet receive listener");
        }
        decodedReceiveListeners.add(decodedListener);
    }

    private void registerReceive(CheckListener listener) {
        registerReceive(receiveRegistrations, listener);
    }

    private void registerSend(CheckListener listener) {
        registerSend(sendRegistrations, listener);
    }

    private void registerTickEnd(CheckListener listener) {
        if (listener == null) {
            return;
        }
        if (!shouldRegister(listener)) {
            return;
        }
        if (!(listener instanceof ClientTickEndListener tickEndListener)) {
            throw new IllegalStateException(listener.getClass().getName() + " is not a client tick-end listener");
        }
        tickEndHandlers.add(new TickEndHandlerRegistration(tickEndListener));
    }

    private void registerReceive(Map<Class<? extends Packet<?>>, List<PacketHandlerScanner.ReceiveRegistration>> handlers,
                                 CheckListener listener) {
        if (listener == null) {
            return;
        }
        if (!shouldRegister(listener)) {
            return;
        }
        receiveDispatchListeners.add(listener);

        List<PacketHandlerScanner.ReceiveRegistration> registrations = PacketHandlerScanner.receiveHandlers(listener);
        if (registrations.isEmpty()) {
            if (!PacketHandlerScanner.hasReceiveHandlerDeclaration(listener.getClass())) {
                throw new IllegalStateException("No receive @GrimPacketHandler methods on " + listener.getClass().getName());
            }
            return;
        }
        for (PacketHandlerScanner.ReceiveRegistration registration : registrations) {
            PacketReceiveHandler<Packet<?>> handler = (event, player, packet) -> {
                if (shouldDispatch(listener)) {
                    registration.handler().handle(event, player, packet);
                }
            };
            handlers.computeIfAbsent(registration.packetType(), ignored -> new ArrayList<>())
                    .add(new PacketHandlerScanner.ReceiveRegistration(registration.packetType(), handler));
        }
    }

    private void registerSend(Map<Class<? extends Packet<?>>, List<PacketHandlerScanner.SendRegistration>> handlers,
                              CheckListener listener) {
        if (listener == null) {
            return;
        }
        if (!shouldRegister(listener)) {
            return;
        }
        sendDispatchListeners.add(listener);

        List<PacketHandlerScanner.SendRegistration> registrations = PacketHandlerScanner.sendHandlers(listener);
        if (registrations.isEmpty()) {
            if (!PacketHandlerScanner.hasSendHandlerDeclaration(listener.getClass())) {
                throw new IllegalStateException("No send @GrimPacketHandler methods on " + listener.getClass().getName());
            }
            return;
        }
        for (PacketHandlerScanner.SendRegistration registration : registrations) {
            PacketSendHandler<Packet<?>> handler = (event, player, packet) -> {
                if (shouldDispatch(listener)) {
                    registration.handler().handle(event, player, packet);
                }
            };
            handlers.computeIfAbsent(registration.packetType(), ignored -> new ArrayList<>())
                    .add(new PacketHandlerScanner.SendRegistration(registration.packetType(), handler));
        }
    }

    private void validatePacketDispatchCoverage() {
        List<String> missing = new ArrayList<>();
        for (CheckListener listener : allCheckListeners.values()) {
            if (!shouldRegister(listener)) {
                continue;
            }
            if (PacketHandlerScanner.hasReceiveHandlerDeclaration(listener.getClass())
                    && !receiveDispatchListeners.contains(listener)) {
                missing.add(listener.getClass().getName() + " receive");
            }
            if (PacketHandlerScanner.hasSendHandlerDeclaration(listener.getClass())
                    && !sendDispatchListeners.contains(listener)) {
                missing.add(listener.getClass().getName() + " send");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Unregistered @GrimPacketHandler declarations: "
                    + String.join(", ", missing));
        }
    }

    private void buildPacketRoutes() {
        prePredictionReceiveRoutes = buildReceiveRoutes(prePredictionReceiveRegistrations);
        receiveRoutes = buildReceiveRoutes(receiveRegistrations);
        sendRoutes = buildSendRoutes(sendRegistrations);
    }

    private static Map<Class<? extends Packet<?>>, PacketReceiveRoute> buildReceiveRoutes(
            Map<Class<? extends Packet<?>>, List<PacketHandlerScanner.ReceiveRegistration>> registrations
    ) {
        Map<Class<? extends Packet<?>>, PacketReceiveRoute> routes = new HashMap<>();
        for (Map.Entry<Class<? extends Packet<?>>, List<PacketHandlerScanner.ReceiveRegistration>> entry
                : registrations.entrySet()) {
            routes.put(entry.getKey(), buildReceiveRoute(entry.getValue()));
        }
        return Map.copyOf(routes);
    }

    private static Map<Class<? extends Packet<?>>, PacketSendRoute> buildSendRoutes(
            Map<Class<? extends Packet<?>>, List<PacketHandlerScanner.SendRegistration>> registrations
    ) {
        Map<Class<? extends Packet<?>>, PacketSendRoute> routes = new HashMap<>();
        for (Map.Entry<Class<? extends Packet<?>>, List<PacketHandlerScanner.SendRegistration>> entry
                : registrations.entrySet()) {
            routes.put(entry.getKey(), buildSendRoute(entry.getValue()));
        }
        return Map.copyOf(routes);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PacketReceiveRoute buildReceiveRoute(List<PacketHandlerScanner.ReceiveRegistration> registrations) {
        PacketReceiveHandler<Packet<?>>[] handlers = new PacketReceiveHandler[registrations.size()];
        for (int i = 0; i < registrations.size(); i++) {
            handlers[i] = registrations.get(i).handler();
        }
        return PacketReceiveRoute.of(handlers);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PacketSendRoute buildSendRoute(List<PacketHandlerScanner.SendRegistration> registrations) {
        PacketSendHandler<Packet<?>>[] handlers = new PacketSendHandler[registrations.size()];
        for (int i = 0; i < registrations.size(); i++) {
            handlers[i] = registrations.get(i).handler();
        }
        return PacketSendRoute.of(handlers);
    }

    private boolean shouldRegister(CheckListener listener) {
        return !(listener instanceof Check check)
                || !player.isBedrockMovement()
                || check.isBedrockSupported();
    }

    private boolean shouldDispatch(CheckListener listener) {
        return shouldRegister(listener);
    }

    public void reload() {
        for (CheckListener value : allCheckListeners.values()) {
            if (value instanceof GrimProcessor) {
                GrimProcessor processor = (GrimProcessor) value;
                processor.reload();
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends CheckListener> T getListener(Class<T> check) {
        return (T) allCheckListeners.get(check);
    }

    @SuppressWarnings("unchecked")
    public <T extends Check> T getCheck(Class<T> check) {
        return (T) allChecks.get(check);
    }

    public void dispatchPrePredictionReceive(final PacketReceiveEvent packet) {
        Packet<?> routedPacket = packet.getNmsPacket();
        PacketReceiveRoute route = prePredictionReceiveRoutes.get(routedPacket.getClass());
        if (route != null) {
            route.dispatch(packet, player, routedPacket);
        }
    }

    public void dispatchEarlyReceive(final PacketReceiveEvent event) {
        Packet<?> packet = event.getNmsPacket();
        for (EarlyReceiveHandlerRegistration handler : earlyReceiveHandlers) {
            handler.handle(event, player, packet);
        }
    }

    public void dispatchReceiveHandlers(final PacketReceiveEvent packet) {
        dispatchDecodedReceiveObservers(packet);

        Packet<?> routedPacket = packet.getNmsPacket();
        PacketReceiveRoute route = receiveRoutes.get(routedPacket.getClass());
        if (route != null) {
            route.dispatch(packet, player, routedPacket);
        }

        dispatchOrderedReceive(packet);

        if (routedPacket instanceof ServerboundClientTickEndPacket) {
            // MCP-Reborn Minecraft#tick sends ServerboundClientTickEndPacket only
            // after ClientLevel#tickEntities and Level#tickBlockEntities have
            // both finished. Tick-end handlers that simulate the completed client
            // tick therefore must see the post-block-entity piston phase and the
            // post-entity interpolation state, not the stale pre-tick-end cache.
            player.compensatedWorld.onClientTickEnd();
            for (TickEndHandlerRegistration handler : tickEndHandlers) {
                handler.handle(packet);
            }
        }
    }

    public void dispatchDecodedReceiveObservers(final PacketReceiveEvent event) {
        if (event.getConnectionState() != ConnectionProtocol.PLAY) {
            return;
        }
        for (DecodedPacketReceiveListener listener : decodedReceiveListeners) {
            if (shouldDispatch(listener)) {
                listener.onDecodedPacketReceive(event);
            }
        }
    }

    /**
     * Dispatches checks whose legacy behavior depends on every ordered PLAY
     * packet, including packets that intentionally bypass the normal check route.
     */
    public void dispatchOrderedReceive(final PacketReceiveEvent packet) {
        for (OrderedPacketReceiveListener listener : orderedReceiveListeners) {
            if (shouldDispatch(listener)) {
                listener.onPacketReceive(packet);
            }
        }
    }

    public void dispatchSendHandlers(final PacketSendEvent packet) {
        Packet<?> routedPacket = packet.getNmsPacket();
        PacketSendRoute route = sendRoutes.get(routedPacket.getClass());
        if (route != null) {
            route.dispatch(packet, player, routedPacket);
        }
    }

    public void doChecksWithKnownLook() {
        for (BlockPlace place : placesToCheck) {
            for (BlockPlaceCheck check : blockPlaceCheck.values()) {
                if (!shouldDispatch(check)) {
                    continue;
                }
                check.onPostFlyingBlockPlace(place);
            }
        }
        placesToCheck.clear();

        // Evaluate breaks at the position claimed when they were queued.
        if (!breaksToCheck.isEmpty()) {
            double lastX = player.x;
            double lastY = player.y;
            double lastZ = player.z;

            Vec3 claimed = player.packetStateData.clientSidePosition;
            player.x = claimed.x;
            player.y = claimed.y;
            player.z = claimed.z;

            if (player.inVehicle()) {
                Vec3 posFromVehicle = BoundingBoxSize.getRidingOffsetFromVehicle(player.compensatedEntities.getSelf().getRiding(), player);
                player.x = posFromVehicle.x;
                player.y = posFromVehicle.y;
                player.z = posFromVehicle.z;
            }

            for (BlockBreak blockBreak : breaksToCheck) {
                for (CheckListener check : postFlyingBlockBreakListeners) {
                    if (!shouldDispatch(check)) {
                        continue;
                    }
                    ((PostFlyingBlockBreakListener) check).onPostFlyingBlockBreak(blockBreak);
                }
            }
            breaksToCheck.clear();

            player.x = lastX;
            player.y = lastY;
            player.z = lastZ;
        }
    }

    public void onPositionUpdate(final PositionUpdate position) {
        for (CheckListener check : prePredictionChecks.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.onPositionUpdate(position);
        }
        for (CheckListener check : positionCheck.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.onPositionUpdate(position);
        }
        for (CheckListener check : packetChecks.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.onPositionUpdate(position);
        }
    }

    public void onRotationUpdate(final RotationUpdate rotation) {
        for (RotationListener check : rotationCheck.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.process(rotation);
        }
        for (BlockPlaceCheck check : blockPlaceCheck.values()) {
            if (!(check instanceof RotationListener rotationListener) || !shouldDispatch(check)) {
                continue;
            }
            rotationListener.process(rotation);
        }
    }

    public void onVehiclePositionUpdate(final VehiclePositionUpdate update) {
        for (VehicleListener check : vehicleCheck.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.process(update);
        }
    }

    public void onPredictionFinish(final PredictionComplete complete) {
        for (PostPredictionListener check : postPredictionCheck.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.onPredictionComplete(complete);
        }
        // Some block-place checks also consume completed predictions.
        for (BlockPlaceCheck check : blockPlaceCheck.values()) {
            if (!(check instanceof PostPredictionListener postPredictionListener)) {
                continue;
            }
            if (!shouldDispatch(check)) {
                continue;
            }
            postPredictionListener.onPredictionComplete(complete);
        }
        for (PostPredictionListener check : pseudoCheck.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            if (!MovementProfiles.forPlayer(player).shouldRunPseudoCheck(check.getClass())) {
                continue;
            }
            check.onPredictionComplete(complete);
        }
    }

    public void onBlockPlace(final BlockPlace place) {
        for (BlockPlaceCheck check : blockPlaceCheck.values()) {
            if (!shouldDispatch(check)) {
                continue;
            }
            check.onBlockPlace(place);
        }
    }

    public void queuePostFlyingBlockPlace(final BlockPlace place) {
        placesToCheck.add(place);
    }

    public void onBlockBreak(final BlockBreak blockBreak) {
        for (CheckListener check : blockBreakListeners) {
            if (!shouldDispatch(check)) {
                continue;
            }
            ((BlockBreakListener) check).onBlockBreak(blockBreak);
        }
    }

    public void queuePostFlyingBlockBreak(final BlockBreak blockBreak) {
        breaksToCheck.add(blockBreak);
    }


    private ExplosionHandler explosionHandlerCache;

    public ExplosionHandler getExplosionHandler() {
        if (explosionHandlerCache == null) {
            explosionHandlerCache = getListener(ExplosionHandler.class);
        }
        return explosionHandlerCache;
    }

    private SimulationProcessor cachedSimulationProcessor = null;

    public SimulationProcessor getSimulationProcessor() {
        if (cachedSimulationProcessor == null) {
            cachedSimulationProcessor = getListener(SimulationProcessor.class);
        }
        return cachedSimulationProcessor;
    }

    private SetbackTeleportUtil cachedSetbackUtil = null;

    public SetbackTeleportUtil getSetbackUtil() {
        if (cachedSetbackUtil == null) {
            cachedSetbackUtil = getListener(SetbackTeleportUtil.class);
        }
        return cachedSetbackUtil;
    }

    public NoFallExecutor getNoFall() {
        return getListener(NoFallExecutor.class);
    }

    private KnockbackHandler knockbackHandlerCache;

    public KnockbackHandler getKnockbackHandler() {
        if (knockbackHandlerCache == null) {
            knockbackHandlerCache = getListener(KnockbackHandler.class);
        }
        return knockbackHandlerCache;
    }

    private Angle angleCheck;
    public Angle getAngleCheck() {
        if (angleCheck == null) {
            angleCheck = getListener(Angle.class);
        }
        return angleCheck;
    }

    private CompensatedInventory inventory = null;

    public CompensatedInventory getInventory() {
        if (inventory == null) inventory = getListener(CompensatedInventory.class);
        return inventory;
    }

    public CompensatedCooldown getCompensatedCooldown() {
        return getListener(CompensatedCooldown.class);
    }

    public DebugHandler getDebugHandler() {
        return getListener(DebugHandler.class);
    }

    public OffsetHandler getOffsetHandler() {
        return getListener(OffsetHandler.class);
    }

    private record TickEndHandlerRegistration(ClientTickEndListener listener) {
        void handle(PacketReceiveEvent event) {
            listener.onPlayerTickEnd(event);
        }
    }

    private record EarlyReceiveHandlerRegistration(
            Class<? extends Packet<?>> packetType,
            PacketReceiveHandler<Packet<?>> handler
    ) {
        void handle(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
            if (packetType == null || packetType == packet.getClass()) {
                handler.handle(event, player, packet);
            }
        }
    }

}
