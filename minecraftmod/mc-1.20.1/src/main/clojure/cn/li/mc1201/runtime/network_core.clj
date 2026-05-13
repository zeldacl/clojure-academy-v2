(ns cn.li.mc1201.runtime.network-core
  "Loader-agnostic runtime network registration helpers.

  Platforms provide transport functions; shared core wires runtime route/send
  integration with mcmod power-runtime and runtime-catalog." 
  (:require [cn.li.mcmod.runtime.hooks.network :as network-hooks]
            [cn.li.mcmod.runtime.catalog :as runtime-catalog]
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
