(ns cn.li.vfx.vocabulary
  "The VFX node vocabulary: descriptor specs for every :component id
   cn.li.vfx.final-engine's sample-node dispatches on directly.

   Relocated from ac/final_vocabulary.clj, which held this vocabulary in a
   content module despite none of it being AC-specific.

   NOT here: :effect/vfx. Despite the name, it is combat's own outbound
   bridge node (final_engine.clj's :effect/vfx case emits a VFX intent the
   same way :combat/damage or :world/lightning emit other effects), and
   real combat ability graphs reference {:component :effect/vfx ...}
   directly -- its descriptor must be resolvable in COMBAT's environment,
   not this one. See cn.li.combat.vocabulary. (First cut of this split put
   it here on namespace-prefix grounds alone; a full ac test-suite run
   caught the resulting :unknown-component failures immediately.)

   vfx-runtime-specs below, by contrast, genuinely is NOT needed by
   combat's environment even though the original final_vocabulary.clj
   folded it in there unconditionally: verified via a full ac test-suite
   run (see the P2.1 refactor commit) that removing it changed zero test
   outcomes. :effect/vfx declares :children {}, so nothing nested inside an
   :effect/vfx node is ever structurally walked as a VFX sub-node tree --
   only :effect/vfx's OWN descriptor needs to be resolvable from a compiled
   combat graph, never vfx-runtime-specs' :vfx/branch, :vfx/let, etc.

   :vfx/beam-arc-fade and :vfx/humanoid-marker are deliberately NOT
   declared here as full specs, even though the original final_vocabulary
   had them: both are ALSO registered as composites in
   ac/vfx/composites.edn (beam_arc_fade.edn, humanoid_marker.edn) --
   the exact same duplicate-id shape as :combat/projectile-reflection-scan
   (see cn.li.combat.vocabulary), just never surfaced as an environment/
   build crash because nothing ever built ONE environment containing both
   copies until now. Declared only as composite-only-ids placeholders
   below; their real bodies come from the loaded composite documents,
   which always win over a placeholder (see environment)."
  (:require [cn.li.node.environment :as descriptors]))

