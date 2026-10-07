package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Named file NBT at the offline schematic/region boundary. */
final class OfflineNbt {
    private static final NbtValue.Compound EMPTY = new NbtValue.Compound(Map.of());

    private OfflineNbt() {}

    static NbtValue.Compound readCompressed(Path path) throws IOException {
        try (var input = new GZIPInputStream(Files.newInputStream(path))) {
            return read(input);
        }
    }

    static NbtValue.Compound read(InputStream source) throws IOException {
        var input = new DataInputStream(source);
        int type = input.readUnsignedByte();
        if (type != 10) throw new IOException("Expected compound file NBT");
        input.readUTF();
        byte[] payload = input.readAllBytes();
        byte[] network = new byte[payload.length + 1];
        network[0] = (byte) type;
        System.arraycopy(payload, 0, network, 1, payload.length);
        try {
            return (NbtValue.Compound) BinaryNbt.read(network);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid file NBT", failure);
        }
    }

    static void writeCompressed(NbtValue.Compound root, Path path) throws IOException {
        byte[] bytes = BinaryNbt.write(root);
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            output.writeByte(bytes[0]);
            output.writeUTF("");
            output.write(bytes, 1, bytes.length - 1);
        }
    }

    static NbtValue.Compound compound(NbtValue.Compound parent, String key) {
        return parent.values().get(key) instanceof NbtValue.Compound value ? value : EMPTY;
    }

    static int integer(NbtValue.Compound parent, String key) {
        return parent.values().get(key) instanceof NbtValue.Numeric value
                ? value.value().intValue()
                : 0;
    }
}
