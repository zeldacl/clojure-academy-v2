(ns cn.li.fabric1201.gui.network.server
  "Fabric 1.20.1 GUI/RPC server transport."
  (:require [cn.li.ac.gui.platform-adapter.sync-api :as gui-sync-api]
            [cn.li.fabric1201.gui.block-sync-broadcast]
            [cn.li.fabric1201.gui.network.shared :as shared]
            [cn.li.mc1201.gui.network.packet :as packet-base]
            [cn.li.mc1201.reflect-util :as ru]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log])
  (:import [clojure.lang Reflector]
           [net.fabricmc.fabric.api.networking.v1 ServerPlayNetworking]
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
  (binding [runtime-hooks/*player-state-owner* (server-player-owner player)]
    (f)))

(def ^:private server-init-guard-lock
  (Object.))

(def ^:private ^:dynamic *server-initialized?*
  false)

(defn send-response-to-client!
  [^ServerPlayer player request-id payload]
  (let [buf (shared/make-buf (packet-base/response-map request-id payload))]
    (ServerPlayNetworking/send player shared/s2c-channel buf)))

(defn send-push-to-client!
  [^ServerPlayer player msg-id payload]
  (let [buf (shared/make-buf (packet-base/push-map msg-id payload))]
    (ServerPlayNetworking/send player shared/s2c-channel buf)))

(defn init-server!
  []
  (when-not (var-get #'*server-initialized?*)
    (locking server-init-guard-lock
      (when-not (var-get #'*server-initialized?*)
        (let [handler-iface (ru/class-noinit "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayChannelHandler")
              receiver (shared/jproxy
                         handler-iface
                         (fn [method-name ^objects args]
                           (when (= method-name "receive")
                             (let [^MinecraftServer server (aget args 0)
                                   ^ServerPlayer player (aget args 1)
                                   ^FriendlyByteBuf buf (aget args 3)
                                   {:keys [msg-id request-id payload]} (packet-base/normalize-request (shared/read-buf-map buf))]
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
                                        (to-array [shared/c2s-channel receiver]))
          (alter-var-root #'*server-initialized?* (constantly true))
          (gui-sync-api/assert-gui-broadcast-dispatch! :fabric-1.20.1)
          (log/info "Fabric GUI network server transport initialized")))))
  nil)
