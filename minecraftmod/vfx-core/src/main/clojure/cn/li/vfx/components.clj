(ns cn.li.vfx.components
  "Data-only VFX component registry; all lowering remains Clojure." )

(defonce ^:private registry*
  (atom {:frozen? false :components {}}))

(defn register!
  [{:keys [id revision schema lower] :as descriptor}]
  (when (:frozen? @registry*)
    (throw (ex-info "VFX component registry is frozen" {:id id})))
  (when-not (and (keyword? id)
                 (integer? revision)
                 (map? schema)
                 (ifn? lower))
    (throw (ex-info "invalid VFX component descriptor"
                    {:descriptor descriptor})))
  (when (contains? (:components @registry*) id)
    (throw (ex-info "duplicate VFX component" {:id id})))
  (swap! registry* update :components assoc id descriptor)
  id)

(defn descriptor [id]
  (get-in @registry* [:components id]))

(defn descriptors [] (:components @registry*))

(defn freeze! []
  (swap! registry* assoc :frozen? true)
  (:components @registry*))

(defn reset-for-test! []
  (reset! registry* {:frozen? false :components {}})
  nil)

(defn- identity-lower [compiler node]
  (update compiler :ir conj {:ir/op :vfx-component
                             :component (:component node)
                             :data (dissoc node :component)}))

(defn register-builtins! []
  (doseq [[id required]
          [[:vfx/timeline #{:duration-ticks :children}]
           [:vfx/event-switch #{:cases}]
           [:vfx/beam #{:start :end :layers}]
           [:vfx/beam-bounds #{:start :end :radius}]
           [:vfx/arc-field #{:start :end :count-limit :life-ticks}]
           [:vfx/vortex-column #{:base :axis :height :spacing :radius
                                 :count-limit :life-ticks :seed}]
           [:vfx/ring #{:center :radius :segments}]
           [:vfx/billboard-sequence #{:anchor :texture-pattern :frame-count}]
           ;; A model marker is deliberately geometry/asset agnostic.  The
           ;; reusable humanoid shape lives in an AC VFX composite EDN; core
           ;; only validates and lowers the generic model/UV contract.
           [:vfx/model-marker #{:anchor :texture-pattern :frame-count
                                :frame-period-ticks :parts :color :facing}]
           [:vfx/emitter #{:anchor :rate-per-tick :limit}]
           [:vfx/ribbon #{:points :max-points}]
           [:vfx/fade #{:from-tick :to-tick :child}]
           [:vfx/scale #{:from :to :child}]
           [:vfx/noise #{:seed :amplitude :child}]
           [:vfx/attach #{:anchor-type :owner :child}]
           [:vfx/first-person-transform #{:offset :child}]
           [:vfx/camera #{:operation :value :duration-ticks}]
           [:vfx/audio-one-shot #{:sound-id :position}]
           [:vfx/audio-loop #{:sound-id :instance-key :stop-on-destroy?}]]]
    (when-not (descriptor id)
      (register! {:id id
                  :revision 1
                  :schema {:required required}
                  :lower identity-lower})))
  nil)
