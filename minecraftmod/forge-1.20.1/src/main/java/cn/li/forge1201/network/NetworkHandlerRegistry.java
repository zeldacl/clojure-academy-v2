package cn.li.forge1201.network;

import clojure.lang.IFn;
import net.minecraft.server.level.ServerPlayer;

final class NetworkHandlerRegistry {
    private static IFn requestHandlerFn;
    private static IFn responseHandlerFn;

    private NetworkHandlerRegistry() {
    }

    static void install(IFn reqHandler, IFn respHandler) {
        requestHandlerFn = reqHandler;
        responseHandlerFn = respHandler;
    }

    static void dispatchRequest(String msgId, int requestId, byte[] payload, ServerPlayer sender) {
        IFn requestHandler = requestHandlerFn;
        if (requestHandler != null) {
            requestHandler.invoke(msgId, requestId, payload, sender);
        }
    }

    static void dispatchResponse(int requestId, byte[] response) {
        IFn responseHandler = responseHandlerFn;
        if (responseHandler != null) {
            responseHandler.invoke(requestId, response);
        }
    }
}
