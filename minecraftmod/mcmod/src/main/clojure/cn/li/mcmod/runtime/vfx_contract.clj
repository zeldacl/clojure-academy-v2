(ns cn.li.mcmod.runtime.vfx-contract
  "Minecraft-free VFX frame ABI shared by neutral runtime and platform backends."
  (:import [java.nio ByteBuffer]))

(def schema-version 1)
(def stages #{:world-after-sky :world-before-translucent
              :world-after-translucent :world-always-on-top :world-glow
              :first-person :hud-underlay :hud-overlay :screen-post})
(def primitives #{:billboard :particle :beam :ribbon :line :mesh
                  :first-person :camera :post-process :audio})

(def signal-ops #{:spawn :signal :destroy :clear-owner})

(defn- require-key [m k]
  (when-not (contains? m k)
    (throw (ex-info "missing VFX ABI field" {:field k :value m})))
  m)

(defn tick-context [context]
  (let [context (-> context (require-key :tick-id) (require-key :delta-seconds))
        tick-id (:tick-id context)
        delta (:delta-seconds context)]
    (when-not (integer? tick-id)
      (throw (ex-info "VFX tick-id must be an integer" {:context context})))
    (when-not (and (number? delta)
                   (Double/isFinite (double delta))
                   (<= 0.0 (double delta)))
      (throw (ex-info "VFX delta-seconds must be a finite non-negative number"
                      {:context context})))
    (assoc context :schema-version schema-version)))

(defn frame-context [context]
  (let [context (-> context (require-key :frame-id) (require-key :partial-tick)
                    (assoc :schema-version schema-version))]
    (when-not (number? (:partial-tick context))
      (throw (ex-info "VFX partial-tick must be numeric" {:context context})))
    (when-not (and (Double/isFinite (double (:partial-tick context)))
                   (<= 0.0 (double (:partial-tick context)) 1.0))
      (throw (ex-info "VFX partial-tick must be in [0,1]"
                      {:context context})))
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

(defn signal [{:keys [op] :as value}]
  (when-not (contains? signal-ops op)
    (throw (ex-info "unknown VFX signal operation" {:value value})))
  (when (#{:spawn :signal} op)
    (doseq [k [:effect-id :instance-key :owner :event-seq]]
      (require-key value k)))
  (when (and (contains? value :event-seq)
             (not (integer? (:event-seq value))))
    (throw (ex-info "VFX event-seq must be an integer" {:value value})))
  (assoc value :schema-version schema-version))

(def required-host-operations
  "Operations every host API must provide: the core tick/sample/frame
   lifecycle that vfx-core needs regardless of what content it's running."
  #{:schema-version :required-anchors :tick! :sample-frame! :frame-stage :latest-frame-stage :release-frame!
    :clear-world! :resource-snapshot :reload-resources!})

(def optional-host-operations
  "Camera-specific capability, not part of the core lifecycle. A host API
   without a first-person/camera consumer legitimately has no use for
   these; only validate their type when the key is actually present."
  #{:active? :fov-offset :drain-camera-pitch-deltas!})

(defn validate-host-api [api]
  (when-not (map? api)
    (throw (ex-info "VFX host API must be a map" {:value api})))
  (when-not (= schema-version (:schema-version api))
    (throw (ex-info "VFX ABI schema version mismatch"
                    {:expected schema-version :actual (:schema-version api)})))
  ;; :schema-version is itself in required-host-operations (it's a required
  ;; key) but its value is the integer checked above, never a function --
  ;; folding it into this ifn? sweep meant every well-formed host API,
  ;; including AC's own, always failed with {:missing [:schema-version]}.
  (let [missing (remove #(ifn? (get api %)) (disj required-host-operations :schema-version))]
    (when (seq missing)
      (throw (ex-info "VFX host API is incomplete" {:missing (vec missing)}))))
  (let [present-optional (filter #(contains? api %) optional-host-operations)
        malformed (remove #(ifn? (get api %)) present-optional)]
    (when (seq malformed)
      (throw (ex-info "VFX host API has a malformed optional operation"
                      {:malformed (vec malformed)}))))
  api)
