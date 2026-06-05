(ns cn.li.fabric1201.gui.network.client
  "Fabric 1.20.1 GUI/RPC client transport."
  (:require [cn.li.fabric1201.gui.network.shared :as shared]
            [cn.li.mc1201.gui.network.packet :as packet-base]
            [cn.li.mc1201.reflect-util :as ru]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log])
  (:import [clojure.lang Reflector]
           [net.minecraft.network FriendlyByteBuf]))

(defn- current-client-instance
  []
  (try
    (let [minecraft-cls (ru/class-noinit "net.minecraft.client.Minecraft")]
      (ru/static minecraft-cls "getInstance"))
    (catch Throwable _
      nil)))

(defn- client-session-id
  []
  (when-let [mc (current-client-instance)]
    (when-let [connection (try
                            (ru/inst mc "getConnection")
                            (catch Throwable _
                              nil))]
      [:client-session
       (System/identityHashCode mc)
       (System/identityHashCode connection)])))

(defn- payload-player-uuid
  [payload]
  (some-> (or (:uuid payload)
              (:player-uuid payload)
              (get-in payload [:payload :uuid])
              (get-in payload [:payload :player-uuid]))
          str))

(defn- with-client-response-owner
  [payload f]
  (let [session-id (client-session-id)
        local-player-uuid-fn (requiring-resolve 'cn.li.mc1201.client.session/local-player-uuid)
        ;; Response payloads rarely carry :player-uuid; fall back to the local
        ;; Minecraft player so require-client-owner validation passes during dispatch.
        player-uuid (or (payload-player-uuid payload)
                        (when local-player-uuid-fn
                          (try (local-player-uuid-fn) (catch Exception _ nil))))]
    (binding [runtime-hooks/*client-session-id* session-id
              runtime-hooks/*player-state-owner* (cond-> {:client-session-id session-id}
                                                   player-uuid (assoc :player-uuid player-uuid))]
      (f))))

(def ^:private client-init-guard-lock
  (Object.))

(def ^:private ^:dynamic *client-initialized?*
  false)

(defn send-to-server!
  [msg-id request-id payload]
  (let [client-networking (ru/class-noinit "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking")
        buf (shared/make-buf (packet-base/request-map msg-id request-id payload))]
    (Reflector/invokeStaticMethod client-networking "send" (to-array [shared/c2s-channel buf]))))

(defmethod net-client/send-request :fabric-1.20.1
  [msg-id payload request-id]
  (send-to-server! msg-id request-id payload))

(defn init-client!
  []
  (when-not (var-get #'*client-initialized?*)
    (locking client-init-guard-lock
      (when-not (var-get #'*client-initialized?*)
        (let [client-networking (ru/class-noinit "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking")
              handler-iface (ru/class-noinit "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayChannelHandler")
              receiver (shared/jproxy
                         handler-iface
                         (fn [method-name ^objects args]
                           (when (= method-name "receive")
                             (let [client (aget args 0)
                                   ^FriendlyByteBuf buf (aget args 2)
                                   {:keys [request-id payload]} (packet-base/normalize-response (shared/read-buf-map buf))]
                               (Reflector/invokeInstanceMethod
                                 client
                                 "execute"
                                 (to-array
                                   [(reify Runnable
                                      (run [_]
                                        (with-client-response-owner payload
                                          #(packet-base/dispatch-client-response!
                                             runtime-hooks/*player-state-owner*
                                             request-id
                                             payload))))]))))
                           nil))]
          (Reflector/invokeStaticMethod client-networking "registerGlobalReceiver"
                                        (to-array [shared/s2c-channel receiver]))
          (alter-var-root #'*client-initialized?* (constantly true))
          (log/info "Fabric GUI network client transport initialized")))))
  nil)
