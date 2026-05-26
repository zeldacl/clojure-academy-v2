(ns cn.li.fabric1201.gui.network
  "Fabric 1.20.1 GUI/RPC network transport.

  Mirrors Forge behavior with request/response RPC plus server push.
  Transport format is EDN maps written into FriendlyByteBuf UTF strings."
  (:require [cn.li.mc1201.gui.network.packet :as packet-base]
            [cn.li.mc1201.reflect-util :as ru]
            [cn.li.mcmod.config :as mod-config]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log])
  (:import [clojure.lang Reflector]
           [io.netty.buffer Unpooled]
           [net.fabricmc.fabric.api.networking.v1 ServerPlayNetworking]
           [net.minecraft.client Minecraft]
           [net.minecraft.network FriendlyByteBuf]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer]))

(defn- client-session-id
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    [:client (System/identityHashCode mc)]))

(defn- with-client-session
  [f]
  (binding [runtime-hooks/*client-session-id* (client-session-id)]
    (f)))

(defn- server-player-owner
  [^ServerPlayer player]
  {:server-session-id (when-let [server (.getServer player)]
                        [:server (System/identityHashCode server)])
   :player-uuid (str (.getUUID player))})

(defn- with-server-player-owner
  [^ServerPlayer player f]
  (binding [runtime-hooks/*player-state-owner* (server-player-owner player)]
    (f)))

(defonce ^:private server-initialized? (atom false))
(defonce ^:private client-initialized? (atom false))

(def ^:private c2s-channel
  (ResourceLocation. mod-config/*mod-id* "clj_rpc_c2s"))

(def ^:private s2c-channel
  (ResourceLocation. mod-config/*mod-id* "clj_rpc_s2c"))

(defn- jproxy
  [^Class iface invoke-fn]
  (java.lang.reflect.Proxy/newProxyInstance
    (.getClassLoader iface)
    (into-array Class [iface])
    (reify java.lang.reflect.InvocationHandler
      (invoke [_ _ method args]
        (let [^java.lang.reflect.Method method method]
          (invoke-fn (.getName method) args))))))

(defn- make-buf
  [payload]
  (doto (FriendlyByteBuf. (Unpooled/buffer))
    (.writeUtf (packet-base/encode-payload payload))))

(defn- read-buf-map
  [^FriendlyByteBuf buf]
  (let [raw (try
              (.readUtf buf 1048576)
              (catch Throwable _
                (.readUtf buf)))]
    (packet-base/decode-payload
      raw
      #(log/error "Failed to deserialize Fabric network payload:" (ex-message %)))))

(defn send-to-server!
  [msg-id request-id payload]
  (let [client-networking (ru/class-noinit "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking")
        buf (make-buf (packet-base/request-map msg-id request-id payload))]
    (Reflector/invokeStaticMethod client-networking "send" (to-array [c2s-channel buf]))))

(defn send-response-to-client!
  [^ServerPlayer player request-id payload]
  (let [buf (make-buf (packet-base/response-map request-id payload))]
    (ServerPlayNetworking/send player s2c-channel buf)))

(defn send-push-to-client!
  [^ServerPlayer player msg-id payload]
  (let [buf (make-buf (packet-base/push-map msg-id payload))]
    (ServerPlayNetworking/send player s2c-channel buf)))

(defmethod net-client/send-request :fabric-1.20.1
  [msg-id payload request-id]
  (send-to-server! msg-id request-id payload))

(defn init-server!
  []
  (when (compare-and-set! server-initialized? false true)
    (let [handler-iface (ru/class-noinit "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayChannelHandler")
          receiver (jproxy
                     handler-iface
                     (fn [method-name ^objects args]
                       (when (= method-name "receive")
                         (let [^MinecraftServer server (aget args 0)
                               ^ServerPlayer player (aget args 1)
                               ^FriendlyByteBuf buf (aget args 3)
                               {:keys [msg-id request-id payload]} (packet-base/normalize-request (read-buf-map buf))]
                           (.execute server
                                     (reify Runnable
                                       (run [_]
                                         (with-server-player-owner
                                           player
                                           #(net-server/handle-request
                                              (str msg-id)
                                              request-id
                                              payload
                                              player
                                              (fn [rid response]
                                                (send-response-to-client!
                                                  player
                                                  (int rid)
                                                  (or response {}))))))))))
                       nil))]
      (Reflector/invokeStaticMethod ServerPlayNetworking "registerGlobalReceiver"
                                    (to-array [c2s-channel receiver]))
      (log/info "Fabric GUI network server transport initialized"))))

(defn init-client!
  []
  (when (compare-and-set! client-initialized? false true)
    (let [client-networking (ru/class-noinit "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking")
          handler-iface (ru/class-noinit "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayChannelHandler")
          receiver (jproxy
                     handler-iface
                     (fn [method-name ^objects args]
                       (when (= method-name "receive")
                         (let [client (aget args 0)
                               ^FriendlyByteBuf buf (aget args 2)
                               {:keys [request-id payload]} (packet-base/normalize-response (read-buf-map buf))]
                           (Reflector/invokeInstanceMethod
                             client
                             "execute"
                             (to-array
                               [(reify Runnable
                                  (run [_]
                                    (with-client-session
                                      #(packet-base/dispatch-client-response! request-id payload))))]))))
                        nil))]
      (Reflector/invokeStaticMethod client-networking "registerGlobalReceiver"
                                    (to-array [s2c-channel receiver]))
      (log/info "Fabric GUI network client transport initialized"))))
