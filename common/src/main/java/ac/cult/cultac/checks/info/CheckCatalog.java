package ac.cult.cultac.checks.info;

import static ac.cult.cultac.checks.info.CheckCategory.BUILDING;
import static ac.cult.cultac.checks.info.CheckCategory.CHAT;
import static ac.cult.cultac.checks.info.CheckCategory.CLOUD;
import static ac.cult.cultac.checks.info.CheckCategory.COMBAT;
import static ac.cult.cultac.checks.info.CheckCategory.CRASH;
import static ac.cult.cultac.checks.info.CheckCategory.ELYTRA;
import static ac.cult.cultac.checks.info.CheckCategory.INVENTORY;
import static ac.cult.cultac.checks.info.CheckCategory.MOVEMENT;
import static ac.cult.cultac.checks.info.CheckCategory.PACKETS;
import static ac.cult.cultac.checks.info.CheckCategory.VEHICLES;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.aim.AimDuplicateLook;
import ac.cult.cultac.checks.impl.aim.AimModulo360;
import ac.cult.cultac.checks.impl.autoclicker.AutoclickerLimit;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsA;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsB;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsC;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsD;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsE;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsF;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsG;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsH;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsI;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsK;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsL;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsM;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsN;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsO;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsP;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsQ;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsR;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsS;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsT;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsU;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsV;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsW;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsWindow;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsX;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsY;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsZ;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.checks.impl.breaking.AirLiquidBreak;
import ac.cult.cultac.checks.impl.breaking.FarBreak;
import ac.cult.cultac.checks.impl.breaking.FastBreak;
import ac.cult.cultac.checks.impl.breaking.InvalidBreak;
import ac.cult.cultac.checks.impl.breaking.MultiBreak;
import ac.cult.cultac.checks.impl.breaking.NoSwingBreak;
import ac.cult.cultac.checks.impl.breaking.PositionBreakA;
import ac.cult.cultac.checks.impl.breaking.PositionBreakB;
import ac.cult.cultac.checks.impl.breaking.RotationBreak;
import ac.cult.cultac.checks.impl.breaking.WrongBreak;
import ac.cult.cultac.checks.impl.chat.ChatA;
import ac.cult.cultac.checks.impl.chat.ChatB;
import ac.cult.cultac.checks.impl.chat.ChatC;
import ac.cult.cultac.checks.impl.chat.ChatD;
import ac.cult.cultac.checks.impl.chat.ChatE;
import ac.cult.cultac.checks.impl.cloud.Cloud;
import ac.cult.cultac.checks.impl.cloud.CloudAutomation;
import ac.cult.cultac.checks.impl.cloud.CloudAutomationExperimental;
import ac.cult.cultac.checks.impl.cloud.CloudClient;
import ac.cult.cultac.checks.impl.cloud.CloudClientExperimental;
import ac.cult.cultac.checks.impl.cloud.CloudCombat;
import ac.cult.cultac.checks.impl.cloud.CloudCombatExperimental;
import ac.cult.cultac.checks.impl.cloud.CloudExploit;
import ac.cult.cultac.checks.impl.cloud.CloudExploitExperimental;
import ac.cult.cultac.checks.impl.combat.EntityHitboxes;
import ac.cult.cultac.checks.impl.combat.EntityReach;
import ac.cult.cultac.checks.impl.combat.FairReach;
import ac.cult.cultac.checks.impl.combat.KeepSprint;
import ac.cult.cultac.checks.impl.combat.MultiInteractA;
import ac.cult.cultac.checks.impl.combat.MultiInteractB;
import ac.cult.cultac.checks.impl.combat.PlayerHitboxes;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.impl.combat.SelfInteract;
import ac.cult.cultac.checks.impl.crash.CrashA;
import ac.cult.cultac.checks.impl.crash.CrashB;
import ac.cult.cultac.checks.impl.crash.CrashC;
import ac.cult.cultac.checks.impl.crash.CrashD;
import ac.cult.cultac.checks.impl.crash.CrashE;
import ac.cult.cultac.checks.impl.crash.CrashF;
import ac.cult.cultac.checks.impl.crash.CrashG;
import ac.cult.cultac.checks.impl.crash.CrashH;
import ac.cult.cultac.checks.impl.crash.CrashI;
import ac.cult.cultac.checks.impl.elytra.ElytraA;
import ac.cult.cultac.checks.impl.elytra.ElytraB;
import ac.cult.cultac.checks.impl.elytra.ElytraC;
import ac.cult.cultac.checks.impl.elytra.ElytraD;
import ac.cult.cultac.checks.impl.elytra.ElytraE;
import ac.cult.cultac.checks.impl.elytra.ElytraF;
import ac.cult.cultac.checks.impl.elytra.ElytraG;
import ac.cult.cultac.checks.impl.elytra.ElytraH;
import ac.cult.cultac.checks.impl.elytra.ElytraI;
import ac.cult.cultac.checks.impl.elytra.ElytraJ;
import ac.cult.cultac.checks.impl.exploit.ExploitA;
import ac.cult.cultac.checks.impl.exploit.ExploitB;
import ac.cult.cultac.checks.impl.exploit.ExploitC;
import ac.cult.cultac.checks.impl.exploit.ExploitF;
import ac.cult.cultac.checks.impl.groundspoof.NoFallExecutor;
import ac.cult.cultac.checks.impl.misc.SkinBlinker;
import ac.cult.cultac.checks.impl.movement.EntityControl;
import ac.cult.cultac.checks.impl.movement.timer.ItemUseBurst;
import ac.cult.cultac.checks.impl.movement.timer.NegativeTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TickTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsA;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsB;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsC;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsD;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsE;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsF;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsG;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsH;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsI;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsJ;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsK;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsL;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsM;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsN;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsO;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsP;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsQ;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsR;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsS;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsT;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderA;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderB;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderC;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderD;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderE;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderF;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderG;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderH;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderI;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderJ;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderK;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderL;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderM;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderN;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderO;
import ac.cult.cultac.checks.impl.ping.PingA;
import ac.cult.cultac.checks.impl.ping.TransactionOrder;
import ac.cult.cultac.checks.impl.post.PostCheck;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.checks.NoSlow;
import ac.cult.cultac.checks.impl.prediction.checks.Phase;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Angle;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.BoatPseudo;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.ElytraPseudo;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.HappyGhastPseudo;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.InputKeys;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.InvalidStep;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoSneakSlow;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.OmniSprint;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.RiptideOffset;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Speed;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Strafe;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VerticalOffset;
import ac.cult.cultac.checks.impl.prediction.runner.ExplosionHandler;
import ac.cult.cultac.checks.impl.prediction.runner.KnockbackHandler;
import ac.cult.cultac.checks.impl.scaffolding.AirLiquidPlace;
import ac.cult.cultac.checks.impl.scaffolding.DuplicateRotPlace;
import ac.cult.cultac.checks.impl.scaffolding.FabricatedPlace;
import ac.cult.cultac.checks.impl.scaffolding.FarPlace;
import ac.cult.cultac.checks.impl.scaffolding.InvalidPlaceA;
import ac.cult.cultac.checks.impl.scaffolding.InvalidPlaceB;
import ac.cult.cultac.checks.impl.scaffolding.MultiPlace;
import ac.cult.cultac.checks.impl.scaffolding.RotationPlace;
import ac.cult.cultac.checks.impl.sprint.SprintA;
import ac.cult.cultac.checks.impl.sprint.SprintB;
import ac.cult.cultac.checks.impl.sprint.SprintC;
import ac.cult.cultac.checks.impl.sprint.SprintD;
import ac.cult.cultac.checks.impl.sprint.SprintE;
import ac.cult.cultac.checks.impl.sprint.SprintF;
import ac.cult.cultac.checks.impl.sprint.SprintG;
import ac.cult.cultac.checks.impl.sprint.SprintH;
import ac.cult.cultac.checks.impl.vehicle.VehicleA;
import ac.cult.cultac.checks.impl.vehicle.VehicleB;
import ac.cult.cultac.checks.impl.vehicle.VehicleC;
import ac.cult.cultac.checks.impl.vehicle.VehicleD;
import ac.cult.cultac.checks.impl.vehicle.VehicleE;
import ac.cult.cultac.checks.impl.vehicle.VehicleF;
import ac.cult.cultac.checks.impl.visibility.AttackMobVisibility;
import ac.cult.cultac.checks.impl.visibility.AttackPlayerVisibility;
import ac.cult.cultac.checks.impl.visibility.BreakVisibility;
import ac.cult.cultac.checks.impl.visibility.InteractEntityVisibility;
import ac.cult.cultac.checks.impl.visibility.PlaceVisibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * Every check a player builds, listed without a player. Identity comes from each check's {@code @CheckData} or
 * {@code CheckInfo}; only the category is kept here. {@code CheckCatalogTest} fails when the two differ.
 */
