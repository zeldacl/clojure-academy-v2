(ns cn.li.fabric1201.gui.network.server
  "Fabric 1.20.1 GUI/RPC server transport."
  (:require [cn.li.fabric1201.gui.network.shared :as shared]
            [cn.li.mc1201.gui.network.packet :as packet-base]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.runtime.install :as install]
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

(defn send-response-to-client!
  [^ServerPlayer player request-id payload]
  (ServerPlayNetworking/send player shared/s2c-channel (shared/make-buf (packet-base/response-map request-id payload))))

(defn send-push-to-client!
  [^ServerPlayer player msg-id payload]
  (ServerPlayNetworking/send player shared/s2c-channel (shared/make-buf (packet-base/push-map msg-id payload))))

(defn- handle-server-request!
  [^ServerPlayer player msg-id request-id payload]
  (with-server-player-owner
    player
    (fn []
      (net-server/handle-request
        (str msg-id)
        request-id
        payload
        player
        (fn [rid response]
          (send-response-to-client!
            player
            (int rid)
            (or response {})))))))

(defn- on-server-play-receive
  [server player _handler buf _sender]
  (let [{:keys [msg-id request-id payload]} (packet-base/normalize-request (shared/read-buf-map buf))]
    (.execute server
              (reify Runnable
                (run [_]
                  (handle-server-request! player msg-id request-id payload)))))
  nil)

(defn init-server!
  "Process-scoped guard: ServerPlayNetworking/registerGlobalReceiver throws
   if the channel already has a registered receiver, so this must not redo
   on Framework reinjection."
  []
  (install/process-once! ::server-initialized
    (fn []
      (let [receiver (reify ServerPlayNetworking$PlayChannelHandler
                       (receive [_ server player _handler buf _sender]
                         (on-server-play-receive server player _handler buf _sender)))]
        (ServerPlayNetworking/registerGlobalReceiver shared/c2s-channel receiver)
        (log/info "Fabric GUI network server transport initialized"))))
  nil)
