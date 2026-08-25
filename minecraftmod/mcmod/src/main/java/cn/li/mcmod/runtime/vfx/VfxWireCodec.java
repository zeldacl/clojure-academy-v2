package cn.li.mcmod.runtime.vfx;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Fixed, bounded codec for the two physical VFX channels. */
public final class VfxWireCodec {
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_PACKET_BYTES = 32 * 1024;
    private VfxWireCodec() {}

    public static byte[] encode(VfxPacket packet) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(packet.kind().ordinal());
            if (packet instanceof VfxCatalogHello hello) {
                out.writeInt(hello.protocolVersion()); out.writeLong(hello.catalogHash());
                out.writeInt(hello.maxPacketBytes()); out.writeInt(hello.maxParameters());
            } else if (packet instanceof VfxCatalogAck ack) {
                out.writeInt(ack.protocolVersion()); out.writeLong(ack.catalogHash());
                out.writeBoolean(ack.accepted()); writeString(out, ack.reason(), 256);
            } else if (packet instanceof VfxLifecyclePacket lifecycle) {
                out.writeLong(lifecycle.worldEpoch()); out.writeLong(lifecycle.instanceId());
                out.writeInt(lifecycle.assetId()); out.writeLong(lifecycle.stateSequence());
                out.writeLong(lifecycle.eventSequence()); writeUuid(out, lifecycle.owner());
                out.writeLong(lifecycle.startServerTick()); out.writeLong(lifecycle.seed());
            } else throw new IllegalArgumentException("unsupported packet " + packet.getClass());
            out.flush();
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_PACKET_BYTES) throw new IllegalArgumentException("VFX packet exceeds limit");
            return result;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static VfxPacket decode(byte[] bytes) {
        if (bytes == null || bytes.length < 1 || bytes.length > MAX_PACKET_BYTES)
            throw new IllegalArgumentException("invalid VFX packet size");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int ordinal = in.readUnsignedByte();
            VfxPacketKind[] kinds = VfxPacketKind.values();
            if (ordinal >= kinds.length) throw new IllegalArgumentException("unknown VFX packet kind");
            VfxPacketKind kind = kinds[ordinal];
            VfxPacket packet;
            switch (kind) {
                case CATALOG_HELLO -> packet = new VfxCatalogHello(in.readInt(), in.readLong(), in.readInt(), in.readInt());
                case CATALOG_ACK -> packet = new VfxCatalogAck(in.readInt(), in.readLong(), in.readBoolean(), readString(in, 256));
                case SPAWN, DELTA, EVENT, DESTROY, RELEASE, SNAPSHOT, OWNER_RESET -> packet = new VfxLifecyclePacket(
                        kind, in.readLong(), in.readLong(), in.readInt(), in.readLong(), in.readLong(), readUuid(in), in.readLong(), in.readLong());
                default -> throw new IllegalArgumentException("unsupported VFX packet kind");
            }
            if (in.available() != 0) throw new IllegalArgumentException("trailing VFX packet bytes");
            return packet;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void writeString(DataOutputStream out, String value, int maxBytes) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maxBytes) throw new IllegalArgumentException("string too long");
        out.writeShort(encoded.length); out.write(encoded);
    }
    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readUnsignedShort();
        if (length > maxBytes) throw new IllegalArgumentException("string too long");
        byte[] encoded = in.readNBytes(length);
        if (encoded.length != length) throw new EOFException("truncated VFX string");
        return new String(encoded, StandardCharsets.UTF_8);
    }
}