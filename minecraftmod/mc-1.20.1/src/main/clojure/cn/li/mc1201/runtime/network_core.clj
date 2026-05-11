(ns cn.li.mc1201.runtime.network-core
  "Loader-agnostic runtime network registration helpers.

  Platforms provide transport functions; shared core wires runtime route/send
  integration with mcmod power-runtime and runtime-catalog." 
  (:require [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.runtime.catalog :as runtime-catalog]))

(defn init-runtime-network!
  [{:keys [send-to-server-fn send-to-client-fn send-to-except-local-fn]}]
  (let [send-to-except-local-fn (or send-to-except-local-fn (fn [_ctx-id _channel _payload] nil))
        send-context-channel-to-server!
        (fn [ctx-id channel payload]
          (send-to-server-fn runtime-catalog/MSG-CTX-CHANNEL
                             {:ctx-id ctx-id :channel channel :payload payload}))
        send-context-channel-to-client!
        (fn [ctx-id channel payload]
          (when-let [player-uuid (power-runtime/get-context-player-uuid ctx-id)]
            (send-to-client-fn player-uuid
                               runtime-catalog/MSG-CTX-CHANNEL
                               {:ctx-id ctx-id :channel channel :payload payload})))]
    (power-runtime/register-network-handlers!)
    (power-runtime/register-context-route-fns! {:to-server send-context-channel-to-server!
                                                :to-client send-context-channel-to-client!
                                                :to-except-local send-to-except-local-fn})
    (power-runtime/register-context-send-fns! {:to-server send-to-server-fn
                                               :to-client send-to-client-fn})))
