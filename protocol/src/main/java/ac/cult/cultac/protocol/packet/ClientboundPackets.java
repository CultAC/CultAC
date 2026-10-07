package ac.cult.cultac.protocol.packet;

import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketCatalog;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.codec.connection.ClientboundKeepAliveCodec;
import ac.cult.cultac.protocol.codec.connection.DisconnectCodec;
import ac.cult.cultac.protocol.codec.connection.MountScreenOpenCodec;
import ac.cult.cultac.protocol.codec.connection.OpenScreenCodec;
import ac.cult.cultac.protocol.codec.connection.PingCodec;
import ac.cult.cultac.protocol.codec.connection.SetHeldSlotCodec;
import ac.cult.cultac.protocol.codec.entity.AddEntityCodec;
import ac.cult.cultac.protocol.codec.entity.AnimateCodec;
import ac.cult.cultac.protocol.codec.entity.CooldownCodec;
import ac.cult.cultac.protocol.codec.entity.DamageEventCodec;
import ac.cult.cultac.protocol.codec.entity.EntityEventCodec;
import ac.cult.cultac.protocol.codec.entity.EntityMotionCodec;
import ac.cult.cultac.protocol.codec.entity.EntityPositionSyncCodec;
import ac.cult.cultac.protocol.codec.entity.MoveEntityCodec;
import ac.cult.cultac.protocol.codec.entity.MoveMinecartCodec;
import ac.cult.cultac.protocol.codec.entity.PlayerCombatKillCodec;
import ac.cult.cultac.protocol.codec.entity.PlayerInfoUpdateCodec;
import ac.cult.cultac.protocol.codec.entity.RemoveEntitiesCodec;
import ac.cult.cultac.protocol.codec.entity.RemoveMobEffectCodec;
import ac.cult.cultac.protocol.codec.entity.SetCameraCodec;
import ac.cult.cultac.protocol.codec.entity.SetHealthCodec;
import ac.cult.cultac.protocol.codec.entity.SetPassengersCodec;
import ac.cult.cultac.protocol.codec.entity.SwingAnimationCodec;
import ac.cult.cultac.protocol.codec.entity.TeleportEntityCodec;
import ac.cult.cultac.protocol.codec.entity.UpdateAttributesCodec;
import ac.cult.cultac.protocol.codec.entity.UpdateMobEffectCodec;
import ac.cult.cultac.protocol.codec.movement.ClientboundMoveVehicleCodec;
import ac.cult.cultac.protocol.codec.movement.ClientboundPlayerAbilitiesCodec;
import ac.cult.cultac.protocol.codec.movement.PlayerPositionCodec;
import ac.cult.cultac.protocol.codec.movement.PlayerRotationCodec;
import ac.cult.cultac.protocol.codec.world.BlockChangedAckCodec;
import ac.cult.cultac.protocol.codec.world.BlockEventCodec;
import ac.cult.cultac.protocol.codec.world.ExplodeCodec;
import ac.cult.cultac.protocol.codec.world.ForgetLevelChunkCodec;
import ac.cult.cultac.protocol.codec.world.GameEventCodec;
import ac.cult.cultac.protocol.codec.world.InitializeBorderCodec;
import ac.cult.cultac.protocol.codec.world.LoginCodec;
import ac.cult.cultac.protocol.codec.world.RespawnCodec;
import ac.cult.cultac.protocol.codec.world.SetBorderCenterCodec;
import ac.cult.cultac.protocol.codec.world.SetBorderLerpSizeCodec;
import ac.cult.cultac.protocol.codec.world.SetBorderSizeCodec;
import ac.cult.cultac.protocol.codec.world.TickingStateCodec;
import ac.cult.cultac.protocol.codec.world.TickingStepCodec;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAnimate;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockChangedAck;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCooldown;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundDamageEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundDisconnect;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundExplode;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundForgetLevelChunk;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundGameEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundInitializeBorder;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundKeepAlive;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMountScreenOpen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveMinecart;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerAbilities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerCombatKill;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerRotation;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveMobEffect;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetBorderCenter;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetBorderLerpSize;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetBorderSize;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetCamera;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHealth;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHeldSlot;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSwingAnimation;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTickingState;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTickingStep;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateMobEffect;
import java.util.List;

/**
 * Clientbound families Cult routes, grouped by connection phase. Which names exist on a version comes from
 * that version's protocol data; {@code since} marks codecs verified only from a version onward.
 */
public final class ClientboundPackets {
    private static final PacketCatalog C = PacketCatalog.clientbound();
    private static final PacketCatalog.Scope PLAY = C.in(ConnectionPhase.PLAY);
    private static final PacketCatalog.Scope COMMON = C.in(ConnectionPhase.CONFIGURATION, ConnectionPhase.PLAY);

