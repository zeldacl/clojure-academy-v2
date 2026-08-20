(ns cn.li.mcmod.runtime.capabilities
  "Startup-linked neutral host capabilities.

   The registry stores ordinary Clojure functions in a stable keyword order;
   platform code owns the function bodies and may only exchange neutral data
   batches or action results with the core runtimes.")

(def ^:private query-capabilities
  #{:owner/snapshot :item/held :raycast :entity/select :block/select :state/read
    :energy/target})
(def ^:private action-capabilities
  #{:inventory/consume :entity/damage :entity/impulse :entity/teleport
    :entity/trigger-behavior :entity/mark :energy/charge
    :entity/reset-fall-damage :entity/radial-impulse :motion/flight
    :owner/can-fly
    :entity/status :entity/spawn :entity/discard :block/break :block/set
    :world/lightning :world/explosion :world/sound
    :projectile/redirect :projectile/schedule-beam :resource/enforce-floor
    :resource/add :block/random-break})
  

(defonce ^:private state*
  (atom {:frozen? false :queries {} :actions {}}))

(defn register-query! [capability handler]
  (when (:frozen? @state*)
    (throw (ex-info "capability registry frozen" {:capability capability})))
  (when-not (and (contains? query-capabilities capability)
                 (ifn? handler))
    (throw (ex-info "invalid query capability" {:capability capability})))
  (swap! state* assoc-in [:queries capability] handler)
  capability)

(defn register-action! [capability handler]
  (when (:frozen? @state*)
    (throw (ex-info "capability registry frozen" {:capability capability})))
  (when-not (and (contains? action-capabilities capability)
                 (ifn? handler))
    (throw (ex-info "invalid action capability" {:capability capability})))
  (swap! state* assoc-in [:actions capability] handler)
  capability)

(defn freeze! []
  (swap! state* assoc :frozen? true)
  @state*)

(defn reset-for-test! []
  (reset! state* {:frozen? false :queries {} :actions {}})
  nil)

(defn snapshot [] @state*)
