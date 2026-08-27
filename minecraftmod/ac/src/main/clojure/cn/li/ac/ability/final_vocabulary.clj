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
   {:id :block/area-break, :inputs {:world-id {:type :any, :default nil}, :break-probability {:type :any, :default nil}, :self-drop? {:type :any, :default nil}, :radius {:type :any, :default nil}, :seed {:type :any, :default nil}, :drop-probability {:type :any, :default nil}, :hardness-max {:type :any, :default nil}, :origin {:type :any, :default nil}, :owner {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :block/break, :inputs {:fortune-level {:type :any, :default nil}, :position {:type :any, :default nil}, :drop? {:type :any, :default nil}, :expected-block-id {:type :any, :default nil}, :tool-tier-capped? {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :block/break-budget, :inputs {:world-id {:type :any, :default nil}, :limit {:type :any, :default nil}, :drop-chance {:type :any, :default nil}, :energy {:type :any, :default nil}, :seed {:type :any, :default nil}, :blocks {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :block/random-break, :inputs {:attempts {:type :any, :default nil}, :radius {:type :any, :default nil}, :seed {:type :any, :default nil}, :hardness-max {:type :any, :default nil}, :origin {:type :any, :default nil}}, :outputs {}, :children {}}
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
   {:id :entity/radial-impulse, :inputs {:world-id {:type :any, :default nil}, :speed-max {:type :any, :default nil}, :radius {:type :any, :default nil}, :seed {:type :any, :default nil}, :center {:type :any, :default nil}, :speed-min {:type :any, :default nil}, :owner {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :entity/reset-fall-damage, :inputs {:target {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :entity/spawn, :inputs {:entity-type {:type :any, :default nil}, :life-ticks {:type :any, :default nil}, :world-id {:type :any, :default nil}, :position {:type :any, :default nil}, :velocity {:type :any, :default nil}, :owner {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :entity/teleport, :inputs {:dismount? {:type :any, :default nil}, :world-id {:type :any, :default nil}, :reset-fall-damage? {:type :any, :default nil}, :position {:type :any, :default nil}, :target {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :entity/trigger-behavior, :inputs {:entity {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :finalize, :inputs {:outcome {:type :any, :default nil}}, :outputs {}, :children {}}
   ;; Branch arms are independent alternatives: only bindings common to both
   ;; arms may flow to the successor.
   {:id :flow/branch, :inputs {:when {:type :any, :default nil}}, :outputs {}, :children {:then {:kind :single, :flow :branch}, :else {:kind :single, :flow :branch}}}
   {:id :flow/control, :inputs {:signal {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :flow/finish, :inputs {:finish-session? {:type :any, :default nil}, :outcome {:type :any, :default nil}}, :outputs {}, :children {}}
   ;; Loop variables are visible only in the closed body and are renamed by
   ;; composite expansion through the descriptor's :binds-locals contract.
   {:id :flow/foreach, :inputs {:limit {:type :any, :default nil}, :as {:type :any, :default nil}, :items {:type :any, :default nil}, :index-as {:type :any, :default nil}}, :outputs {}, :children {:body {:kind :single, :flow :closed}}, :binds-locals #{:as :index-as}}
   ;; Both callbacks run in the wrapper's caller-provided lexical scope.  The
   ;; closed flow also lets the descriptor-driven checker carry the explicit
   ;; :projectile binding supplied by projectile-reflection-scan.
   {:id :flow/once, :inputs {:key {:type :any, :default nil}, :scope {:type :any, :default nil}, :strategy {:type :any, :default nil}, :storage-path {:type :any, :default nil}}, :outputs {}, :children {:body {:kind :single, :flow :closed}, :on-first {:kind :single, :flow :closed}}}
   {:id :flow/phases, :inputs {}, :outputs {}, :children {:start {:kind :single, :flow :sequential}, :pulse {:kind :single, :flow :sequential}, :release {:kind :single, :flow :sequential}, :abort {:kind :single, :flow :sequential}, :events {:kind :case-map, :flow :branch}}}
   {:id :flow/sequence, :inputs {}, :outputs {}, :children {:steps {:kind :seq, :flow :sequential}}}
   {:id :host/beam-trace, :inputs {:block-limit {:type :any, :default nil}, :query-radius {:type :any, :default nil}, :entity-limit {:type :any, :default nil}, :radius {:type :any, :default nil}, :damage {:type :any, :default nil}, :damage-type {:type :any, :default nil}, :result {:type :any, :default nil}, :trace-origin {:type :any, :default nil}, :length {:type :any, :default nil}, :origin {:type :any, :default nil}, :reflection-policy {:type :any, :default nil}, :step {:type :any, :default nil}, :direction {:type :any, :default nil}, :visual-length {:type :any, :default nil}}, :outputs {}, :children {}}
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
   {:id :session/read, :inputs {:key {:type :any, :default nil}}, :outputs {:value {:type :any}}, :children {}}
   {:id :session/write, :inputs {:key {:type :any, :default nil}, :value {:type :any, :default nil}}, :outputs {}, :children {}}
   {:id :target/beam-trace, :inputs {:block-limit {:type :any, :default nil}, :query-radius {:type :any, :default nil}, :entity-limit {:type :any, :default nil}, :radius {:type :any, :default nil}, :damage {:type :any, :default nil}, :damage-type {:type :any, :default nil}, :trace-origin {:type :any, :default nil}, :length {:type :any, :default nil}, :origin {:type :any, :default nil}, :reflection-policy {:type :any, :default nil}, :step {:type :any, :default nil}, :direction {:type :any, :default nil}, :visual-length {:type :any, :default nil}}, :outputs {:beam {:type :any}}, :children {}}
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
   {:id :terrain/propagate, :inputs {:launch-base {:type :any, :default nil}, :launch-span {:type :any, :default nil}, :spread {:type :any, :default nil}, :mastery-radius {:type :any, :default nil}, :ground-break-probability {:type :any, :default nil}, :mastery-threshold {:type :any, :default nil}, :entity-search-radius {:type :any, :default nil}, :max-iterations {:type :any, :default nil}, :energy-cost {:type :any, :default nil}, :seed {:type :any, :default nil}, :result {:type :any, :default nil}, :drop-probability {:type :any, :default nil}, :origin {:type :any, :default nil}, :mastery-hardness-cap {:type :any, :default nil}, :block-transforms {:type :any, :default nil}, :mastery {:type :any, :default nil}, :initial-energy {:type :any, :default nil}, :direction {:type :any, :default nil}}, :outputs {}, :children {}}
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
  #{:combat/area-damage :combat/beam-strike :combat/break-budget :combat/impact-strike
    :combat/teleport-group :target/hold-destination :target/penetration-destination
    :target/raycast-destination})

;; These identifiers occur in the VFX graph language but are not composites.
;; They are structural/render primitives; registering them as empty :mid nodes
;; would make the generic expander replace a real graph node with nil whenever
;; no external composite document is present.
(def ^:private vfx-runtime-specs
  "Explicit ABI for structural/render nodes handled by vfx-core's sampler.
   These are primitives from node-core's point of view (they have no external
   Minecraft side effect), but their fields/child ports are still declared so
   catalog validation and editor tooling cannot silently accept an arbitrary
   shape."
  [{:id :vfx/beam-bounds
    :inputs {:start {:type :any} :end {:type :any} :radius {:type :any}}
    :outputs {:bounds {:type :any}}}
   {:id :vfx/branch
    :inputs {:when {:type :any}}
    :outputs {} :children {:then {:kind :single :flow :sequential}
                           :else {:kind :single :flow :sequential}}}
   {:id :vfx/group
    :inputs {} :outputs {}
    :children {:children {:kind :seq :flow :sequential}}}
   {:id :vfx/let
    :inputs {:bindings {:type :any}}
    :outputs {} :children {:child {:kind :single :flow :sequential}}}
   {:id :vfx/line
    :inputs {:from {:type :any} :to {:type :any} :color {:type :any}
             :material {:type :any} :geometry {:type :any}}
    :outputs {}}
   {:id :vfx/model-marker
    :inputs {:anchor {:type :any} :texture-pattern {:type :any}
             :frame-count {:type :any} :frame-period-ticks {:type :any}
             :color {:type :any} :facing {:type :any} :owner {:type :any}
             :parts {:type :any} :no-depth-test? {:type :any}
             :no-cull? {:type :any}}
    :outputs {}}
   {:id :vfx/repeat
    :inputs {:count {:type :any} :index-as {:type :any}}
    :outputs {} :children {:body {:kind :single :flow :sequential}}}])

(defn- composite-spec [id]
  {:id id :revision 1 :layer :mid :category :final
   :doc (str "Final node component " id)
   :inputs {} :outputs {} :children {}})

(defn- normalize-field [field]
  (cond-> (or field {:type :any})
    (not (contains? field :default)) (assoc :default nil)))

(defn- normalize-spec [spec]
  (-> spec
      (assoc :revision (long (or (:revision spec) 1))
             :category (or (:category spec) :final)
             :doc (or (:doc spec) (str "Final node component " (:id spec)))
             :inputs (into {} (map (fn [[k v]] [k (normalize-field v)])
                                   (or (:inputs spec) {})))
             :outputs (into {} (map (fn [[k v]] [k (normalize-field v)])
                                    (or (:outputs spec) {})))
             :children (or (:children spec) {}))))

(defn descriptor-specs
  "Return the complete AC vocabulary as immutable descriptor values. No
   process-global registration occurs; each composition root owns its copy."
  []
  (mapv (fn [spec]
          (let [spec (normalize-spec spec)]
            (if (= :mid (:layer spec))
              spec
              (assoc spec :layer :primitive :impl (fn [_ _] {})))))
        (concat component-specs
                (map composite-spec composite-only-ids)
                vfx-runtime-specs)))

(defn environment
  "Build an AC-local NodeEnvironment, optionally extending it with loaded
   composite documents. The returned value is immutable and can be safely
   captured per server/player session without cross-player state."
  ([] (environment []))
  ([composites]
   (descriptors/build
    {:descriptors (concat (descriptor-specs)
                          (map normalize-spec (vals (or composites {}))))})))

(defn register!
  "Vocabulary inspection API retained under its historic name for tooling;
   it returns descriptors and never mutates process-global state."
  []
  (descriptor-specs))