    // Login and configuration.
    public static final PacketType<Opaque> LOGIN_FINISHED =
            C.in(ConnectionPhase.LOGIN).ignored("login_finished");
    public static final PacketType<Opaque> FINISH_CONFIGURATION =
            C.in(ConnectionPhase.CONFIGURATION).empty("finish_configuration");
    public static final PacketType<Opaque> START_CONFIGURATION = PLAY.empty("start_configuration");

    // Configuration and play.
    public static final PacketType<ClientboundDisconnect> DISCONNECT =
            COMMON.add("disconnect", ClientboundDisconnect.class, new DisconnectCodec());
    public static final PacketType<ClientboundKeepAlive> KEEP_ALIVE =
            COMMON.add("keep_alive", ClientboundKeepAlive.class, new ClientboundKeepAliveCodec());
    public static final PacketType<ClientboundPing> PING = COMMON.add("ping", ClientboundPing.class, new PingCodec());

    // Player state.
    public static final PacketType<Opaque> BUNDLE_DELIMITER = PLAY.writableEmpty("bundle_delimiter");
    public static final PacketType<ClientboundLogin> LOGIN =
            PLAY.add("login", ClientboundLogin.class, new LoginCodec());
    public static final PacketType<ClientboundRespawn> RESPAWN =
            PLAY.add("respawn", ClientboundRespawn.class, new RespawnCodec());
    public static final PacketType<ClientboundPlayerPosition> PLAYER_POSITION =
            PLAY.add("player_position", ClientboundPlayerPosition.class, new PlayerPositionCodec());
    public static final PacketType<ClientboundPlayerRotation> PLAYER_ROTATION =
            PLAY.add("player_rotation", ClientboundPlayerRotation.class, new PlayerRotationCodec());
    public static final PacketType<ClientboundMoveVehicle> MOVE_VEHICLE =
            PLAY.add("move_vehicle", ClientboundMoveVehicle.class, new ClientboundMoveVehicleCodec());
    public static final PacketType<ClientboundPlayerAbilities> PLAYER_ABILITIES =
            PLAY.add("player_abilities", ClientboundPlayerAbilities.class, new ClientboundPlayerAbilitiesCodec());
    public static final PacketType<ClientboundSetHealth> SET_HEALTH =
            PLAY.add("set_health", ClientboundSetHealth.class, new SetHealthCodec());
    public static final PacketType<ClientboundPlayerCombatKill> PLAYER_COMBAT_KILL =
            PLAY.add("player_combat_kill", ClientboundPlayerCombatKill.class, new PlayerCombatKillCodec());
    public static final PacketType<ClientboundPlayerInfoUpdate> PLAYER_INFO_UPDATE =
            PLAY.add("player_info_update", ClientboundPlayerInfoUpdate.class, new PlayerInfoUpdateCodec());
    public static final PacketType<ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoRemove>
            PLAYER_INFO_REMOVE = PLAY.add(
                    "player_info_remove",
                    ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoRemove.class,
                    new ac.cult.cultac.protocol.codec.entity.PlayerInfoRemoveCodec());
    public static final PacketType<ClientboundCooldown> COOLDOWN =
            PLAY.add("cooldown", ClientboundCooldown.class, new CooldownCodec());
    public static final PacketType<ClientboundGameEvent> GAME_EVENT =
            PLAY.add("game_event", ClientboundGameEvent.class, new GameEventCodec());
    public static final PacketType<ClientboundExplode> EXPLODE =
            PLAY.add("explode", ClientboundExplode.class, new ExplodeCodec());

