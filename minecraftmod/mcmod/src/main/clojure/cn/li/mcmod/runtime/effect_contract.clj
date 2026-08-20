(ns cn.li.mcmod.runtime.effect-contract
  "Neutral, versioned request and output contracts for host capabilities.")

(def schema-version 2)

(def query-capabilities
  #{:owner/snapshot :item/held :raycast :entity/select :block/select :state/read})

(def action-capabilities
  #{:inventory/consume :entity/damage :entity/impulse :entity/teleport
    :entity/reset-fall-damage
    :entity/status :entity/spawn :entity/discard :block/break :block/set
    :world/lightning :world/explosion :world/sound
    :projectile/redirect :resource/enforce-floor :resource/add})

(defn- require-map! [value message]
  (when-not (map? value)
    (throw (ex-info message {:value value})))
  value)

(defn query-request [{:keys [capability world-id] :as request}]
  (require-map! request "query request must be a map")
  (when-not (contains? query-capabilities capability)
    (throw (ex-info "unknown query capability" {:capability capability})))
  (when-not (string? world-id)
    (throw (ex-info "query world-id must be a string" {:world-id world-id})))
  (assoc request :schema-version schema-version))

(defn action-request [{:keys [capability world-id] :as request}]
  (require-map! request "action request must be a map")
  (when-not (contains? action-capabilities capability)
    (throw (ex-info "unknown action capability" {:capability capability})))
  (when-not (string? world-id)
    (throw (ex-info "action world-id must be a string" {:world-id world-id})))
  (assoc request :schema-version schema-version))

(defn vfx-signal [{:keys [effect-id operation payload] :as signal}]
  (require-map! signal "VFX signal must be a map")
  (when-not (keyword? effect-id)
    (throw (ex-info "VFX effect id must be a keyword" {:effect-id effect-id})))
  (when-not (#{:spawn :update :destroy} operation)
    (throw (ex-info "invalid VFX operation" {:operation operation})))
  (when-not (map? payload)
    (throw (ex-info "VFX payload must be a map" {:payload payload})))
  (assoc signal :schema-version schema-version))