public final class CheckCatalog {
    /**
     * One check's identity as the running check reports it.
     *
     * @param name the check name punishments match and alerts show by default
     * @param configName the prefix of the check's own config keys
     * @param altName the second name punishments match; {@link CheckData#NO_ALTERNATIVE_NAME} for a {@code @CheckData}
     *     check without one, {@code null} for a {@code CheckInfo} check without one
     */
    public record Entry(
            String name, String configName, @Nullable String altName, boolean experimental, CheckCategory category) {}

    public static final List<Entry> ENTRIES = List.of(
            check(AimDuplicateLook.class, COMBAT),
            check(AimModulo360.class, COMBAT),
            check(ExplosionHandler.INFO, COMBAT),
            check(KnockbackHandler.INFO, COMBAT),
            check(AttackMobVisibility.class, COMBAT),
            check(AttackPlayerVisibility.class, COMBAT),
            check(AutoclickerLimit.INFO, COMBAT),
            check(EntityHitboxes.class, COMBAT),
            check(EntityReach.INFO, COMBAT),
            check(FairReach.INFO, COMBAT),
            check(InteractEntityVisibility.class, COMBAT),
            check(KeepSprint.INFO, COMBAT),
            check(MultiActionsA.class, COMBAT),
            check(MultiActionsE.class, COMBAT),
            check(MultiActionsF.class, COMBAT),
            check(MultiInteractA.class, COMBAT),
            check(MultiInteractB.class, COMBAT),
            check(PlayerHitboxes.class, COMBAT),
            check(PlayerReach.INFO, COMBAT),
            check(SelfInteract.class, COMBAT),
            check(Angle.INFO, MOVEMENT),
            check(BedrockMovement.INFO, MOVEMENT),
            check(NoFallPseudo.INFO, MOVEMENT),
            check(InputKeys.INFO, MOVEMENT),
            check(InvalidStep.INFO, MOVEMENT),
            check(ItemUseBurst.INFO, MOVEMENT),
            check(NegativeTimerCheck.INFO, MOVEMENT),
            check(NoFallExecutor.INFO, MOVEMENT),
            check(NoSlow.INFO, MOVEMENT),
            check(NoSneakSlow.INFO, MOVEMENT),
            check(OmniSprint.INFO, MOVEMENT),
            check(Phase.INFO, MOVEMENT),
            check(RiptideOffset.INFO, MOVEMENT),
            check(OffsetHandler.INFO, MOVEMENT),
            check(Speed.INFO, MOVEMENT),
            check(SprintA.class, MOVEMENT),
            check(SprintB.class, MOVEMENT),
            check(SprintC.class, MOVEMENT),
            check(SprintD.class, MOVEMENT),
            check(SprintE.class, MOVEMENT),
            check(SprintF.class, MOVEMENT),
            check(SprintG.class, MOVEMENT),
            check(SprintH.class, MOVEMENT),
            check(Strafe.INFO, MOVEMENT),
            check(TickTimer.class, MOVEMENT),
            check(TimerCheck.INFO, MOVEMENT),
            check(VerticalOffset.INFO, MOVEMENT),
            check(ElytraPseudo.INFO, ELYTRA),
            check(ElytraA.class, ELYTRA),
            check(ElytraB.class, ELYTRA),
            check(ElytraC.class, ELYTRA),
            check(ElytraD.class, ELYTRA),
            check(ElytraE.class, ELYTRA),
            check(ElytraF.class, ELYTRA),
            check(ElytraG.class, ELYTRA),
            check(ElytraH.class, ELYTRA),
            check(ElytraI.class, ELYTRA),
            check(ElytraJ.class, ELYTRA),
            check(BoatPseudo.INFO, VEHICLES),
            check(EntityControl.INFO, VEHICLES),
            check(HappyGhastPseudo.INFO, VEHICLES),
            check(MultiActionsG.class, VEHICLES),
            check(VehicleTimer.INFO, VEHICLES),
            check(VehicleA.class, VEHICLES),
            check(VehicleB.class, VEHICLES),
            check(VehicleC.class, VEHICLES),
            check(VehicleD.class, VEHICLES),
            check(VehicleE.class, VEHICLES),
            check(VehicleF.class, VEHICLES),
            check(VehicleOffset.INFO, VEHICLES),
            check(AirLiquidBreak.class, BUILDING),
            check(AirLiquidPlace.class, BUILDING),
            check(BreakVisibility.class, BUILDING),
            check(DuplicateRotPlace.class, BUILDING),
            check(FabricatedPlace.class, BUILDING),
            check(FarBreak.class, BUILDING),
            check(FarPlace.class, BUILDING),
            check(FastBreak.class, BUILDING),
            check(InvalidBreak.class, BUILDING),
            check(InvalidPlaceA.class, BUILDING),
            check(InvalidPlaceB.class, BUILDING),
            check(MultiActionsB.class, BUILDING),
            check(MultiBreak.class, BUILDING),
            check(MultiPlace.class, BUILDING),
            check(NoSwingBreak.class, BUILDING),
            check(PlaceVisibility.class, BUILDING),
            check(PositionBreakA.class, BUILDING),
            check(PositionBreakB.class, BUILDING),
            check(RotationBreak.class, BUILDING),
            check(RotationPlace.class, BUILDING),
            check(WrongBreak.class, BUILDING),
            check(MultiActionsC.class, INVENTORY),
            check(MultiActionsD.class, INVENTORY),
            check(MultiActionsH.class, INVENTORY),
            check(MultiActionsI.class, INVENTORY),
            check(MultiActionsJ.class, INVENTORY),
            check(MultiActionsK.class, INVENTORY),
            check(MultiActionsL.class, INVENTORY),
            check(MultiActionsM.class, INVENTORY),
            check(MultiActionsN.class, INVENTORY),
            check(MultiActionsO.class, INVENTORY),
            check(MultiActionsP.class, INVENTORY),
            check(MultiActionsQ.class, INVENTORY),
            check(MultiActionsR.class, INVENTORY),
            check(MultiActionsS.class, INVENTORY),
            check(MultiActionsT.class, INVENTORY),
            check(ChatA.class, CHAT),
            check(ChatB.class, CHAT),
            check(ChatC.class, CHAT),
            check(ChatD.class, CHAT),
            check(ChatE.class, CHAT),
            check(BadPacketsA.class, PACKETS),
            check(BadPacketsB.class, PACKETS),
            check(BadPacketsC.class, PACKETS),
            check(BadPacketsD.class, PACKETS),
            check(BadPacketsE.class, PACKETS),
            check(BadPacketsF.class, PACKETS),
            check(BadPacketsG.class, PACKETS),
            check(BadPacketsH.class, PACKETS),
            check(BadPacketsI.class, PACKETS),
            check(BadPacketsJ.class, PACKETS),
            check(BadPacketsK.class, PACKETS),
            check(BadPacketsL.class, PACKETS),
            check(BadPacketsM.class, PACKETS),
            check(BadPacketsN.class, PACKETS),
            check(BadPacketsO.class, PACKETS),
            check(BadPacketsP.class, PACKETS),
            check(BadPacketsQ.class, PACKETS),
            check(BadPacketsR.class, PACKETS),
            check(BadPacketsS.class, PACKETS),
            check(BadPacketsT.class, PACKETS),
            check(BadPacketsU.class, PACKETS),
            check(BadPacketsV.class, PACKETS),
            check(BadPacketsVehicle.class, PACKETS),
            check(BadPacketsW.class, PACKETS),
            check(BadPacketsWindow.class, PACKETS),
            check(BadPacketsX.class, PACKETS),
            check(BadPacketsY.class, PACKETS),
            check(BadPacketsZ.class, PACKETS),
            check(ExploitF.class, PACKETS),
            check(PacketOrderA.class, PACKETS),
            check(PacketOrderB.class, PACKETS),
            check(PacketOrderC.class, PACKETS),
            check(PacketOrderD.class, PACKETS),
            check(PacketOrderE.class, PACKETS),
            check(PacketOrderF.class, PACKETS),
            check(PacketOrderG.class, PACKETS),
            check(PacketOrderH.class, PACKETS),
            check(PacketOrderI.class, PACKETS),
            check(PacketOrderJ.class, PACKETS),
            check(PacketOrderK.class, PACKETS),
            check(PacketOrderL.class, PACKETS),
            check(PacketOrderM.class, PACKETS),
            check(PacketOrderN.class, PACKETS),
            check(PacketOrderO.class, PACKETS),
            check(PingA.class, PACKETS),
            check(PostCheck.INFO, PACKETS),
            check(SkinBlinker.class, PACKETS),
            check(TransactionOrder.class, PACKETS),
            check(CrashA.class, CRASH),
            check(CrashB.class, CRASH),
            check(CrashC.class, CRASH),
            check(CrashD.class, CRASH),
            check(CrashE.class, CRASH),
            check(CrashF.class, CRASH),
            check(CrashG.class, CRASH),
            check(CrashH.class, CRASH),
            check(CrashI.class, CRASH),
            check(ExploitA.class, CRASH),
            check(ExploitB.class, CRASH),
            check(ExploitC.class, CRASH),
            check(Cloud.class, CLOUD),
            check(CloudCombat.class, CLOUD),
            check(CloudCombatExperimental.class, CLOUD),
            check(CloudAutomation.class, CLOUD),
            check(CloudAutomationExperimental.class, CLOUD),
            check(CloudClient.class, CLOUD),
            check(CloudClientExperimental.class, CLOUD),
            check(CloudExploit.class, CLOUD),
            check(CloudExploitExperimental.class, CLOUD));

