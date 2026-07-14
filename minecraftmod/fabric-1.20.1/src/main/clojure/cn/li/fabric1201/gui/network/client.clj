(ns cn.li.fabric1201.gui.network.client
  "Fabric 1.20.1 GUI/RPC client transport via
  net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking."
  (:require [cn.li.fabric1201.gui.network.shared :as shared]
            [cn.li.mc1201.gui.network.packet :as packet-base]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.client.session :as mc-session])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.network FriendlyByteBuf]
           [net.fabricmc.fabric.api.client.networking.v1 ClientPlayNetworking ClientPlayNetworking$PlayChannelHandler]
           [net.fabricmc.fabric.api.networking.v1 PacketSender]))

(defn- client-session-id
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [connection (try (.getConnection mc) (catch Throwable _ nil))]
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
        player-uuid (or (payload-player-uuid payload)
                        (try (mc-session/local-player-uuid) (catch Exception _ nil)))]
    (runtime-hooks/with-client-ctx-fn
      {:session-id session-id
       :player-owner (cond-> {:client-session-id session-id}
                       player-uuid (assoc :player-uuid player-uuid))}
      f)))

(defn send-to-server!
  [msg-id request-id payload]
  (ClientPlayNetworking/send shared/c2s-channel (shared/make-buf (packet-base/request-map msg-id request-id payload))))

(defmethod net-client/send-request :fabric-1.20.1
  [msg-id payload request-id]
  (send-to-server! msg-id request-id payload))

(defn- handle-client-response!
  [request-id payload]
  (with-client-response-owner payload
    (fn []
      (packet-base/dispatch-client-response!
        runtime-hooks/*player-state-owner*
        request-id
        payload))))

(defn- on-client-play-receive
  [client _handler buf _sender]
  (let [{:keys [request-id payload]} (packet-base/normalize-response (shared/read-buf-map buf))]
    (.execute client
              (reify Runnable
                (run [_]
                  (handle-client-response! request-id payload)))))
  nil)

(defn init-client!
  "Process-scoped guard: ClientPlayNetworking/registerGlobalReceiver throws
   if the channel already has a registered receiver, so this must not redo
   on Framework reinjection."
  []
  (install/process-once! ::client-initialized
    (fn []
      (let [receiver (reify ClientPlayNetworking$PlayChannelHandler
                       (receive [_ client _handler buf _sender]
                         (on-client-play-receive client _handler buf _sender)))]
        (ClientPlayNetworking/registerGlobalReceiver shared/s2c-channel receiver)
        (log/info "Fabric GUI network client transport initialized"))))
  nil)
