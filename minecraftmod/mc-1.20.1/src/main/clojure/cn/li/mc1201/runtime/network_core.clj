(ns cn.li.mc1201.runtime.network-core
  "Loader-agnostic runtime network registration helpers.

  Platforms provide transport functions; shared core wires runtime route/send
  integration with mcmod power-runtime and runtime message registry." 
  (:require [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mc1201.runtime.spi.network-transport :as transport-spi]
            [cn.li.mc1201.runtime.spi.server-context :as server-context-spi]
            [cn.li.mcmod.hooks.core :as network-hooks]
            [cn.li.mcmod.hooks.messages :as messages]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.players PlayerList]
           [net.minecraft.server.level ServerPlayer]))

(def ^:private default-except-local-radius 64.0)

(defn- msg-id [message-key]
  (messages/msg-id message-key))

(def ^:private sync-message-specs
  [{:message-key :sync-runtime
    :payload-key :ability-data}
   {:message-key :sync-resource
    :payload-key :resource-data}
   {:message-key :sync-cooldown
    :payload-key :cooldown-data}
   {:message-key :sync-preset
    :payload-key :preset-data}])

(declare init-runtime-network!)

(defn sync-message-payloads
  [uuid payload]
  (for [{:keys [message-key payload-key]} sync-message-specs]
    {:msg-id (msg-id message-key)
     :payload {:uuid uuid
               payload-key (get payload payload-key)}}))

(defn create-targeted-client-sender
  "Create a player-targeted send fn from player lookup + push transport functions."
  [find-player-by-uuid push-to-client!]
  (fn [uuid msg-id payload]
    (when-let [player (find-player-by-uuid uuid)]
      (push-to-client! player msg-id payload))))

(defn create-sync-sender
  "Create a runtime sync sender that fans a sync payload into all sync message variants."
  [find-player-by-uuid push-to-client!]
  (fn [uuid payload]
    (when-let [player (find-player-by-uuid uuid)]
      (doseq [{:keys [msg-id payload]} (sync-message-payloads uuid payload)]
        (try
          (push-to-client! player msg-id payload)
          (catch Exception e
            (log/error "Failed to send runtime sync message" msg-id "for" uuid ":" (.getMessage e))))))))

(defn default-send-to-server!
  [msg-id payload]
  (net-client/send-to-server msg-id payload))

(defn default-find-player-by-uuid
  [uuid-str]
  (query-core/get-player-by-uuid (server-context-spi/require-current-server) uuid-str))

(defn- same-dimension?
  [^ServerPlayer a-player ^ServerPlayer b-player]
  (= (some-> a-player .level .dimension .location str)
     (some-> b-player .level .dimension .location str)))

(defn- within-radius?
  [^ServerPlayer source-player ^ServerPlayer target-player radius]
  (let [dx (- (.getX target-player) (.getX source-player))
        dy (- (.getY target-player) (.getY source-player))
        dz (- (.getZ target-player) (.getZ source-player))
        distance-sqr (+ (* dx dx) (* dy dy) (* dz dz))]
    (<= distance-sqr (* radius radius))))

(defn default-find-nearby-player-uuids
  [source-player-uuid radius]
  (try
    (let [^MinecraftServer server (server-context-spi/require-current-server)
          ^ServerPlayer source-player (query-core/get-player-by-uuid server source-player-uuid)]
      (if (nil? source-player)
        []
        (let [^PlayerList player-list (.getPlayerList server)]
          (->> (.getPlayers player-list)
               (filter (fn [^ServerPlayer target-player]
                       (and (not= (str (.getUUID target-player)) source-player-uuid)
                            (same-dimension? source-player target-player)
                            (within-radius? source-player target-player radius))))
               (map (fn [^ServerPlayer target-player] (str (.getUUID target-player))))
               (into [])))))
    (catch Exception e
      (log/warn "Failed to resolve nearby players for except-local route:" source-player-uuid (ex-message e))
      [])))

(defn create-except-local-context-sender
  "Create a context channel sender that broadcasts to nearby players except source player."
  [find-nearby-player-uuids send-to-client-fn]
  (fn [ctx-id channel payload]
    (if-let [source-player-uuid (network-hooks/get-context-player-uuid ctx-id)]
      (doseq [target-player-uuid (find-nearby-player-uuids source-player-uuid default-except-local-radius)]
        (send-to-client-fn target-player-uuid
                           (msg-id :ctx-channel)
                           {:ctx-id ctx-id :channel channel :payload payload}))
      (log/debug "Skip except-local send due to missing context owner:" ctx-id))))

(def send-sync-to-client!
  (create-sync-sender transport-spi/find-player-by-uuid transport-spi/send-push-to-client!))

(def send-to-client!
  (create-targeted-client-sender transport-spi/find-player-by-uuid transport-spi/send-push-to-client!))

(defn install-runtime-network-transport!
  [{:keys [label install-server-context! send-to-server! send-push-to-client! find-player-by-uuid find-nearby-player-uuids]
    :or {label "runtime network"
         install-server-context! server-context-spi/install-server-context!
         send-to-server! default-send-to-server!
         find-player-by-uuid default-find-player-by-uuid
         find-nearby-player-uuids default-find-nearby-player-uuids}}]
  (install-server-context!)
  (transport-spi/register-transport-impl! {:send-to-server! send-to-server!
                                           :send-push-to-client! send-push-to-client!
                                           :find-player-by-uuid find-player-by-uuid
                                           :find-nearby-player-uuids find-nearby-player-uuids})
  (let [send-to-except-local-fn
        (create-except-local-context-sender find-nearby-player-uuids send-to-client!)]
  (init-runtime-network! {:send-to-server-fn send-to-server!
                          :send-to-client-fn send-to-client!
                          :send-to-except-local-fn send-to-except-local-fn}))
  (log/info label "runtime network initialized"))

(defn init-runtime-network!
  [{:keys [send-to-server-fn send-to-client-fn send-to-except-local-fn]}]
  (let [send-to-except-local-fn (or send-to-except-local-fn (fn [_ctx-id _channel _payload] nil))
        send-context-channel-to-server!
        (fn [ctx-id channel payload]
          (send-to-server-fn (msg-id :ctx-channel)
                             {:ctx-id ctx-id :channel channel :payload payload}))
        send-context-channel-to-client!
        (fn [ctx-id channel payload]
          (when-let [player-uuid (network-hooks/get-context-player-uuid ctx-id)]
            (send-to-client-fn player-uuid
                               (msg-id :ctx-channel)
                               {:ctx-id ctx-id :channel channel :payload payload})))]
    (network-hooks/register-network-handlers!)
    (network-hooks/register-context-route-fns! {:to-server send-context-channel-to-server!
                                                :to-client send-context-channel-to-client!
                                                :to-except-local send-to-except-local-fn})
    (network-hooks/register-context-send-fns! {:to-server send-to-server-fn
                                               :to-client send-to-client-fn})))