    private CheckCatalog() {}

    private static Entry check(Class<? extends Check> type, CheckCategory category) {
        CheckData data = type.getAnnotation(CheckData.class);
        if (data == null) throw new IllegalArgumentException(type.getName() + " has no @CheckData");
        return new Entry(
                data.name(),
                Check.configName(data.name(), data.configName()),
                data.alternativeName(),
                data.experimental(),
                category);
    }

    private static Entry check(CheckInfo info, CheckCategory category) {
        return new Entry(
                info.getName(),
                Check.configName(info.getName(), info.getConfigName()),
                info.getAltName(),
                false,
                category);
    }

    public static @Nullable Entry find(String query) {
        for (Entry entry : ENTRIES) {
            if (entry.name().equalsIgnoreCase(query)
                    || entry.configName().equalsIgnoreCase(query)
                    || (hasAltName(entry) && entry.altName().equalsIgnoreCase(query))) return entry;
        }
        return null;
    }

    private static boolean hasAltName(Entry entry) {
        return entry.altName() != null && !entry.altName().equals(CheckData.NO_ALTERNATIVE_NAME);
    }

    public static List<Entry> byCategory(CheckCategory category) {
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.category() == category) entries.add(entry);
        }
        return entries;
    }

    public static List<Entry> containing(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.name().toLowerCase(Locale.ROOT).contains(lower)) entries.add(entry);
        }
        return entries;
    }
}
