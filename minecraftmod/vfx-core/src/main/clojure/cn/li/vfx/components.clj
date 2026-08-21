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
           [:vfx/ray-beam #{:start :end :life-ticks :grow-ticks :style}]
           [:vfx/ray-fan #{:origin :direction :length :count
                           :yaw-range-degrees :pitch-range-degrees
                           :life-ticks :grow-ticks :style :seed}]
           ;; Generic chained arc burst: one caster ray plus a bounded list of
           ;; impact-to-target arcs. Geometry, palette, lifetime and sound are
           ;; supplied by the effect document; no skill is encoded.
           [:vfx/arc-strike #{:start :end :aoe-origin :aoe-points
                              :arc-life-ticks :pattern :hand-origin?
                              :sound-id :sound-volume :sound-pitch
                              :sound-position :bounds-radius :seed}]
           [:vfx/channel-arc #{:mode :caster :target :block-pos :block-bounds
                              :good? :charge-ticks :visual-max-ticks :style :seed}]
           ;; Generic client-side block scan/highlight.  The filter, palette,
           ;; texture and scan policy are all supplied by the effect document;
           ;; this node contains no ore/skill knowledge.
           [:vfx/block-scan #{:origin :range :max-range :filter :advanced?
                              :life-ticks :rescan-interval :max-results
                              :texture :base-color :tier-colors :seed}]
           ;; Generic progress geometry around a block target.  Palette,
           ;; pulse and progress are supplied by the effect document; this
           ;; node has no mining/skill knowledge.
           [:vfx/block-progress #{:target :progress :color}]
           [:vfx/beam-bounds #{:start :end :radius}]
           [:vfx/arc-field #{:start :end :count-limit :life-ticks}]
           [:vfx/vortex-column #{:base :axis :height :spacing :radius
                                 :count-limit :life-ticks :seed :alpha
                                 :orientation}]
           [:vfx/ring #{:center :radius :segments}]
           ;; Generic target-mark sparks; palette, density and lifetime are
           ;; supplied by the effect document rather than hard-coded here.
           [:vfx/mark-sparks #{:position :ttl-ticks :count :radius :color :seed}]
           [:vfx/billboard-sequence #{:anchor :texture-pattern :frame-count}]
           ;; A model marker is deliberately geometry/asset agnostic.  The
           ;; reusable humanoid shape lives in an AC VFX composite EDN; core
           ;; only validates and lowers the generic model/UV contract.
           [:vfx/model-marker #{:anchor :texture-pattern :frame-count
                                :frame-period-ticks :parts :color :facing}]
           [:vfx/emitter #{:anchor :rate-per-tick :limit}]
           [:vfx/ribbon #{:points :max-points}]
           ;; Generic configurable ballistic preview. Physics, origin offset,
           ;; segment count and style are supplied by the effect document.
           [:vfx/trajectory-ribbon #{:origin :initial-velocity :look-dir
                                      :lateral-offset :forward-offset
                                      :vertical-offset :drag :gravity :dt
                                      :segments :width :style :can-perform?}]
           [:vfx/fade #{:from-tick :to-tick :child}]
           [:vfx/scale #{:from :to :child}]
           [:vfx/noise #{:seed :amplitude :child}]
           [:vfx/attach #{:anchor-type :owner :child}]
           [:vfx/first-person-transform #{:offset :child}]
           [:vfx/camera #{:operation :value :duration-ticks}]
           [:vfx/audio-one-shot #{:sound-id :position}]
           [:vfx/audio-loop #{:sound-id :position :volume :pitch :instance-key
                              :stop-on-destroy?}]]]
    (when-not (descriptor id)
      (register! {:id id
                  :revision 1
                  :schema {:required required}
                  :lower identity-lower})))
  nil)
