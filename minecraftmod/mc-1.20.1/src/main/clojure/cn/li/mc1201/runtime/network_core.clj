(ns cn.li.mc1201.runtime.network-core
  "Loader-agnostic runtime network registration helpers.

  Platforms provide transport functions; shared core wires runtime route/send
  integration with mcmod power-runtime and runtime-catalog." 
  (:require [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mc1201.runtime.spi.network-transport :as transport-spi]
            [cn.li.mc1201.runtime.spi.server-context :as server-context-spi]
            [cn.li.mcmod.hooks.core :as network-hooks]
            [cn.li.mcmod.hooks.catalog :as runtime-catalog]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]))

(def ^:private sync-message-specs
  [{:msg-id runtime-catalog/MSG-SYNC-RUNTIME
    :payload-key :ability-data}
   {:msg-id runtime-catalog/MSG-SYNC-RESOURCE
    :payload-key :resource-data}
   {:msg-id runtime-catalog/MSG-SYNC-COOLDOWN
    :payload-key :cooldown-data}
   {:msg-id runtime-catalog/MSG-SYNC-PRESET
    :payload-key :preset-data}])

(declare init-runtime-network!)

(defn sync-message-payloads
  [uuid payload]
  (for [{:keys [msg-id payload-key]} sync-message-specs]
    {:msg-id msg-id
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

(def send-sync-to-client!
  (create-sync-sender transport-spi/find-player-by-uuid transport-spi/send-push-to-client!))

(def send-to-client!
  (create-targeted-client-sender transport-spi/find-player-by-uuid transport-spi/send-push-to-client!))

(defn install-runtime-network-transport!
  [{:keys [label install-server-context! send-to-server! send-push-to-client! find-player-by-uuid]
    :or {label "runtime network"
         install-server-context! server-context-spi/install-server-context!
         send-to-server! default-send-to-server!
         find-player-by-uuid default-find-player-by-uuid}}]
  (install-server-context!)
  (transport-spi/register-transport-impl! {:send-to-server! send-to-server!
                                           :send-push-to-client! send-push-to-client!
                                           :find-player-by-uuid find-player-by-uuid})
  (init-runtime-network! {:send-to-server-fn send-to-server!
                          :send-to-client-fn send-to-client!})
  (log/info label "runtime network initialized"))

(defn init-runtime-network!
  [{:keys [send-to-server-fn send-to-client-fn send-to-except-local-fn]}]
  (let [send-to-except-local-fn (or send-to-except-local-fn (fn [_ctx-id _channel _payload] nil))
        send-context-channel-to-server!
        (fn [ctx-id channel payload]
          (send-to-server-fn runtime-catalog/MSG-CTX-CHANNEL
                             {:ctx-id ctx-id :channel channel :payload payload}))
        send-context-channel-to-client!
        (fn [ctx-id channel payload]
          (when-let [player-uuid (network-hooks/get-context-player-uuid ctx-id)]
            (send-to-client-fn player-uuid
                               runtime-catalog/MSG-CTX-CHANNEL
                               {:ctx-id ctx-id :channel channel :payload payload})))]
    (network-hooks/register-network-handlers!)
    (network-hooks/register-context-route-fns! {:to-server send-context-channel-to-server!
                                                :to-client send-context-channel-to-client!
                                                :to-except-local send-to-except-local-fn})
    (network-hooks/register-context-send-fns! {:to-server send-to-server-fn
                                               :to-client send-to-client-fn})))