(def ^:private component-specs
  [
   {:id :vfx/arc-field, :inputs {:count-limit {:type :any, :default nil}, :life-ticks {:type :any, :default nil}, :start {:type :any, :default nil}, :radius {:type :any, :default nil}, :seed {:type :any, :default nil}, :end {:type :any, :default nil}, :spacing {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/arc-strike, :inputs {:hand-origin? {:type :any, :default nil}, :sound-position {:type :any, :default nil}, :sound-pitch {:type :any, :default nil}, :sound-volume {:type :any, :default nil}, :start {:type :any, :default nil}, :seed {:type :any, :default nil}, :sound-id {:type :any, :default nil}, :arc-life-ticks {:type :any, :default nil}, :aoe-points {:type :any, :default nil}, :aoe-origin {:type :any, :default nil}, :end {:type :any, :default nil}, :bounds-radius {:type :any, :default nil}, :pattern {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/audio-loop, :inputs {:pitch {:type :any, :default nil}, :stop-on-destroy? {:type :any, :default nil}, :instance-key {:type :any, :default nil, :editor-visible? false}, :volume {:type :any, :default nil}, :sound-id {:type :any, :default nil}, :position {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/audio-one-shot, :inputs {:pitch {:type :any, :default nil}, :volume {:type :any, :default nil}, :sound-id {:type :any, :default nil}, :position {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/beam, :inputs {:start {:type :any, :default nil}, :layers {:type :any, :default nil}, :grow-ticks {:type :any, :default nil}, :end {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/billboard-sequence, :inputs {:texture-pattern {:type :any, :default nil}, :half-size {:type :any, :default nil}, :frame-duration-ms {:type :any, :default nil}, :anchor {:type :any, :default nil}, :frame-count {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/block-progress, :inputs {:color {:type :any, :default nil}, :pulse-period {:type :any, :default nil}, :width {:type :any, :default nil}, :corner-length {:type :any, :default nil}, :target {:type :any, :default nil}, :progress {:type :any, :default nil}, :height {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/block-scan, :inputs {:life-ticks {:type :any, :default nil}, :rescan-interval {:type :any, :default nil}, :max-results {:type :any, :default nil}, :max-range {:type :any, :default nil}, :seed {:type :any, :default nil}, :filter {:type :any, :default nil}, :origin {:type :any, :default nil}, :tier-colors {:type :any, :default nil}, :base-color {:type :any, :default nil}, :texture {:type :any, :default nil}, :advanced? {:type :any, :default nil}, :range {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/camera, :inputs {:duration-ticks {:type :any, :default nil}, :value {:type :any, :default nil}, :operation {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/channel-arc, :inputs {:block-bounds {:type :any, :default nil}, :charge-ticks {:type :any, :default nil}, :mode {:type :any, :default nil}, :seed {:type :any, :default nil}, :style {:type :any, :default nil}, :good? {:type :any, :default nil}, :target {:type :any, :default nil}, :caster {:type :any, :default nil}, :block-pos {:type :any, :default nil}, :visual-max-ticks {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/charge-ring, :inputs {:max-charge-ticks {:type :any, :default nil}, :charge-ticks {:type :any, :default nil}, :punched? {:type :any, :default nil}, :points {:type :any, :default nil}, :center {:type :any, :default nil}, :pulse-frequency {:type :any, :default nil}, :core-color {:type :any, :default nil}, :outer-color {:type :any, :default nil}, :base-radius {:type :any, :default nil}, :pulse-amplitude {:type :any, :default nil}, :radius-growth {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/charge-slow, :inputs {:speed {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/directional-wave, :inputs {:forward-speed {:type :any, :default nil}, :fade-in-ratio {:type :any, :default nil}, :ring-offset-step {:type :any, :default nil}, :life-ticks {:type :any, :default nil}, :ring-size-min {:type :any, :default nil}, :mid-scale {:type :any, :default nil}, :fade-out-ratio {:type :any, :default nil}, :color {:type :any, :default nil}, :time-offset-jitter {:type :any, :default nil}, :ring-size-max {:type :any, :default nil}, :ring-life-min {:type :any, :default nil}, :full-ratio {:type :any, :default nil}, :final-scale {:type :any, :default nil}, :ring-offset-jitter {:type :any, :default nil}, :seed {:type :any, :default nil}, :mid-ratio {:type :any, :default nil}, :time-offset-step {:type :any, :default nil}, :ring-count-max {:type :any, :default nil}, :ring-life-jitter {:type :any, :default nil}, :ring-life-max {:type :any, :default nil}, :growth-ticks {:type :any, :default nil}, :position {:type :any, :default nil}, :ring-count-min {:type :any, :default nil}, :initial-scale {:type :any, :default nil}, :texture {:type :any, :default nil}, :direction {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/emitter, :inputs {:limit {:type :any, :default nil}, :particle {:type :any, :default nil}, :rate-per-tick {:type :any, :default nil}, :chance {:type :any, :default nil}, :anchor {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/fade, :inputs {:to-alpha {:type :any, :default nil}, :to-tick {:type :any, :default nil}, :from-alpha {:type :any, :default nil}, :from-tick {:type :any, :default nil}}, :outputs {}, :children {:child {:kind :single, :flow :sequential}}}
   {:id :vfx/first-person-motion, :inputs {:stage {:type :any, :default nil}, :phase-ticks {:type :any, :default nil}, :duration-ticks {:type :any, :default nil}, :curves {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/impact-burst, :inputs {:spray-duplicates {:type :any, :default nil}, :spray-life-ticks {:type :any, :default nil}, :look-dir {:type :any, :default nil}, :splash-frame-count {:type :any, :default nil}, :splash-frame-duration-ms {:type :any, :default nil}, :splash-texture-pattern {:type :any, :default nil}, :splash-life-ticks {:type :any, :default nil}, :target-height {:type :any, :default nil}, :seed {:type :any, :default nil}, :spray-textures {:type :any, :default nil}, :origin {:type :any, :default nil}, :surface-hits {:type :any, :default nil}, :splash-count {:type :any, :default nil}, :splash-size {:type :any, :default nil}, :target-width {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/mark-sparks, :inputs {:ttl-ticks {:type :any, :default nil}, :color {:type :any, :default nil}, :radius {:type :any, :default nil}, :seed {:type :any, :default nil}, :count {:type :any, :default nil}, :position {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/particle-trail, :inputs {:count-limit {:type :any, :default nil}, :life-ticks {:type :any, :default nil}, :fade-out {:type :any, :default nil}, :start {:type :any, :default nil}, :radius {:type :any, :default nil}, :seed {:type :any, :default nil}, :size {:type :any, :default nil}, :alpha {:type :any, :default nil}, :end {:type :any, :default nil}, :velocity {:type :any, :default nil}, :texture {:type :any, :default nil}, :spacing {:type :any, :default nil}, :fade-in {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/ray-beam, :inputs {:life-ticks {:type :any, :default nil}, :start {:type :any, :default nil}, :style {:type :any, :default nil}, :grow-ticks {:type :any, :default nil}, :end {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/ray-fan, :inputs {:life-ticks {:type :any, :default nil}, :yaw-range-degrees {:type :any, :default nil}, :pitch-range-degrees {:type :any, :default nil}, :seed {:type :any, :default nil}, :style {:type :any, :default nil}, :count {:type :any, :default nil}, :length {:type :any, :default nil}, :grow-ticks {:type :any, :default nil}, :origin {:type :any, :default nil}, :direction {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/ring, :inputs {:color {:type :any, :default nil}, :segments {:type :any, :default nil}, :radius {:type :any, :default nil}, :center {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/timeline, :inputs {:children {:type :any, :default nil}, :duration-ticks {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/trajectory-ribbon, :inputs {:lateral-offset {:type :any, :default nil}, :look-dir {:type :any, :default nil}, :can-perform? {:type :any, :default nil}, :dt {:type :any, :default nil}, :vertical-offset {:type :any, :default nil}, :initial-velocity {:type :any, :default nil}, :width {:type :any, :default nil}, :segments {:type :any, :default nil}, :gravity {:type :any, :default nil}, :forward-offset {:type :any, :default nil}, :style {:type :any, :default nil}, :origin {:type :any, :default nil}, :drag {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/vortex-column, :inputs {:count-limit {:type :any, :default nil}, :life-ticks {:type :any, :default nil}, :orientation {:type :any, :default nil}, :radius {:type :any, :default nil}, :seed {:type :any, :default nil}, :alpha {:type :any, :default nil}, :base {:type :any, :default nil}, :axis {:type :any, :default nil}, :height {:type :any, :default nil}, :spacing {:type :any, :default nil}}, :outputs {}, :children {}}
   ])

(def ^:private vfx-runtime-specs
  "Explicit ABI for structural/render nodes handled by vfx-core's sampler."
  [{:id :vfx/beam-bounds :inputs {:start {:type :any} :end {:type :any} :radius {:type :any}} :outputs {:bounds {:type :any}}}
   {:id :vfx/branch :inputs {:when {:type :any}} :outputs {} :children {:then {:kind :single :flow :sequential} :else {:kind :single :flow :sequential}}}
   {:id :vfx/group :inputs {} :outputs {} :children {:children {:kind :seq :flow :sequential}}}
   ;; :binds-locals-map #{:bindings}: added when this moved here from
   ;; final_vocabulary.clj (P1.3's prerequisite for P2.3's compiler swap).
   ;; :vfx/let introduces new local names through {:bindings {name expr}} --
   ;; the name is on the KEY side, a shape cn.li.node.composite's
   ;; rename-locals could not rename correctly until :binds-locals-map was
   ;; added there. Without this field declared, a composite body using
   ;; :vfx/let could only be safely inlined once per program.
   {:id :vfx/let :inputs {:bindings {:type :any}} :outputs {} :children {:child {:kind :single :flow :sequential}} :binds-locals-map #{:bindings}}
   {:id :vfx/line :inputs {:from {:type :any} :to {:type :any} :color {:type :any} :material {:type :any} :geometry {:type :any}} :outputs {}}
   {:id :vfx/model-marker :inputs {:anchor {:type :any} :texture-pattern {:type :any} :frame-count {:type :any} :frame-period-ticks {:type :any} :color {:type :any} :facing {:type :any} :owner {:type :any} :parts {:type :any} :no-depth-test? {:type :any} :no-cull? {:type :any}} :outputs {}}
   {:id :vfx/repeat :inputs {:count {:type :any} :index-as {:type :any}} :outputs {} :children {:body {:kind :single :flow :sequential}}}])

(def ^:private composite-only-ids
  "Ids that exist ONLY as composites (their bodies live in
   vfx-core/src/main/resources/cn/li/vfx/composites/*.edn, loaded and merged
   into the environment by (environment composites) below, which always
   wins over this placeholder). :vfx.fx/* ids use a nested namespace
   segment (vfx.fx, not vfx) because that is the id ac/vfx/composites.edn
   already declares them under."
  #{:vfx.fx/charge-ring :vfx.fx/block-progress :vfx.fx/trajectory-ribbon
    :vfx/beam-arc-fade :vfx/humanoid-marker})

(defn- composite-spec [id]
  {:id id :revision 1 :layer :composite :visibility :author :category :final
   :doc (str "VFX composite " id) :inputs {} :outputs {} :children {}})

(defn- normalize-field [field]
  (cond-> (or field {:type :any})
    (not (contains? field :default)) (assoc :default nil)))

(defn- normalize-spec [spec]
  (-> spec
      (assoc :revision (long (or (:revision spec) 1))
             :visibility (or (:visibility spec) :author)
             :category (or (:category spec) :final)
             :doc (or (:doc spec) (str "VFX node " (:id spec)))
             :inputs (into {} (map (fn [[k v]] [k (normalize-field v)]) (or (:inputs spec) {})))
             :outputs (into {} (map (fn [[k v]] [k (normalize-field v)]) (or (:outputs spec) {})))
             :children (or (:children spec) {}))))

(defn descriptor-specs []
  (mapv (fn [spec]
          (let [spec (normalize-spec spec)
                layer (or (:layer spec) :primitive)]
            (case layer
              :composite spec
              (assoc spec :layer :primitive :impl (or (:impl spec) (fn [_ _] {}))))))
        (concat component-specs vfx-runtime-specs (map composite-spec composite-only-ids))))

(defn environment
  ([] (environment []))
  ([composites]
   (let [loaded (into {} (map (fn [[id spec]] [id (normalize-spec spec)]) (or composites {})))]
     (descriptors/build {:descriptors (concat (remove #(contains? loaded (:id %)) (descriptor-specs)) (vals loaded))}))))
