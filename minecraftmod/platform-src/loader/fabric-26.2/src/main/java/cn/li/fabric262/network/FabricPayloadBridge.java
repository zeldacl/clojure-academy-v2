package cn.li.fabric262.network;

import clojure.lang.IFn;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

/** Typed-payload bridge for modern Fabric API networking. */
public final class FabricPayloadBridge {
    private static final Identifier C2S_ID = Identifier.parse("academycraft:clj_rpc_c2s");
    private static final Identifier S2C_ID = Identifier.parse("academycraft:clj_rpc_s2c");
    private static final Identifier RUNTIME_ID = Identifier.parse("academycraft:runtime_sync_v2");
    private static final CustomPacketPayload.Type<BytesPayload> C2S_TYPE = new CustomPacketPayload.Type<>(C2S_ID);
    private static final CustomPacketPayload.Type<BytesPayload> S2C_TYPE = new CustomPacketPayload.Type<>(S2C_ID);
    private static final CustomPacketPayload.Type<BytesPayload> RUNTIME_TYPE = new CustomPacketPayload.Type<>(RUNTIME_ID);

    private static StreamCodec<RegistryFriendlyByteBuf, BytesPayload> codec(CustomPacketPayload.Type<BytesPayload> type) {
        return StreamCodec.of((buf, payload) -> buf.writeByteArray(payload.bytes()),
                buf -> new BytesPayload(type, buf.readByteArray()));
    }
    private static final StreamCodec<RegistryFriendlyByteBuf, BytesPayload> C2S_CODEC = codec(C2S_TYPE);
    private static final StreamCodec<RegistryFriendlyByteBuf, BytesPayload> S2C_CODEC = codec(S2C_TYPE);
    private static final StreamCodec<RegistryFriendlyByteBuf, BytesPayload> RUNTIME_CODEC = codec(RUNTIME_TYPE);
    private static volatile boolean serverInstalled;
    private static volatile boolean clientInstalled;

    private FabricPayloadBridge() {}

    public static final class BytesPayload implements CustomPacketPayload {
        private final CustomPacketPayload.Type<BytesPayload> payloadType;
        private final byte[] payloadBytes;
        public BytesPayload(CustomPacketPayload.Type<BytesPayload> type, byte[] bytes) {
            payloadType = type;
            payloadBytes = Arrays.copyOf(bytes, bytes.length);
        }
        public byte[] bytes() { return Arrays.copyOf(payloadBytes, payloadBytes.length); }
        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return payloadType; }
    }

    private static BytesPayload payload(CustomPacketPayload.Type<BytesPayload> type, byte[] bytes) {
        return new BytesPayload(type, bytes);
    }

    public static synchronized void installServer(IFn requestHandler) {
        if (serverInstalled) return;
        PayloadTypeRegistry.serverboundPlay().register(C2S_TYPE, C2S_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(C2S_TYPE,
                (payload, context) -> requestHandler.invoke(payload.bytes(), context.player()));
        serverInstalled = true;
    }

    public static synchronized void installClient(IFn responseHandler, IFn runtimeHandler) {
        if (clientInstalled) return;
        PayloadTypeRegistry.clientboundPlay().register(S2C_TYPE, S2C_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RUNTIME_TYPE, RUNTIME_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(S2C_TYPE,
                (payload, context) -> responseHandler.invoke(payload.bytes(), context.client()));
        ClientPlayNetworking.registerGlobalReceiver(RUNTIME_TYPE,
                (payload, context) -> runtimeHandler.invoke(payload.bytes(), context.client()));
        clientInstalled = true;
    }

    public static void sendToClient(ServerPlayer player, String channel, byte[] bytes) {
        ServerPlayNetworking.send(player, payload(channel.equals("runtime") ? RUNTIME_TYPE : S2C_TYPE, bytes));
    }

    public static void sendToServer(byte[] bytes) {
        ClientPlayNetworking.send(payload(C2S_TYPE, bytes));
    }
}
