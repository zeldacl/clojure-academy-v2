(ns cn.li.mcmod.runtime.vfx-contract
  "Minecraft-free VFX frame ABI shared by neutral runtime and platform backends."
  (:import [java.nio ByteBuffer]))

(def schema-version 1)
(def stages #{:world-after-sky :world-before-translucent
              :world-after-translucent :world-always-on-top :world-glow
              :first-person :hud-underlay :hud-overlay :screen-post})
(def primitives #{:billboard :particle :beam :ribbon :line :mesh
                  :first-person :camera :post-process})

(defn- require-key [m k]
  (when-not (contains? m k)
    (throw (ex-info "missing VFX ABI field" {:field k :value m})))
  m)

(defn tick-context [context]
  (-> context (require-key :tick-id) (require-key :delta-seconds)
      (assoc :schema-version schema-version)))

(defn frame-context [context]
  (let [context (-> context (require-key :frame-id) (require-key :partial-tick)
                    (assoc :schema-version schema-version))]
    (when-not (number? (:partial-tick context))
      (throw (ex-info "VFX partial-tick must be numeric" {:context context})))
    context))

(defn batch [{:keys [stage primitive material variant layout-version count sort-mode payload]
             :as value}]
  (when-not (contains? stages stage)
    (throw (ex-info "unknown VFX stage" {:stage stage :value value})))
  (when-not (contains? primitives primitive)
    (throw (ex-info "unknown VFX primitive" {:primitive primitive :value value})))
  (when-not (and (integer? count) (<= 0 count))
    (throw (ex-info "VFX batch count must be non-negative" {:value value})))
  (when (and payload (not (or (instance? ByteBuffer payload)
                              (vector? payload) (sequential? payload))))
    (throw (ex-info "unsupported VFX batch payload" {:class (class payload)})))
  {:schema-version schema-version :stage stage :primitive primitive
   :material material :variant variant :layout-version (long (or layout-version 1))
   :count (long count) :sort-mode (or sort-mode :stable) :payload payload})

(defn frame [frame-id generation stages-map]
  {:schema-version schema-version :frame-id (long frame-id)
   :resource-generation (long (or generation 0))
   :stages (into {} (map (fn [[stage batches]]
                           (when-not (contains? stages stage)
                             (throw (ex-info "unknown VFX frame stage" {:stage stage})))
                           [stage (vec batches)])) stages-map)})

(def required-host-operations
  #{:schema-version :required-anchors :tick! :sample-frame! :frame-stage :release-frame!
    :clear-world! :resource-snapshot :reload-resources!
    :active? :fov-offset :hand-transform :drain-camera-pitch-deltas!})

(defn validate-host-api [api]
  (when-not (map? api)
    (throw (ex-info "VFX host API must be a map" {:value api})))
  (when-not (= schema-version (:schema-version api))
    (throw (ex-info "VFX ABI schema version mismatch"
                    {:expected schema-version :actual (:schema-version api)})))
  (let [missing (remove #(ifn? (get api %)) required-host-operations)]
    (when (seq missing)
      (throw (ex-info "VFX host API is incomplete" {:missing (vec missing)}))))
  api)
