(ns cn.li.combat.vocabulary
  "The combat node vocabulary: descriptor specs for every :component id
   cn.li.combat.final-engine's run-node dispatches on directly (either an
   explicit case branch -- :cost/spend, :cooldown/start, :flow/control,
   :end, :effect/vfx, ... -- or node-kind dispatch onto a host capability).

   Relocated from ac/final_vocabulary.clj, which held this vocabulary in a
   content module despite none of it being AC-specific -- every id here is
   generic combat vocabulary a second content module (bc/cc) would need
   verbatim. Split by keyword namespace against the module that owns the
   corresponding EXECUTOR: this file owns everything final-engine.clj's
   run-node handles directly or routes to a combat host capability;
   cn.li.combat.kernels owns the :kernel/* internal execution boundaries;
   cn.li.vfx.vocabulary owns :vfx/* and :fx/*.

   :effect/vfx stays here despite its name: it is combat's own outbound
   VFX-intent-emission bridge node (final-engine.clj's :effect/vfx case),
   and real combat ability graphs reference it directly as a :component --
   it is namespace-prefix-adjacent to vfx-core but executor-adjacent to
   combat-core, and the executor is what determines ownership here. (A
   first cut of this split moved it to vfx-core on namespace grounds alone;
   a full ac test-suite run immediately caught the resulting
   :unknown-component failures -- see cn.li.vfx.vocabulary's docstring.)

   :flow/sequence, :flow/branch, :flow/foreach, :data/bind and :flow/finish
   are deliberately NOT declared here: cn.li.node.flow/builtin-descriptors
   already has real (non-no-op) descriptors for exactly these five ids, and
   the versions that used to live in final_vocabulary.clj were literal
   duplicates -- {:impl (fn [_ _] {})} placeholders that were never called
   (final-engine.clj has its own run-sequence/run-branch/run-foreach
   handling for these ids; see final_engine.clj's :flow/sequence,
   :flow/branch, :flow/foreach case branches). :finalize, :flow/control,
   :flow/once, :flow/phases and :flow/finish are combat-specific structural
   nodes with no node-core equivalent (or, for :flow/finish, an equivalent
   that is missing a combat-only field -- see the :flow/finish entry below)
   and stay here.

   Verified (via a full ac test-suite run, see the P2.1 refactor commit)
   that vfx-core's vfx-runtime-specs -- which the original
   final_vocabulary.clj's descriptor-specs unconditionally folded into this
   SAME environment -- is not needed here: :effect/vfx (kept above) declares
   :children {}, so nothing nested inside it is ever structurally walked as
   a VFX sub-node tree, and removing vfx-runtime-specs from the environment
   changed zero test outcomes across the whole suite. Carrying it here
   would have required combat-core to depend on vfx-core, the one
   dependency edge this codebase gates hardest against."
  (:require [cn.li.node.environment :as descriptors]
            [cn.li.node.flow :as flow]
            [cn.li.combat.kernels :as kernels]))

(def ^:private component-specs
  [
   {:id :ability/budget, :inputs {:name {:type :any, :default nil}}, :outputs {:budget {:type :any}}, :children {}}
   {:id :ability/caster, :inputs {}, :outputs {:normal-metal-blocks {:type :any}, :aim {:type :any}, :world-id {:type :any}, :charge-ticks {:type :any}, :eye {:type :any}, :eye-y {:type :any}, :metal-entities {:type :any}, :seed {:type :any}, :weak-metal-blocks {:type :any}, :level {:type :any}, :id {:type :any}, :creative? {:type :any}, :mastery {:type :any}, :body {:type :any}}, :children {}}
   {:id :ability/context, :inputs {:name {:type :any, :default nil}}, :outputs {:value {:type :any}}, :children {}}
   {:id :ability/cooldown, :inputs {:name {:type :any, :default nil}}, :outputs {:cooldown {:type :any}}, :children {}}
   {:id :ability/progression, :inputs {:name {:type :any, :default nil}}, :outputs {:progression {:type :any}}, :children {}}
   {:id :ability/tunable, :inputs {:name {:type :any, :default nil}}, :outputs {:value {:type :any}}, :children {}}
   {:id :block/break, :inputs {:fortune-level {:type :any, :default nil}, :position {:type :any, :default nil}, :drop? {:type :any, :default nil}, :expected-block-id {:type :any, :default nil}, :tool-tier-capped? {:type :any, :default nil}, :barrier? {:type :any, :default nil}}, :outputs {:status {:type :any}, :block-id {:type :any}, :position {:type :any}}, :children {}}
   {:id :block/set, :inputs {:expected-block-ids {:type :any, :default nil}, :block-id {:type :any, :default nil}, :position {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :combat/damage, :inputs {:amount {:type :any, :default nil}, :damage-pipeline {:type :any, :default nil}, :world-id {:type :any, :default nil}, :damage-type {:type :any, :default nil}, :target {:type :any, :default nil}, :reset-invulnerable-time? {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :combat/impulse, :inputs {:vector {:type :any, :default nil}, :target {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :combat/status, :inputs {:duration-ticks {:type :any, :default nil}, :amplifier {:type :any, :default nil}, :status-id {:type :any, :default nil}, :target {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :cooldown/start, :inputs {:name {:type :any, :default nil}, :cooldown {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :cost/spend, :inputs {:scale {:type :any, :default nil}, :budget {:type :any, :default nil}, :partial? {:type :any, :default nil}}, :outputs {:insufficient? {:type :any}}, :children {:on-insufficient {:kind :single, :flow :sequential}}}
   {:id :damage/absorb, :inputs {:front? {:type :any, :default nil}, :progression-tag {:type :any, :default nil}, :cap {:type :any, :default nil}, :last-tick-path {:type :any, :default nil}, :interval-ticks {:type :any, :default nil}, :progression-scale {:type :any, :default nil}, :cost {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :damage/critical, :inputs {:damage-types {:type :any, :default nil}, :progression-mode {:type :any, :default nil}, :feedback {:type :any, :default nil}, :progression-per-level {:type :any, :default nil}, :events {:type :any, :default nil}, :levels {:type :any, :default nil}, :vfx {:type :any, :default nil}, :events-by-level {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :damage/multiply, :inputs {:multiplier {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :damage/reduce, :inputs {:max-cost {:type :any, :default nil}, :rate {:type :any, :default nil}, :vfx {:type :any, :default nil}, :progression-scale {:type :any, :default nil}, :ignore-threshold {:type :any, :default nil}, :cost-resource {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :damage/reflect, :inputs {:multiplier {:type :any, :default nil}, :cost-per-damage {:type :any, :default nil}, :progression-scale {:type :any, :default nil}, :minimum {:type :any, :default nil}, :max-depth {:type :any, :default nil}, :cost-resource {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :data/random-item, :inputs {:result {:type :any, :default nil}, :items {:type :any, :default nil}}, :outputs {:item {:type :any}}, :children {}}
   {:id :domain/event, :inputs {:payload {:type :any, :default nil}, :event-type {:type :any, :default nil}}, :outputs {}, :children {}}
   ;; :effect/vfx is combat's OWN outbound bridge node -- it emits a VFX
   ;; intent (final_engine.clj's :effect/vfx case: (emit context :vfx
   ;; (normalize-vfx node context path))), the same way :combat/damage or
   ;; :world/lightning emit other kinds of effects. It stays here, not in
   ;; vfx-core's vocabulary, despite the "vfx" in its name: real combat
   ;; ability graphs reference {:component :effect/vfx ...} directly, so
   ;; its descriptor must be resolvable in COMBAT's environment (confirmed
   ;; by :unknown-component failures across the ac test suite when this was
   ;; first misclassified into vfx-core's vocabulary instead).
   {:id :effect/vfx, :inputs {:payload {:type :any, :default nil}, :operation {:type :any, :default nil}, :instance-key {:type :any, :default nil, :editor-visible? false}, :audience {:type :any, :default nil}, :effect-id {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :energy/charge, :inputs {:amount {:type :any, :default nil}, :world-id {:type :any, :default nil}, :mode {:type :any, :default nil}, :target {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :energy/target, :inputs {:hit {:type :any, :default nil}}, :outputs {:energy-target {:type :any}}, :children {}}
   {:id :entity/configure, :inputs {:world-id {:type :any, :default nil}, :place-when-collide? {:type :any, :default nil}, :projectile-damage {:type :any, :default nil}, :block-id {:type :any, :default nil}, :add-tags {:type :any, :default nil}, :entity {:type :any, :default nil}, :velocity {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :entity/discard, :inputs {:world-id {:type :any, :default nil}, :entity {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :entity/mark, :inputs {:requires-ability {:type :any, :default nil}, :duration-ticks {:type :any, :default nil}, :target {:type :any, :default nil}, :mark-type {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :entity/reset-fall-damage, :inputs {:target {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :entity/spawn, :inputs {:add-tags {:type :any, :default nil}, :barrier? {:type :any, :default nil}, :entity-type {:type :any, :default nil}, :life-ticks {:type :any, :default nil}, :world-id {:type :any, :default nil}, :position {:type :any, :default nil}, :velocity {:type :any, :default nil}, :owner {:type :any, :default nil}}, :outputs {:entity-id {:type :any}, :status {:type :any}}, :children {}}
   {:id :entity/teleport, :inputs {:dismount? {:type :any, :default nil}, :world-id {:type :any, :default nil}, :reset-fall-damage? {:type :any, :default nil}, :position {:type :any, :default nil}, :target {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :entity/trigger-behavior, :inputs {:entity {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :finalize, :inputs {:outcome {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :flow/control, :inputs {:signal {:type :any, :default nil}}, :outputs {}, :children {}}
   ;; :flow/finish is declared here, NOT delegated to
   ;; node/flow.clj's builtin-descriptors like :flow/sequence,
   ;; :flow/branch, :flow/foreach and :data/bind are -- node-core's
   ;; :flow/finish declares only :outcome and :finish-ability?, but real
   ;; combat ability EDN uses a third field, :next-phase (combat's
   ;; :flow/phases multi-phase execution model has no node-core
   ;; equivalent, so node-core's descriptor has no reason to know about
   ;; it). Caught by an :unknown-field failure in
   ;; combat-runtime-edn-activation-smoke-test the first time this was
   ;; delegated. descriptor-specs below excludes node/flow's :flow/finish
   ;; from the merged flow/builtin-descriptors so the two don't collide as
   ;; a duplicate id.
   {:id :flow/finish, :inputs {:finish-ability? {:type :any, :default nil}, :next-phase {:type :any, :default nil}, :outcome {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :flow/once, :inputs {:key {:type :any, :default nil}, :scope {:type :any, :default nil}, :strategy {:type :any, :default nil}, :storage-path {:type :any, :default nil}}, :outputs {}, :children {:body {:kind :single, :flow :closed}, :on-first {:kind :single, :flow :closed}}}
   {:id :flow/phases, :inputs {}, :outputs {}, :children {:start {:kind :single, :flow :sequential}, :pulse {:kind :single, :flow :sequential}, :release {:kind :single, :flow :sequential}, :abort {:kind :single, :flow :sequential}, :events {:kind :case-map, :flow :branch}}}
   {:id :inventory/consume, :inputs {:source {:type :any, :default nil}, :count {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :inventory/place-or-drop, :inputs {:source {:type :any, :default nil}, :count {:type :any, :default nil}, :creative? {:type :any, :default nil}, :plan {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :inventory/settle, :inputs {:source {:type :any, :default nil}, :count {:type :any, :default nil}, :creative? {:type :any, :default nil}, :position {:type :any, :default nil}, :drop? {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :motion/entity-velocity, :inputs {:target {:type :any, :default nil}, :velocity {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :motion/entity-velocity-add, :inputs {:target {:type :any, :default nil}, :velocity {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :motion/flight, :inputs {:world-id {:type :any, :default nil}, :speed {:type :any, :default nil}, :reset-fall-damage? {:type :any, :default nil}, :near-ground-distance {:type :any, :default nil}, :near-ground-eye-height {:type :any, :default nil}, :hover-near-ground-velocity {:type :any, :default nil}, :hover-air-velocity {:type :any, :default nil}, :acceleration {:type :any, :default nil}, :direction {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :motion/velocity, :inputs {:dismount? {:type :any, :default nil}, :reset-fall-damage? {:type :any, :default nil}, :velocity {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :owner/can-fly, :inputs {:enabled? {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :owner/snapshot, :inputs {:result {:type :any, :default nil}, :projection {:type :any, :default nil}}, :outputs {:snapshot {:type :any}}, :children {}}
   {:id :projectile/redirect, :inputs {:replacement-types {:type :any, :default nil}, :difficulty {:type :any, :default nil}, :target-position {:type :any, :default nil}, :entity {:type :any, :default nil}, :velocity {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :projectile/schedule-beam, :inputs {:world-id {:type :any, :default nil}, :destination-selector {:type :any, :default nil}, :instance-key {:type :any, :default nil, :editor-visible? false}, :seed {:type :any, :default nil}, :damage {:type :any, :default nil}, :damage-type {:type :any, :default nil}, :origin-selector {:type :any, :default nil}, :delay-ticks {:type :any, :default nil}, :origin {:type :any, :default nil}, :settlement-vfx {:type :any, :default nil}, :destination {:type :any, :default nil}, :owner {:type :any, :default nil}, :exclude-owner? {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :resource/add, :inputs {:amount {:type :any, :default nil}, :resource {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :resource/enforce-floor, :inputs {:resource {:type :any, :default nil}, :minimum {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :score/mark, :inputs {:weight {:type :any, :default nil}, :tag {:type :any, :default nil}, :progression {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :state/read, :inputs {:key {:type :any, :default nil}}, :outputs {:value {:type :any}}, :children {}}
   {:id :state/write, :inputs {:key {:type :any, :default nil}, :value {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :target/block-placement, :inputs {:hit {:type :any, :default nil}, :policy {:type :any, :default nil}, :origin {:type :any, :default nil}, :distance {:type :any, :default nil}, :direction {:type :any, :default nil}}, :outputs {:destination {:type :any}}, :children {}}
   {:id :target/blocks, :inputs {:limit {:type :any, :default nil}, :shape {:type :any, :default nil}, :projection {:type :any, :default nil}}, :outputs {:blocks {:type :any}}, :children {}}
   {:id :target/directional-destination-query, :inputs {:look {:type :any, :default nil}, :eye-y {:type :any, :default nil}, :policy {:type :any, :default nil}, :origin {:type :any, :default nil}, :distance {:type :any, :default nil}, :direction {:type :any, :default nil}}, :outputs {:destination {:type :any}}, :children {}}
   {:id :target/entities, :inputs {:limit {:type :any, :default nil}, :filter {:type :any, :default nil}, :result {:type :any, :default nil}, :shape {:type :any, :default nil}, :sort {:type :any, :default nil}, :projection {:type :any, :default nil}}, :outputs {:entities {:type :any}}, :children {}}
   {:id :target/entity-snapshot, :inputs {:entity-id {:type :any, :default nil}, :projection {:type :any, :default nil}}, :outputs {:snapshot {:type :any}}, :children {}}
   {:id :target/item-held, :inputs {:source {:type :any, :default nil}}, :outputs {:held-item {:type :any}}, :children {}}
   {:id :target/raycast, :inputs {:include-blocks? {:type :any, :default nil}, :policy {:type :any, :default nil}, :include-entities? {:type :any, :default nil}, :result {:type :any, :default nil}, :living-only? {:type :any, :default nil}, :origin {:type :any, :default nil}, :distance {:type :any, :default nil}, :direction {:type :any, :default nil}}, :outputs {:hit {:type :any}}, :children {}}
   {:id :target/raycast-fan, :inputs {:pitch-angles {:type :any, :default nil}, :limit {:type :any, :default nil}, :yaw-range-degrees {:type :any, :default nil}, :seed {:type :any, :default nil}, :result {:type :any, :default nil}, :origin {:type :any, :default nil}, :distance {:type :any, :default nil}, :direction {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :target/resolve-destination, :inputs {:hit {:type :any, :default nil}, :policy {:type :any, :default nil}, :origin {:type :any, :default nil}, :distance {:type :any, :default nil}, :direction {:type :any, :default nil}}, :outputs {:destination {:type :any}}, :children {}}
   {:id :target/saved-location, :inputs {:location-name {:type :any, :default nil}}, :outputs {:location {:type :any}}, :children {}}
   {:id :world/explosion, :inputs {:world-id {:type :any, :default nil}, :terrain? {:type :any, :default nil}, :fire? {:type :any, :default nil}, :radius {:type :any, :default nil}, :position {:type :any, :default nil}, :owner {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :world/lightning, :inputs {:world-id {:type :any, :default nil}, :visual-only? {:type :any, :default nil}, :position {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :world/sound, :inputs {:world-id {:type :any, :default nil}, :sound-id {:type :any, :default nil}, :position {:type :any, :default nil}}, :outputs {}, :children {}}
   ])

(def ^:private composite-only-ids
  "Ids that exist ONLY as composites (their bodies live in
   combat-core/src/main/resources/cn/li/combat/composites/*.edn, loaded and
   merged into the environment by (environment composites) below, which
   always wins over this placeholder). :fx/lightning-strike is combat's
   despite the :fx/ namespace: it is declared in this module's own
   composites manifest, not vfx-core's."
  #{:combat/area-damage :combat/beam-strike :terrain/apply-break-budget :combat/impact-strike
    :combat/teleport-group :target/hold-destination :target/penetration-destination
    :target/raycast-destination :motion/radial-impulse :terrain/break-area
    :terrain/random-break :terrain/wave-plan :combat/release-with-cost
    :fx/lightning-strike :combat/charged-area-damage :combat/projectile-reflection-scan
    :target/directional-destination})

(defn- composite-spec [id]
  {:id id :revision 1 :layer :composite :visibility :author :category :final
   :doc (str "Final composite " id) :inputs {} :outputs {} :children {}})

(defn- normalize-field [field]
  (cond-> (or field {:type :any})
    (not (contains? field :default)) (assoc :default nil)))

(defn- node-kind-for [id]
  (case (namespace id)
    "flow" :flow "finalize" :flow "graph" :source
    "feedback" :feedback "domain" :feedback
    "policy" :policy "cost" :policy "cooldown" :policy
    "progression" :policy "score" :policy
    "target" :query "owner" :query "query" :query "energy" :query "terrain" :query
    "combat" :action "entity" :action "world" :action "block" :action
    "motion" :action "projectile" :action "inventory" :action
    "resource" :action "ability" :source "state" :source "data" :source
    ;; "vfx" is dead here (no :vfx/* id remains in this module's
    ;; component-specs -- they all moved to vfx-core), but "effect" is
    ;; very much alive: :effect/vfx stays in THIS module (see its own
    ;; comment above) and needs a :node-kind for
    ;; cn.li.combat.final-compiler/node-kind's dispatch. Restored both
    ;; branches from the original final_vocabulary.clj rather than
    ;; re-proving "vfx" is truly unused a second time -- an
    ;; :unknown-final-node-kind failure on :effect/vfx across the whole ac
    ;; suite is exactly what dropping "effect" caused the first time.
    "effect" :vfx "vfx" :vfx nil))

(defn- normalize-spec [spec]
  (-> spec
      (assoc :revision (long (or (:revision spec) 1))
             :visibility (or (:visibility spec) :author)
             :node-kind (or (:node-kind spec) (node-kind-for (:id spec)))
             :category (or (:category spec) :final)
             :doc (or (:doc spec) (str "Final node " (:id spec)))
             :inputs (into {} (map (fn [[k v]] [k (normalize-field v)]) (or (:inputs spec) {})))
             :outputs (into {} (map (fn [[k v]] [k (normalize-field v)]) (or (:outputs spec) {})))
             :children (or (:children spec) {}))))

(defn descriptor-specs []
  (mapv (fn [spec]
          (let [spec (normalize-spec spec)
                layer (or (:layer spec)
                          (if (= :source (:node-kind spec)) :source :primitive))]
            (case layer
              :composite spec
              :kernel spec
              :source (dissoc (assoc spec :layer :source) :impl :execution)
              ;; flow/builtin-descriptors' entries already declare :layer
              ;; :primitive AND a real :impl (run-sequence, run-branch, ...);
              ;; only fall back to the historical no-op when a spec has
              ;; none, instead of unconditionally stomping it -- the
              ;; original final_vocabulary.clj could stomp freely here
              ;; because every entry that ever reached this branch was raw
              ;; content-module data with no :impl of its own to begin with.
              (assoc spec :layer :primitive :impl (or (:impl spec) (fn [_ _] {}))))))
        (concat (remove #(= :flow/finish (:id %)) flow/builtin-descriptors)
                component-specs
                (map composite-spec composite-only-ids)
                kernels/kernel-specs)))

(defn environment
  ([] (environment []))
  ([composites]
   (let [loaded (into {} (map (fn [[id spec]] [id (normalize-spec spec)]) (or composites {})))]
     (descriptors/build {:descriptors (concat (remove #(contains? loaded (:id %)) (descriptor-specs)) (vals loaded))}))))
