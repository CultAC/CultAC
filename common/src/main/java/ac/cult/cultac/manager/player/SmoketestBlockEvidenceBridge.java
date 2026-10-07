package ac.cult.cultac.manager.player;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

/** Compares captured client prediction bookkeeping without changing simulated state. */
final class SmoketestBlockEvidenceBridge {
    private SmoketestBlockEvidenceBridge() {}

    static boolean handle(CultPlayer player, String channel, byte[] data) {
        if (!"cult:smoketest_block_evidence".equals(channel)) return false;
        try {
            if (data.length >= 2 && data[0] == (byte) 0x1f && data[1] == (byte) 0x8b) {
                try (var gzip = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(data))) {
                    data = gzip.readNBytes(1024 * 1024 + 1);
                    if (data.length > 1024 * 1024) throw new IOException("evidence exceeds 1 MiB");
                }
            }
            var input = new DataInputStream(new ByteArrayInputStream(data));
            if (input.readInt() != 0x42534531 || input.readInt() != 1)
                throw new IOException("unsupported evidence format");
            int tick = input.readInt();
            String scenario = input.readUTF();
            int count = input.readInt();
            if (count < 0 || count > input.available() / 20) throw new IOException("invalid prediction count");
            var modeled = new HashMap<BlockPos, ac.cult.cultac.utils.latency.CompensatedWorld.PendingPrediction>();
            for (var prediction : player.compensatedWorld.pendingPredictionSnapshot()) {
                modeled.put(prediction.position(), prediction);
            }
            var mismatches = new ArrayList<String>();
            for (int i = 0; i < count; i++) {
                var pos = new BlockPos(input.readInt(), input.readInt(), input.readInt());
                int sequence = input.readInt();
                String retained = input.readUTF(), predicted = input.readUTF();
                var actual = modeled.remove(pos);
                if (actual == null
                        || actual.sequence() != sequence
                        || !SmoketestSnapshotBridge.blockStatesMatch(retained, actual.retained())
                        || !SmoketestSnapshotBridge.blockStatesMatch(predicted, actual.predicted())) {
                    mismatches.add(
                            pos + " expected=" + sequence + ":" + retained + " -> " + predicted + " cult=" + actual);
                }
            }
            if (!modeled.isEmpty()) mismatches.add("extra Cult predictions=" + modeled.values());
            input.readBoolean(); // destroying
            input.readFloat(); // destroyProgress
            input.readUTF(); // destroyTarget
            int results = input.readInt();
            if (results < 0 || results > input.available() / 2) throw new IOException("invalid interaction count");
            for (int i = 0; i < results; i++) input.readUTF();
            if (input.available() != 0) throw new IOException("trailing evidence bytes");
            String label =
                    " player=" + player.getName() + " tick=" + tick + " scenario=" + scenario + " pending=" + count;
            if (mismatches.isEmpty()) LogUtil.info("Cult smoketest block evidence passed" + label);
            else
                LogUtil.warn(
                        "Cult smoketest block evidence failed" + label + " details=" + String.join(" | ", mismatches));
        } catch (IOException | RuntimeException failure) {
            LogUtil.warn("Cult smoketest block evidence failed player=" + player.getName() + " decode=" + failure);
        }
        return true;
    }
}
