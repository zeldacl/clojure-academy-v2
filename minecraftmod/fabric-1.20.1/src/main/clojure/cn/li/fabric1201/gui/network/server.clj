(ns cn.li.fabric1201.gui.network.server
  "Fabric 1.20.1 GUI/RPC server transport."
  (:require [cn.li.fabric1201.gui.network.shared :as shared]
            [cn.li.mc1201.gui.network.packet :as packet-base]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.networking.v1 ServerPlayNetworking ServerPlayNetworking$PlayChannelHandler]
           [net.minecraft.network FriendlyByteBuf]
           [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer]))

(defn- server-player-owner
  [^ServerPlayer player]
  {:server-session-id (when-let [server (.getServer player)]
                        [:server (System/identityHashCode server)])
   :player-uuid (str (.getUUID player))})

(defn- with-server-player-owner
  [^ServerPlayer player f]
  (runtime-hooks/with-client-ctx-fn {:player-owner (server-player-owner player)} f))

(def ^:private server-init-guard-lock
  (Object.))

(def ^:private ^:dynamic *server-initialized?*
  false)

(defn send-response-to-client!
  [^ServerPlayer player request-id payload]
  (ServerPlayNetworking/send player shared/s2c-channel (shared/make-buf (packet-base/response-map request-id payload))))

(defn send-push-to-client!
  [^ServerPlayer player msg-id payload]
  (ServerPlayNetworking/send player shared/s2c-channel (shared/make-buf (packet-base/push-map msg-id payload))))

(defn init-server!
  []
  (when-not (var-get #'*server-initialized?*)
    (locking server-init-guard-lock
      (when-not (var-get #'*server-initialized?*)
        (let [receiver (reify ServerPlayNetworking$PlayChannelHandler
                         (receive [_ server player _handler buf _sender]
                           (let [{:keys [msg-id request-id payload]} (packet-base/normalize-request (shared/read-buf-map buf))]
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
                                                    (or response {})))))))))
                           nil))]
          (ServerPlayNetworking/registerGlobalReceiver shared/c2s-channel receiver)
          (alter-var-root #'*server-initialized?* (constantly true))
          (log/info "Fabric GUI network server transport initialized")))))
  nil)
