(ns cn.li.mcmod.runtime.combat-contract
  "Minecraft-free combat intent/result contracts shared by AC and platform ports.")

(def schema-version 1)
(def intent-ops #{:start :release :abort})
(def activation-kinds #{:instant :session :toggle :passive})
(def host-port-keys #{:query :world-effect :owner-state :clock :random})
(defonce ^:private host-ports* (atom {}))

(defn install-host-port!
  "Install one neutral combat host port from a platform composition root.
   The port value is an IFn and never exposes a loader or Minecraft type." 
  [kind port]
  (when-not (contains? host-port-keys kind)
    (throw (ex-info "unknown combat host port" {:kind kind})))
  (when-not (ifn? port)
    (throw (ex-info "combat host port must be callable" {:kind kind :value port})))
  (swap! host-ports* assoc kind port)
  port)

(defn host-port [kind] (get @host-ports* kind))
(defn host-ports [] @host-ports*)
(defn clear-host-ports! [] (reset! host-ports* {}) nil)

(defn- require-key [m k]
  (when-not (contains? m k)
    (throw (ex-info "missing combat contract field" {:field k :value m})))
  m)

(defn intent [value]
  (when (and (contains? value :schema-version)
             (not= schema-version (:schema-version value)))
    (throw (ex-info "combat intent schema version mismatch"
                    {:expected schema-version :actual (:schema-version value)})))
  (let [value (-> value (require-key :intent-id) (require-key :op)
                  (require-key :owner) (assoc :schema-version schema-version))]
    (when-not (contains? intent-ops (:op value))
      (throw (ex-info "unknown combat intent operation" {:value value})))
    (when-not (or (string? (:intent-id value)) (integer? (:intent-id value)))
      (throw (ex-info "combat intent-id must be string or integer" {:value value})))
    (when (contains? value :slot)
      (when-not (and (integer? (:slot value)) (<= 0 (long (:slot value)) 63))
        (throw (ex-info "combat slot must be an integer in [0,63]" {:value value}))))
    (when (contains? value :client-tick)
      (when-not (integer? (:client-tick value))
        (throw (ex-info "combat client-tick must be an integer" {:value value}))))
    value))

(defn result [value]
  (let [value (merge {:schema-version schema-version
                      :status :accepted
                      :state-patch []
                      :session-ops []
                      :world-effects []
                      :effect-results []
                      :vfx-signals []
                      :events []
                      :feedback []}
                     value)]
    (when-not (#{:accepted :rejected} (:status value))
      (throw (ex-info "invalid combat result status" {:value value})))
    value))

(defn query-request [{:keys [kind owner args] :as request}]
  (when-not (and kind owner (map? args))
    (throw (ex-info "invalid combat query request" {:request request})))
  (assoc request :schema-version schema-version))

(defn world-effect [{:keys [type] :as effect}]
  (when-not (keyword? type)
    (throw (ex-info "combat world effect requires keyword type" {:effect effect})))
  (assoc effect :schema-version schema-version))

(defn effect-result [value]
  (when-not (map? value)
    (throw (ex-info "combat effect result must be a map" {:value value})))
  (assoc value :schema-version schema-version))

(defn domain-event [value]
  (when-not (map? value)
    (throw (ex-info "combat domain event must be a map" {:value value})))
  (assoc value :schema-version schema-version))

(defn signal [value]
  (let [value (-> value (require-key :op)
                  (assoc :schema-version schema-version
                         :seed (long (or (:seed value) 0))))]
    (when-not (#{:spawn :signal :destroy :clear-owner} (:op value))
      (throw (ex-info "unknown combat VFX signal operation" {:value value})))
    (when (#{:spawn :signal} (:op value))
      (doseq [k [:effect-id :instance-key :owner :event-seq]]
        (require-key value k)))
    (when (and (#{:spawn :signal :destroy} (:op value))
               (contains? value :event-seq)
               (not (integer? (:event-seq value))))
      (throw (ex-info "combat VFX event-seq must be an integer" {:value value})))
    value))
