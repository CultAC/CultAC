package ac.grim.grimac.bedrock.replay.offline;

import ac.grim.grimac.bedrock.prediction.geometry.BedrockPositionTranslator;
import ac.grim.grimac.bedrock.protocol.BedrockAuthInputFrame;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

final class OfflineBedrockAuthInputCapture {
   private OfflineBedrockAuthInputCapture() {
   }

   static List<BedrockAuthInputFrame> read(Path packetsPath, UUID playerUuid) throws IOException {
      List<BedrockAuthInputFrame> frames = new ArrayList<>();
      Vec3 previousPosition = null;

      String line;
      try (BufferedReader reader = Files.newBufferedReader(packetsPath, StandardCharsets.UTF_8)) {
         while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
               JsonObject packet = JsonParser.parseString(line).getAsJsonObject();
               if ("PLAYER_AUTH_INPUT".equals(string(packet, "packetType", ""))) {
                  Vec3 position = feetPosition(packet);
                  Vec3 delta = previousPosition == null ? Vec3.ZERO : position.subtract(previousPosition);
                  previousPosition = position;
                  frames.add(toFrame(packet, playerUuid, position, delta));
               }
            }
         }
      }

      return List.copyOf(frames);
   }

   private static BedrockAuthInputFrame toFrame(JsonObject packet, UUID playerUuid, Vec3 feet, Vec3 delta) {
      JsonObject decoded = packet.getAsJsonObject("decoded");
      JsonObject packetPosition = decoded.getAsJsonObject("position");
      JsonObject rotation = decoded.getAsJsonObject("rotation");
      Vec3 packetPos = vec3(packetPosition);
      OfflineBedrockAuthInputCapture.RawInputFlags rawInputFlags = rawInputFlags(decoded.getAsJsonArray("inputFlags"));
      float[] moveVector = moveVector(decoded);
      return BedrockAuthInputFrame.builder(playerUuid)
         .protocolVersion(intValue(packet, "protocolVersion", 0))
         .clientTick(longValue(decoded, "tick", 0L))
         .inputMode(inputMode(decoded))
         .playMode(playMode(decoded))
         .position(feet)
         .packetPosition(packetPos)
         .delta(delta)
         .rotation((float)doubleValue(rotation, "y", 0.0), (float)doubleValue(rotation, "x", 0.0), (float)doubleValue(rotation, "z", 0.0))
         .moveVector(moveVector[0], moveVector[1])
         .rawInputFlags(rawInputFlags.low())
         .rawInputFlagsHigh(rawInputFlags.high())
         .jumping(
            hasAnyFlag(
               rawInputFlags,
               PlayerAuthInputData.JUMP_CURRENT_RAW,
               PlayerAuthInputData.JUMP_DOWN,
               PlayerAuthInputData.JUMPING,
               PlayerAuthInputData.START_JUMPING,
               PlayerAuthInputData.AUTO_JUMPING_IN_WATER
            )
         )
         .jumpStarted(hasFlag(rawInputFlags, PlayerAuthInputData.START_JUMPING))
         .jumpPressedRaw(hasFlag(rawInputFlags, PlayerAuthInputData.JUMP_PRESSED_RAW))
         .jumpCurrentRaw(hasFlag(rawInputFlags, PlayerAuthInputData.JUMP_CURRENT_RAW))
         .wantUp(hasFlag(rawInputFlags, PlayerAuthInputData.WANT_UP))
         .sneaking(
            hasAnyFlag(
               rawInputFlags,
               PlayerAuthInputData.SNEAK_CURRENT_RAW,
               PlayerAuthInputData.SNEAK_DOWN,
               PlayerAuthInputData.SNEAKING,
               PlayerAuthInputData.START_SNEAKING,
               PlayerAuthInputData.DESCEND,
               PlayerAuthInputData.SNEAK_TOGGLE_DOWN
            )
         )
         .startSneaking(
            hasAnyFlag(
               rawInputFlags,
               PlayerAuthInputData.START_SNEAKING,
               PlayerAuthInputData.SNEAK_PRESSED_RAW,
               PlayerAuthInputData.SNEAKING,
               PlayerAuthInputData.SNEAK_DOWN,
               PlayerAuthInputData.SNEAK_CURRENT_RAW
            )
         )
         .stopSneaking(hasFlag(rawInputFlags, PlayerAuthInputData.STOP_SNEAKING))
         .sprinting(hasFlag(rawInputFlags, PlayerAuthInputData.SPRINTING))
         .startSwimming(hasFlag(rawInputFlags, PlayerAuthInputData.START_SWIMMING))
         .stopSwimming(hasFlag(rawInputFlags, PlayerAuthInputData.STOP_SWIMMING))
         .startCrawling(hasFlag(rawInputFlags, PlayerAuthInputData.START_CRAWLING))
         .stopCrawling(hasFlag(rawInputFlags, PlayerAuthInputData.STOP_CRAWLING))
         .startGliding(hasFlag(rawInputFlags, PlayerAuthInputData.START_GLIDING))
         .stopGliding(hasFlag(rawInputFlags, PlayerAuthInputData.STOP_GLIDING))
         .usingItem(
            hasAnyFlag(
               rawInputFlags,
               PlayerAuthInputData.PERFORM_ITEM_INTERACTION,
               PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST,
               PlayerAuthInputData.START_USING_ITEM
            )
         )
         .blockAction(hasFlag(rawInputFlags, PlayerAuthInputData.PERFORM_BLOCK_ACTIONS))
         .authorityMode("client-auth-input")
         .build();
   }

   private static Vec3 feetPosition(JsonObject packet) {
      JsonObject decoded = packet.getAsJsonObject("decoded");
      Vec3 packetPos = vec3(decoded.getAsJsonObject("position"));
      return new Vec3(
         BedrockPositionTranslator.packetFloatToDouble(packetPos.x),
         BedrockPositionTranslator.packetFloatToDouble(packetPos.y) - 1.6200104F,
         BedrockPositionTranslator.packetFloatToDouble(packetPos.z)
      );
   }

   private static OfflineBedrockAuthInputCapture.RawInputFlags rawInputFlags(JsonArray flags) {
      long low = 0L;
      long high = 0L;
      if (flags == null) {
         return new OfflineBedrockAuthInputCapture.RawInputFlags(low, high);
      }

      for (JsonElement element : flags) {
         String name = element.getAsString();

         try {
            PlayerAuthInputData input = PlayerAuthInputData.valueOf(name);
            if (input.ordinal() < 64) {
               low |= 1L << input.ordinal();
            } else if (input.ordinal() < 128) {
               high |= 1L << input.ordinal() - 64;
            }
         } catch (IllegalArgumentException var9) {
         }
      }

      return new OfflineBedrockAuthInputCapture.RawInputFlags(low, high);
   }

   private static long inputFlagMask(PlayerAuthInputData input) {
      return input.ordinal() < 64 ? 1L << input.ordinal() : 0L;
   }

   private static boolean hasFlag(OfflineBedrockAuthInputCapture.RawInputFlags flags, PlayerAuthInputData input) {
      int ordinal = input.ordinal();
      return ordinal < 64 ? (flags.low() & 1L << ordinal) != 0L : ordinal < 128 && (flags.high() & 1L << ordinal - 64) != 0L;
   }

   private static boolean hasAnyFlag(OfflineBedrockAuthInputCapture.RawInputFlags flags, PlayerAuthInputData... inputs) {
      for (PlayerAuthInputData input : inputs) {
         if (hasFlag(flags, input)) {
            return true;
         }
      }

      return false;
   }

   private static float[] moveVector(JsonObject decoded) {
      JsonObject motion = decoded.getAsJsonObject("motion");
      return isNonZeroVector(motion) ? vector2(motion) : new float[]{0.0F, 0.0F};
   }

   private static boolean isNonZeroVector(JsonObject object) {
      return object != null && (Math.abs(doubleValue(object, "x", 0.0)) > 1.0E-6 || Math.abs(doubleValue(object, "y", 0.0)) > 1.0E-6);
   }

   private static float[] vector2(JsonObject object) {
      return new float[]{(float)doubleValue(object, "x", 0.0), (float)doubleValue(object, "y", 0.0)};
   }

   private static Vec3 vec3(JsonObject object) {
      return new Vec3(doubleValue(object, "x", 0.0), doubleValue(object, "y", 0.0), doubleValue(object, "z", 0.0));
   }

   private static int inputMode(JsonObject decoded) {
      return enumOrdinal(InputMode.class, string(decoded, "inputMode", ""));
   }

   private static int playMode(JsonObject decoded) {
      return enumOrdinal(ClientPlayMode.class, string(decoded, "playMode", ""));
   }

   private static <T extends Enum<T>> int enumOrdinal(Class<T> enumType, String name) {
      if (name != null && !name.isBlank()) {
         try {
            return Enum.<T>valueOf(enumType, name.trim()).ordinal();
         } catch (IllegalArgumentException ignored) {
            return -1;
         }
      } else {
         return -1;
      }
   }

   private static String string(JsonObject object, String name, String fallback) {
      JsonElement value = object == null ? null : object.get(name);
      return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
   }

   private static boolean booleanValue(JsonObject object, String name, boolean fallback) {
      JsonElement value = object == null ? null : object.get(name);
      return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
   }

   private static int intValue(JsonObject object, String name, int fallback) {
      JsonElement value = object == null ? null : object.get(name);
      return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
   }

   private static long longValue(JsonObject object, String name, long fallback) {
      JsonElement value = object == null ? null : object.get(name);
      return value != null && value.isJsonPrimitive() ? value.getAsLong() : fallback;
   }

   private static double doubleValue(JsonObject object, String name, double fallback) {
      JsonElement value = object == null ? null : object.get(name);
      return value != null && value.isJsonPrimitive() ? value.getAsDouble() : fallback;
   }

   private record RawInputFlags(long low, long high) {
   }
}
