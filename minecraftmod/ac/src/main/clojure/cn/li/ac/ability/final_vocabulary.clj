(ns cn.li.ac.ability.final-vocabulary
  "Static final node vocabulary. Descriptors are source-controlled data; no
   runtime graph inspection or descriptor synthesis is allowed."
  (:require [cn.li.node.environment :as descriptors]))

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
   {:id :combat/charged-area-damage, :inputs {:minimum-ticks {:type :any, :default nil}, :limit {:type :any, :default nil}, :maximum-ticks {:type :any, :default nil}, :ratio-min {:type :any, :default nil}, :ratio-slot {:type :any, :default nil}, :current-ticks {:type :any, :default nil}, :radius {:type :any, :default nil}, :center {:type :any, :default nil}, :filter {:type :any, :default nil}, :damage {:type :any, :default nil}, :damage-type {:type :any, :default nil}, :ratio-max {:type :any, :default nil}, :projection {:type :any, :default nil}}, :outputs {}, :children {:on-impact {:kind :single, :flow :sequential}}}
   {:id :combat/damage, :inputs {:amount {:type :any, :default nil}, :damage-pipeline {:type :any, :default nil}, :world-id {:type :any, :default nil}, :damage-type {:type :any, :default nil}, :target {:type :any, :default nil}, :reset-invulnerable-time? {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :combat/impulse, :inputs {:vector {:type :any, :default nil}, :target {:type :any, :default nil}}, :outputs {}, :children {}}
   ;; The scan callback receives one projected projectile as :projectile.
   {:id :combat/projectile-reflection-scan, :inputs {:excluded-tags {:type :any, :default nil}, :limit {:type :any, :default nil}, :radius {:type :any, :default nil}, :center {:type :any, :default nil}, :excluded-entity-ids {:type :any, :default nil}, :difficulty-entries {:type :any, :default nil}}, :outputs {}, :children {:body {:kind :single, :flow :closed}}, :binds-locals #{:projectile}, :child-binds-locals {:body #{:projectile}}}
   {:id :combat/status, :inputs {:duration-ticks {:type :any, :default nil}, :amplifier {:type :any, :default nil}, :status-id {:type :any, :default nil}, :target {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :cooldown/start, :inputs {:name {:type :any, :default nil}, :cooldown {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :cost/spend, :inputs {:scale {:type :any, :default nil}, :budget {:type :any, :default nil}, :partial? {:type :any, :default nil}}, :outputs {:insufficient? {:type :any}}, :children {:on-insufficient {:kind :single, :flow :sequential}}}
   {:id :damage/absorb, :inputs {:front? {:type :any, :default nil}, :exp-tag {:type :any, :default nil}, :cap {:type :any, :default nil}, :last-tick-path {:type :any, :default nil}, :interval-ticks {:type :any, :default nil}, :exp-scale {:type :any, :default nil}, :cost {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :damage/critical, :inputs {:damage-types {:type :any, :default nil}, :exp-mode {:type :any, :default nil}, :feedback {:type :any, :default nil}, :exp-per-level {:type :any, :default nil}, :events {:type :any, :default nil}, :levels {:type :any, :default nil}, :vfx {:type :any, :default nil}, :events-by-level {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :damage/multiply, :inputs {:multiplier {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :damage/reduce, :inputs {:max-cost {:type :any, :default nil}, :rate {:type :any, :default nil}, :vfx {:type :any, :default nil}, :exp-scale {:type :any, :default nil}, :ignore-threshold {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :damage/reflect, :inputs {:multiplier {:type :any, :default nil}, :cost-per-damage {:type :any, :default nil}, :exp-scale {:type :any, :default nil}, :minimum {:type :any, :default nil}, :max-depth {:type :any, :default nil}}, :outputs {}, :children {}}
   ;; A plain keyword :to binds the following sequential sibling.
   {:id :data/bind, :inputs {:value {:type :any, :default nil}, :to {:type :any, :default nil}}, :outputs {}, :children {}, :binds-locals #{:to}}
   {:id :data/random-item, :inputs {:result {:type :any, :default nil}, :items {:type :any, :default nil}}, :outputs {:item {:type :any}}, :children {}}
   {:id :domain/event, :inputs {:payload {:type :any, :default nil}, :event-type {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :effect/vfx, :inputs {:payload {:type :any, :default nil}, :operation {:type :any, :default nil}, :instance-key {:type :any, :default nil}, :audience {:type :any, :default nil}, :effect-id {:type :any, :default nil}}, :outputs {}, :children {}}
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
   ;; Branch arms are independent alternatives: only bindings common to both
   ;; arms may flow to the successor.
   {:id :flow/branch, :inputs {:when {:type :any, :default nil}}, :outputs {}, :children {:then {:kind :single, :flow :branch}, :else {:kind :single, :flow :branch}}}
   {:id :flow/control, :inputs {:signal {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :flow/finish, :inputs {:finish-ability? {:type :any, :default nil}, :next-phase {:type :any, :default nil}, :outcome {:type :any, :default nil}}, :outputs {}, :children {}}
   ;; Loop variables are visible only in the closed body and are renamed by
   ;; composite expansion through the descriptor's :binds-locals contract.
   {:id :flow/foreach, :inputs {:limit {:type :any, :default nil}, :as {:type :any, :default nil}, :items {:type :any, :default nil}, :index-as {:type :any, :default nil}}, :outputs {}, :children {:body {:kind :single, :flow :closed}}, :binds-locals #{:as :index-as}}
   ;; Both callbacks run in the wrapper's caller-provided lexical scope.  The
   ;; closed flow also lets the descriptor-driven checker carry the explicit
   ;; :projectile binding supplied by projectile-reflection-scan.
   {:id :flow/once, :inputs {:key {:type :any, :default nil}, :scope {:type :any, :default nil}, :strategy {:type :any, :default nil}, :storage-path {:type :any, :default nil}}, :outputs {}, :children {:body {:kind :single, :flow :closed}, :on-first {:kind :single, :flow :closed}}}
   {:id :flow/phases, :inputs {}, :outputs {}, :children {:start {:kind :single, :flow :sequential}, :pulse {:kind :single, :flow :sequential}, :release {:kind :single, :flow :sequential}, :abort {:kind :single, :flow :sequential}, :events {:kind :case-map, :flow :branch}}}
   {:id :flow/sequence, :inputs {}, :outputs {}, :children {:steps {:kind :seq, :flow :sequential}}}
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
   {:id :projectile/schedule-beam, :inputs {:world-id {:type :any, :default nil}, :destination-selector {:type :any, :default nil}, :instance-key {:type :any, :default nil}, :seed {:type :any, :default nil}, :damage {:type :any, :default nil}, :damage-type {:type :any, :default nil}, :origin-selector {:type :any, :default nil}, :delay-ticks {:type :any, :default nil}, :origin {:type :any, :default nil}, :settlement-vfx {:type :any, :default nil}, :destination {:type :any, :default nil}, :owner {:type :any, :default nil}, :exclude-owner? {:type :any, :default nil}}, :outputs {}, :children {}}
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
   {:id :vfx/arc-field, :inputs {:count-limit {:type :any, :default nil}, :life-ticks {:type :any, :default nil}, :start {:type :any, :default nil}, :radius {:type :any, :default nil}, :seed {:type :any, :default nil}, :end {:type :any, :default nil}, :spacing {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/arc-strike, :inputs {:hand-origin? {:type :any, :default nil}, :sound-position {:type :any, :default nil}, :sound-pitch {:type :any, :default nil}, :sound-volume {:type :any, :default nil}, :start {:type :any, :default nil}, :seed {:type :any, :default nil}, :sound-id {:type :any, :default nil}, :arc-life-ticks {:type :any, :default nil}, :aoe-points {:type :any, :default nil}, :aoe-origin {:type :any, :default nil}, :end {:type :any, :default nil}, :bounds-radius {:type :any, :default nil}, :pattern {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/audio-loop, :inputs {:pitch {:type :any, :default nil}, :stop-on-destroy? {:type :any, :default nil}, :instance-key {:type :any, :default nil}, :volume {:type :any, :default nil}, :sound-id {:type :any, :default nil}, :position {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/audio-one-shot, :inputs {:pitch {:type :any, :default nil}, :volume {:type :any, :default nil}, :sound-id {:type :any, :default nil}, :position {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/beam, :inputs {:start {:type :any, :default nil}, :layers {:type :any, :default nil}, :grow-ticks {:type :any, :default nil}, :end {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/beam-arc-fade, :inputs {:fade-at {:type :any, :default nil}, :duration-ticks {:type :any, :default nil}, :arc-at {:type :any, :default nil}, :arc-count-limit {:type :any, :default nil}, :beam-at {:type :any, :default nil}, :arc-radius {:type :any, :default nil}, :start {:type :any, :default nil}, :seed {:type :any, :default nil}, :ring-radius {:type :any, :default nil}, :arc-life-ticks {:type :any, :default nil}, :fade-to-tick {:type :any, :default nil}, :arc-spacing {:type :any, :default nil}, :fade-from-alpha {:type :any, :default nil}, :layers {:type :any, :default nil}, :grow-ticks {:type :any, :default nil}, :fade-to-alpha {:type :any, :default nil}, :end {:type :any, :default nil}, :fade-from-tick {:type :any, :default nil}, :ring-color {:type :any, :default nil}, :ring-segments {:type :any, :default nil}}, :outputs {}, :children {}}
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
   {:id :vfx/humanoid-marker, :inputs {:texture-pattern {:type :any, :default nil}, :frame-period-ticks {:type :any, :default nil}, :color {:type :any, :default nil}, :facing {:type :any, :default nil}, :anchor {:type :any, :default nil}, :frame-count {:type :any, :default nil}, :owner {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/impact-burst, :inputs {:spray-duplicates {:type :any, :default nil}, :spray-life-ticks {:type :any, :default nil}, :look-dir {:type :any, :default nil}, :splash-frame-count {:type :any, :default nil}, :splash-frame-duration-ms {:type :any, :default nil}, :splash-texture-pattern {:type :any, :default nil}, :splash-life-ticks {:type :any, :default nil}, :target-height {:type :any, :default nil}, :seed {:type :any, :default nil}, :spray-textures {:type :any, :default nil}, :origin {:type :any, :default nil}, :surface-hits {:type :any, :default nil}, :splash-count {:type :any, :default nil}, :splash-size {:type :any, :default nil}, :target-width {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/mark-sparks, :inputs {:ttl-ticks {:type :any, :default nil}, :color {:type :any, :default nil}, :radius {:type :any, :default nil}, :seed {:type :any, :default nil}, :count {:type :any, :default nil}, :position {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/particle-trail, :inputs {:count-limit {:type :any, :default nil}, :life-ticks {:type :any, :default nil}, :fade-out {:type :any, :default nil}, :start {:type :any, :default nil}, :radius {:type :any, :default nil}, :seed {:type :any, :default nil}, :size {:type :any, :default nil}, :alpha {:type :any, :default nil}, :end {:type :any, :default nil}, :velocity {:type :any, :default nil}, :texture {:type :any, :default nil}, :spacing {:type :any, :default nil}, :fade-in {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/ray-beam, :inputs {:life-ticks {:type :any, :default nil}, :start {:type :any, :default nil}, :style {:type :any, :default nil}, :grow-ticks {:type :any, :default nil}, :end {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/ray-fan, :inputs {:life-ticks {:type :any, :default nil}, :yaw-range-degrees {:type :any, :default nil}, :pitch-range-degrees {:type :any, :default nil}, :seed {:type :any, :default nil}, :style {:type :any, :default nil}, :count {:type :any, :default nil}, :length {:type :any, :default nil}, :grow-ticks {:type :any, :default nil}, :origin {:type :any, :default nil}, :direction {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/ring, :inputs {:color {:type :any, :default nil}, :segments {:type :any, :default nil}, :radius {:type :any, :default nil}, :center {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/timeline, :inputs {:children {:type :any, :default nil}, :duration-ticks {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/trajectory-ribbon, :inputs {:lateral-offset {:type :any, :default nil}, :look-dir {:type :any, :default nil}, :can-perform? {:type :any, :default nil}, :dt {:type :any, :default nil}, :vertical-offset {:type :any, :default nil}, :initial-velocity {:type :any, :default nil}, :width {:type :any, :default nil}, :segments {:type :any, :default nil}, :gravity {:type :any, :default nil}, :forward-offset {:type :any, :default nil}, :style {:type :any, :default nil}, :origin {:type :any, :default nil}, :drag {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :vfx/vortex-column, :inputs {:count-limit {:type :any, :default nil}, :life-ticks {:type :any, :default nil}, :orientation {:type :any, :default nil}, :radius {:type :any, :default nil}, :seed {:type :any, :default nil}, :alpha {:type :any, :default nil}, :base {:type :any, :default nil}, :axis {:type :any, :default nil}, :height {:type :any, :default nil}, :spacing {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :world/explosion, :inputs {:world-id {:type :any, :default nil}, :terrain? {:type :any, :default nil}, :fire? {:type :any, :default nil}, :radius {:type :any, :default nil}, :position {:type :any, :default nil}, :owner {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :world/lightning, :inputs {:world-id {:type :any, :default nil}, :visual-only? {:type :any, :default nil}, :position {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :world/sound, :inputs {:world-id {:type :any, :default nil}, :sound-id {:type :any, :default nil}, :position {:type :any, :default nil}}, :outputs {}, :children {}}
   ])

(def ^:private composite-only-ids
  #{:combat/area-damage :combat/beam-strike :terrain/apply-break-budget :combat/impact-strike
    :combat/teleport-group :target/hold-destination :target/penetration-destination
    :target/raycast-destination :motion/radial-impulse :terrain/break-area
    :terrain/random-break :terrain/wave-plan :combat/release-with-cost
    :fx/lightning-strike :combat/charged-area-damage :combat/projectile-reflection-scan
    :target/directional-destination})

(def ^:private vfx-runtime-specs
  "Explicit ABI for structural/render nodes handled by vfx-core's sampler."
  [{:id :vfx/beam-bounds :inputs {:start {:type :any} :end {:type :any} :radius {:type :any}} :outputs {:bounds {:type :any}}}
   {:id :vfx/branch :inputs {:when {:type :any}} :outputs {} :children {:then {:kind :single :flow :sequential} :else {:kind :single :flow :sequential}}}
   {:id :vfx/group :inputs {} :outputs {} :children {:children {:kind :seq :flow :sequential}}}
   {:id :vfx/let :inputs {:bindings {:type :any}} :outputs {} :children {:child {:kind :single :flow :sequential}}}
   {:id :vfx/line :inputs {:from {:type :any} :to {:type :any} :color {:type :any} :material {:type :any} :geometry {:type :any}} :outputs {}}
   {:id :vfx/model-marker :inputs {:anchor {:type :any} :texture-pattern {:type :any} :frame-count {:type :any} :frame-period-ticks {:type :any} :color {:type :any} :facing {:type :any} :owner {:type :any} :parts {:type :any} :no-depth-test? {:type :any} :no-cull? {:type :any}} :outputs {}}
   {:id :vfx/repeat :inputs {:count {:type :any} :index-as {:type :any}} :outputs {} :children {:body {:kind :single :flow :sequential}}}])

(def ^:private kernel-specs
  [{:id :kernel/break-area :revision 1 :layer :kernel :visibility :internal
    :inputs {:origin {:type :vec3} :radius {:type :float} :hardness-max {:type :float} :limit {:type :int}}
    :outputs {:broken-count {:type :int}}
    :node-kind :action :execution {:kind :combat-kernel :capability :kernel/terrain-break-area}}
   {:id :kernel/random-break :revision 1 :layer :kernel :visibility :internal
    :inputs {:origin {:type :vec3} :radius {:type :float} :attempts {:type :int} :hardness-max {:type :float} :seed {:type :int}}
    :outputs {:broken-count {:type :int}}
    :node-kind :action :execution {:kind :combat-kernel :capability :kernel/terrain-random-break}}
   {:id :kernel/apply-break-budget :revision 1 :layer :kernel :visibility :internal
    :inputs {:blocks {:type [:list-of :map]} :energy {:type :float} :limit {:type :int} :drop-chance {:type :float}}
    :outputs {:remaining-energy {:type :float}}
    :node-kind :action :execution {:kind :combat-kernel :capability :kernel/terrain-apply-break-budget}}
   {:id :kernel/radial-impulse :revision 1 :layer :kernel :visibility :internal
    :inputs {:center {:type :vec3} :radius {:type :float} :speed {:type :float} :limit {:type :int}}
    :outputs {:affected-count {:type :int}}
    :node-kind :action :execution {:kind :combat-kernel :capability :kernel/motion-radial-impulse}}
   {:id :kernel/terrain-wave-plan :revision 1 :layer :kernel :visibility :internal
    :inputs {:origin {:type :vec3} :direction {:type :vec3} :initial-energy {:type :float} :max-iterations {:type :int} :seed {:type :int}
             :spread {:type :map} :energy-cost {:type :map} :block-transforms {:type :map}
             :mastery {:type :float} :mastery-threshold {:type :float} :mastery-radius {:type :int}
             :mastery-hardness-cap {:type :float} :ground-break-probability {:type :float}
             :drop-probability {:type :float} :launch-base {:type :float} :launch-span {:type :float}
             :entity-search-radius {:type :float}}
    :outputs {:affected-blocks {:type [:list-of :map]} :transforms {:type [:list-of :map]} :broken-blocks {:type [:list-of :map]} :mastery-breaks {:type [:list-of :map]} :entities {:type [:list-of :map]}}
    :node-kind :query :execution {:kind :combat-kernel :capability :kernel/terrain-wave-plan}}
   {:id :kernel/trace-beam :revision 1 :layer :kernel :visibility :internal
    :inputs {:origin {:type :vec3} :trace-origin {:type :any, :default nil} :direction {:type :vec3} :length {:type :float} :visual-length {:type :any, :default nil} :radius {:type :float} :query-radius {:type :any, :default nil} :entity-limit {:type :int, :default 256} :damage {:type :float, :default 0.0} :damage-type {:type :resource-id, :default :generic} :block-limit {:type :int, :default 4096} :reflection-policy {:type :any, :default nil} :step {:type :any, :default nil}}
    :outputs {:beam {:type :map}}
    :node-kind :query :execution {:kind :combat-kernel :capability :kernel/trace-beam}}])

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
              (assoc spec :layer :primitive :impl (fn [_ _] {})))))
        (concat component-specs (map composite-spec composite-only-ids) kernel-specs vfx-runtime-specs)))
(defn environment
  ([] (environment []))
  ([composites]
   (let [loaded (into {} (map (fn [[id spec]] [id (normalize-spec spec)]) (or composites {})))]
     (descriptors/build {:descriptors (concat (remove #(contains? loaded (:id %)) (descriptor-specs)) (vals loaded))}))))






