(ns cn.li.fabric1201.gui.network
  "Fabric 1.20.1 GUI/RPC network transport.

  Mirrors Forge behavior with request/response RPC plus server push.
  Transport format is EDN maps written into FriendlyByteBuf UTF strings."
  (:require [cn.li.mc1201.gui.network-packet-base :as packet-base]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log])
  (:import [clojure.lang Reflector]
           [io.netty.buffer Unpooled]
           [net.fabricmc.fabric.api.networking.v1 ServerPlayNetworking]
           [net.minecraft.network FriendlyByteBuf]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.server.level ServerPlayer]))

(defonce ^:private server-initialized? (atom false))
(defonce ^:private client-initialized? (atom false))

(def ^:private c2s-channel
  (ResourceLocation. modid/MOD-ID "clj_rpc_c2s"))

(def ^:private s2c-channel
  (ResourceLocation. modid/MOD-ID "clj_rpc_s2c"))

(defn- class-noinit [^String class-name]
  (Class/forName class-name false (.getContextClassLoader (Thread/currentThread))))

(defn- jproxy
  [^Class iface invoke-fn]
  (java.lang.reflect.Proxy/newProxyInstance
    (.getClassLoader iface)
    (into-array Class [iface])
    (reify java.lang.reflect.InvocationHandler
      (invoke [_ _ method args]
        (invoke-fn (.getName method) args)))))

(defn- serialize-map
  [m]
  (packet-base/encode-payload m))

(defn- deserialize-map
  [^String s]
  (packet-base/decode-payload s
                              #(log/error "Failed to deserialize Fabric network payload:" (ex-message %))))

(defn- make-buf
  [payload]
  (doto (FriendlyByteBuf. (Unpooled/buffer))
    (.writeUtf (serialize-map payload))))

(defn- read-buf-map
  [^FriendlyByteBuf buf]
  (let [raw (try
              (.readUtf buf 1048576)
              (catch Throwable _
                (.readUtf buf)))]
    (deserialize-map raw)))

(defn send-to-server!
  [msg-id request-id payload]
  (let [client-networking (class-noinit "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking")
        buf (make-buf {:msg-id msg-id
                       :request-id (int request-id)
                       :payload (or payload {})})]
    (Reflector/invokeStaticMethod client-networking "send" (to-array [c2s-channel buf]))))

(defn send-response-to-client!
  [^ServerPlayer player request-id payload]
  (let [buf (make-buf {:request-id (int request-id)
                       :payload (or payload {})})]
    (ServerPlayNetworking/send player s2c-channel buf)))

(defn send-push-to-client!
  [^ServerPlayer player msg-id payload]
  (let [buf (make-buf {:request-id -1
                       :payload {:msg-id msg-id :payload (or payload {})}})]
    (ServerPlayNetworking/send player s2c-channel buf)))

(defmethod net-client/send-request :fabric-1.20.1
  [msg-id payload request-id]
  (send-to-server! msg-id request-id payload))

(defn init-server!
  []
  (when (compare-and-set! server-initialized? false true)
    (let [handler-iface (class-noinit "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayChannelHandler")
          receiver (jproxy
                     handler-iface
                     (fn [method-name args]
                       (when (= method-name "receive")
                         (let [server (aget args 0)
                               player (aget args 1)
                               buf (aget args 3)
                               {:keys [msg-id request-id payload]} (read-buf-map buf)
                               request-id (int (or request-id -1))
                               payload (if (map? payload) payload {})]
                           (.execute server
                                     (reify Runnable
                                       (run [_]
                                         (net-server/handle-request
                                           (str msg-id)
                                           request-id
                                           payload
                                           player
                                           (fn [rid response]
                                             (send-response-to-client!
                                               player
                                               (int rid)
                                               (or response {})))))))))
                       nil))]
      (Reflector/invokeStaticMethod ServerPlayNetworking "registerGlobalReceiver"
                                    (to-array [c2s-channel receiver]))
      (log/info "Fabric GUI network server transport initialized"))))

(defn init-client!
  []
  (when (compare-and-set! client-initialized? false true)
    (let [client-networking (class-noinit "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking")
          handler-iface (class-noinit "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayChannelHandler")
          receiver (jproxy
                     handler-iface
                     (fn [method-name args]
                       (when (= method-name "receive")
                         (let [client (aget args 0)
                               buf (aget args 2)
                               {:keys [request-id payload]} (read-buf-map buf)
                               request-id (int (or request-id -1))
                               payload (if (map? payload) payload {})]
                           (.execute client
                                     (reify Runnable
                                       (run [_]
                                         (if (neg? request-id)
                                           (net-client/handle-push (:msg-id payload) (:payload payload))
                                           (net-client/handle-response request-id payload)))))))
                       nil))]
      (Reflector/invokeStaticMethod client-networking "registerGlobalReceiver"
                                    (to-array [s2c-channel receiver]))
      (log/info "Fabric GUI network client transport initialized"))))
