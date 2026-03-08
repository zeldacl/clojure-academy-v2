package my_mod.network;

import clojure.lang.IFn;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Forge 1.20.1 SimpleChannel bridge for Clojure GUI RPC network system.
 *
 * Two packet types:
 *   0 - C2SPacket: client sends request (msgId, requestId, payload bytes)
 *   1 - S2CPacket: server sends response (requestId, response bytes)
 *
 * Payload/response bytes are EDN-serialized Clojure maps produced by pr-str
 * and deserialized by clojure.edn/read-string on the receiving side.
 */
public class ClojureNetwork {

    private static final String PROTOCOL_VERSION = "1";
    private static SimpleChannel CHANNEL;
    private static IFn requestHandlerFn;
    private static IFn responseHandlerFn;

    // -------------------------------------------------------------------------
    // Packet: client → server
    // -------------------------------------------------------------------------

    public static class C2SPacket {
        public final String msgId;
        public final int requestId;
        public final byte[] payload;

        public C2SPacket(String msgId, int requestId, byte[] payload) {
            this.msgId = msgId;
            this.requestId = requestId;
            this.payload = payload;
        }

        public static void encode(C2SPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.msgId);
            buf.writeInt(pkt.requestId);
            buf.writeByteArray(pkt.payload);
        }

        public static C2SPacket decode(FriendlyByteBuf buf) {
            return new C2SPacket(buf.readUtf(), buf.readInt(), buf.readByteArray());
        }

        public static void handle(C2SPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ServerPlayer sender = ctx.getSender();
            ctx.enqueueWork(() -> {
                if (requestHandlerFn != null) {
                    requestHandlerFn.invoke(pkt.msgId, pkt.requestId, pkt.payload, sender);
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Packet: server → client
    // -------------------------------------------------------------------------

    public static class S2CPacket {
        public final int requestId;
        public final byte[] response;

        public S2CPacket(int requestId, byte[] response) {
            this.requestId = requestId;
            this.response = response;
        }

        public static void encode(S2CPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.requestId);
            buf.writeByteArray(pkt.response);
        }

        public static S2CPacket decode(FriendlyByteBuf buf) {
            return new S2CPacket(buf.readInt(), buf.readByteArray());
        }

        public static void handle(S2CPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                if (responseHandlerFn != null) {
                    responseHandlerFn.invoke(pkt.requestId, pkt.response);
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    /**
     * Register the SimpleChannel and both packet types.
     * Must be called during mod common setup (before any packet is sent).
     *
     * @param reqHandler  IFn(String msgId, int requestId, byte[] payload, ServerPlayer sender)
     * @param respHandler IFn(int requestId, byte[] response)
     */
    public static void init(IFn reqHandler, IFn respHandler) {
        requestHandlerFn = reqHandler;
        responseHandlerFn = respHandler;

        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation("my_mod:gui_rpc"),
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals
        );

        CHANNEL.registerMessage(0, C2SPacket.class,
                C2SPacket::encode, C2SPacket::decode, C2SPacket::handle);
        CHANNEL.registerMessage(1, S2CPacket.class,
                S2CPacket::encode, S2CPacket::decode, S2CPacket::handle);
    }

    // -------------------------------------------------------------------------
    // Send helpers called from Clojure
    // -------------------------------------------------------------------------

    public static void sendToServer(String msgId, int requestId, byte[] payload) {
        if (CHANNEL == null) throw new IllegalStateException("ClojureNetwork not initialized");
        CHANNEL.sendToServer(new C2SPacket(msgId, requestId, payload));
    }

    public static void sendToClient(ServerPlayer player, int requestId, byte[] response) {
        if (CHANNEL == null) throw new IllegalStateException("ClojureNetwork not initialized");
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new S2CPacket(requestId, response));
    }
}