    // Entities.
    public static final PacketType<ClientboundAddEntity> ADD_ENTITY =
            PLAY.add("add_entity", ClientboundAddEntity.class, new AddEntityCodec());
    public static final PacketType<ClientboundRemoveEntities> REMOVE_ENTITIES =
            PLAY.add("remove_entities", ClientboundRemoveEntities.class, new RemoveEntitiesCodec());
    public static final PacketType<ClientboundMoveEntity> MOVE_ENTITY =
            PLAY.variants("move_entity", ClientboundMoveEntity.class, new MoveEntityCodec());
    public static final PacketType<ClientboundTeleportEntity> TELEPORT_ENTITY =
            PLAY.add("teleport_entity", ClientboundTeleportEntity.class, new TeleportEntityCodec());
    public static final PacketType<ClientboundEntityPositionSync> ENTITY_POSITION_SYNC =
            PLAY.add("entity_position_sync", ClientboundEntityPositionSync.class, new EntityPositionSyncCodec());
    public static final PacketType<ClientboundMoveMinecart> MOVE_MINECART = PLAY.add(
            "move_minecart", "move_minecart_along_track", ClientboundMoveMinecart.class, new MoveMinecartCodec());
    public static final PacketType<ClientboundEntityMotion> ENTITY_MOTION =
            PLAY.add("entity_motion", "set_entity_motion", ClientboundEntityMotion.class, new EntityMotionCodec());
    public static final PacketType<ClientboundSetPassengers> SET_PASSENGERS =
            PLAY.add("set_passengers", ClientboundSetPassengers.class, new SetPassengersCodec());
    public static final PacketType<ClientboundSetCamera> SET_CAMERA =
            PLAY.add("set_camera", ClientboundSetCamera.class, new SetCameraCodec());
    public static final PacketType<ClientboundEntityEvent> ENTITY_EVENT =
            PLAY.add("entity_event", ClientboundEntityEvent.class, new EntityEventCodec());
    public static final PacketType<ClientboundDamageEvent> DAMAGE_EVENT =
            PLAY.add("damage_event", ClientboundDamageEvent.class, new DamageEventCodec());
    public static final PacketType<ClientboundUpdateAttributes> UPDATE_ATTRIBUTES =
            PLAY.add("update_attributes", ClientboundUpdateAttributes.class, new UpdateAttributesCodec());
    public static final PacketType<ClientboundUpdateMobEffect> UPDATE_MOB_EFFECT =
            PLAY.add("update_mob_effect", ClientboundUpdateMobEffect.class, new UpdateMobEffectCodec());
    public static final PacketType<ClientboundRemoveMobEffect> REMOVE_MOB_EFFECT =
            PLAY.add("remove_mob_effect", ClientboundRemoveMobEffect.class, new RemoveMobEffectCodec());
    public static final PacketType<ClientboundAnimate> ANIMATE =
            PLAY.add("animate", ClientboundAnimate.class, new AnimateCodec());
    public static final PacketType<ClientboundSwingAnimation> SWING_ANIMATION =
            PLAY.add("swing_animation", ClientboundSwingAnimation.class, new SwingAnimationCodec());

    // World.
    public static final PacketType<ClientboundForgetLevelChunk> FORGET_LEVEL_CHUNK = PLAY.add(
            "forget_chunk", "forget_level_chunk", ClientboundForgetLevelChunk.class, new ForgetLevelChunkCodec());
    public static final PacketType<ClientboundBlockEvent> BLOCK_EVENT =
            PLAY.add("block_event", ClientboundBlockEvent.class, new BlockEventCodec());
    public static final PacketType<ClientboundBlockChangedAck> BLOCK_CHANGED_ACK =
            PLAY.add("block_changed_ack", ClientboundBlockChangedAck.class, new BlockChangedAckCodec());
    public static final PacketType<ClientboundInitializeBorder> INITIALIZE_BORDER =
            PLAY.add("initialize_border", ClientboundInitializeBorder.class, new InitializeBorderCodec());
    public static final PacketType<ClientboundSetBorderCenter> SET_BORDER_CENTER =
            PLAY.add("set_border_center", ClientboundSetBorderCenter.class, new SetBorderCenterCodec());
    public static final PacketType<ClientboundSetBorderLerpSize> SET_BORDER_LERP_SIZE =
            PLAY.add("set_border_lerp_size", ClientboundSetBorderLerpSize.class, new SetBorderLerpSizeCodec());
    public static final PacketType<ClientboundSetBorderSize> SET_BORDER_SIZE =
            PLAY.add("set_border_size", ClientboundSetBorderSize.class, new SetBorderSizeCodec());
    public static final PacketType<ClientboundTickingState> TICKING_STATE =
            PLAY.add("ticking_state", ClientboundTickingState.class, new TickingStateCodec());
    public static final PacketType<ClientboundTickingStep> TICKING_STEP =
            PLAY.add("ticking_step", ClientboundTickingStep.class, new TickingStepCodec());

    // Inventory. Source-version layouts are audited for every supported release.
    public static final PacketType<Opaque> CONTAINER_CLOSE = PLAY.ignored("container_close");
    public static final PacketType<ClientboundSetHeldSlot> SET_HELD_SLOT =
            PLAY.add("set_held_slot", ClientboundSetHeldSlot.class, new SetHeldSlotCodec());
    public static final PacketType<ClientboundMountScreenOpen> MOUNT_SCREEN_OPEN = PLAY.renamed(
            "mount_screen_open",
            ClientboundMountScreenOpen.class,
            new MountScreenOpenCodec(),
            "mount_screen_open",
            "horse_screen_open");
    public static final PacketType<ClientboundOpenScreen> OPEN_SCREEN =
            PLAY.add("open_screen", ClientboundOpenScreen.class, new OpenScreenCodec());

    private static final List<PacketType<?>> ALL = C.types();

    private ClientboundPackets() {}

    public static List<PacketType<?>> all() {
        return ALL;
    }
}
