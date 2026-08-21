(ns cn.li.combat.examples
  "Generic examples for every combat middle-layer component.")

(def ^:private finish-example
  {:component :flow/finish :outcome :ok})

(def ^:private vec3-example {:vec3 [0.0 0.0 0.0]})

(def combat-node-examples
  {:flow/phases {:component :flow/phases :start finish-example :pulse finish-example
                 :release finish-example :abort finish-example
                 :events {:sample finish-example}}
   :flow/sequence {:component :flow/sequence
                   :steps [{:component :data/bind :to :value :value 1.0}
                           finish-example]}
   :flow/branch {:component :flow/branch :when {:expr :bool/not :args [false]}
                 :then finish-example :else {:component :flow/finish :outcome :idle}}
   :flow/foreach {:component :flow/foreach :items {:ref [:slot :items]} :as :item
                  :limit 64 :body {:component :combat/damage
                                   :target {:ref [:slot :item :id]}
                                   :amount 1.0}}
   :flow/window {:component :flow/window :value {:ref [:slot :value]}
                 :min-exclusive 0.0 :max-inclusive 1.0
                 :on-pass finish-example
                 :on-fail {:component :flow/finish :outcome :miss}}
   :flow/once {:component :flow/once :scope :owner :storage-path [:latches]
               :strategy :last-key :key {:ref [:slot :key]}
               :on-first finish-example
               :on-duplicate {:component :flow/finish :outcome :duplicate}}
   :flow/finish {:component :flow/finish :outcome :completed}
   :data/bind {:component :data/bind :to :value :value 0.0}
   :session/patch {:component :session/patch :entries []}
   :owner/patch {:component :owner/patch :entries []}
   :txn/atomic {:component :txn/atomic
                :guards [{:component :guard/resource :cost {:cp 1.0}}]
                :reservations [{:component :inventory/consume :source :main-hand :count 1}]
                :body {:component :world/sound :sound-id "generic" :position vec3-example}
                :on-success {:component :flow/finish :outcome :committed}
                :on-fail {:component :flow/finish :outcome :insufficient}}
   :guard/resource {:component :guard/resource :cost {:cp 1.0}}
   :guard/value-in {:component :guard/value-in
                    :value {:ref [:slot :held-item :item-id]}
                    :one-of ["minecraft:stick"]}
   :target/raycast {:component :target/raycast :origin vec3-example
                    :direction {:vec3 [0.0 0.0 1.0]} :distance 1.0
                    :result :hit}
   :target/resolve-destination {:component :target/resolve-destination
                                :hit {:ref [:slot :hit]}
                                :origin vec3-example
                                :direction {:vec3 [0.0 0.0 1.0]}
                                :distance 1.0
                                :policy {:minimum-distance 1.0
                                         :entity-eye-height 1.6
                                         :head-clearance? true}
                                :result :destination}
   :target/directional-destination-query
   {:component :target/directional-destination-query
    :origin vec3-example :look {:vec3 [0.0 0.0 1.0]} :eye-y 1.62
    :direction :forward :distance 16.0 :policy {} :result :destination}
   :target/entities {:component :target/entities
                     :shape {:type :sphere :center vec3-example :radius 1.0}
                     :projection [:id :type :position] :limit 1 :result :entities}
   :target/entity-snapshot {:component :target/entity-snapshot
                            :entity-id "entity-id"
                            :projection [:id :type :eye-position :alive?]
                            :result :entity-snapshot}
   :owner/snapshot {:component :owner/snapshot
                    :projection [:position :velocity :can-fly?]
                    :result :owner-snapshot}
   :target/item-held {:component :target/item-held
                      :source :main-hand :result :held-item}
   :energy/target {:component :energy/target
                   :hit {:ref [:slot :aim-hit]} :result :energy-target}
   :target/blocks {:component :target/blocks
                   :shape {:type :cylinder :start vec3-example
                           :direction {:vec3 [0.0 0.0 1.0]}
                           :radius 1.0 :length 1.0 :step 1.0}
                   :projection [:position :hardness] :limit 1 :result :blocks}
   :terrain/propagate {:component :terrain/propagate
                       :origin vec3-example
                       :direction {:vec3 [0.0 0.0 1.0]}
                       :max-iterations 4 :initial-energy 2.0
                       :entity-search-radius 2.0
                       :spread {:angle-radians 90.0
                                :entries [[0.0 1.0] [1.0 0.7] [-1.0 0.7]]}
                       :energy-cost {:default 0.5}
                       :block-transforms {"minecraft:stone" {:to "minecraft:cobblestone"}}
                       :result :terrain-plan}
   :host/beam-trace {:component :host/beam-trace :origin vec3-example
                     :trace-origin vec3-example
                     :direction {:vec3 [0.0 0.0 1.0]} :length 1.0 :radius 0.1
                     :damage 1.0 :entity-limit 1 :result :beam}
   :combat/damage {:component :combat/damage :target {:ref [:slot :target-id]} :amount 1.0}
   :energy/charge {:component :energy/charge :mode :block
                   :target {:ref [:slot :energy-target]} :amount 1.0}
   :damage/reflect {:component :damage/reflect
                    :multiplier 1.0 :cost-per-damage 1.0
                    :minimum 1.0 :max-depth 6}
   :damage/absorb {:component :damage/absorb
                   :cap 10.0 :interval-ticks 18
                   :last-tick-path [:last-absorb-tick]
                   :front? true :cost {:cp 1.0 :overload 1.0}}
   :combat/impulse {:component :combat/impulse :target {:ref [:slot :target-id]}
                    :vector vec3-example}
   :entity/radial-impulse {:component :entity/radial-impulse
                           :center vec3-example :radius 1.0
                           :speed-min 0.5 :speed-max 1.0 :seed 0}
   :motion/flight {:component :motion/flight :direction nil :speed 1.0
                   :acceleration 0.16 :hover-near-ground-velocity 0.1
                   :hover-air-velocity 0.078 :near-ground-distance 0.8
                   :near-ground-eye-height 0.5}
   :motion/velocity {:component :motion/velocity :velocity vec3-example
                     :dismount? true :reset-fall-damage? true}
   :motion/entity-velocity {:component :motion/entity-velocity
                            :target {:ref [:slot :entity-id]}
                            :velocity vec3-example}
   :motion/entity-velocity-add {:component :motion/entity-velocity-add
                                :target {:ref [:slot :entity-id]}
                                :velocity vec3-example}
   :owner/can-fly {:component :owner/can-fly :enabled? true}
   :combat/status {:component :combat/status :target {:ref [:slot :target-id]}
                   :status-id :generic :duration-ticks 1 :amplifier 0}
   :entity/teleport {:component :entity/teleport
                     :target {:ref [:context :owner]}
                     :position vec3-example
                     :dismount? true
                     :reset-fall-damage? true}
   :entity/reset-fall-damage {:component :entity/reset-fall-damage
                              :target {:ref [:context :owner]}}
   :interaction/dispatch {:component :interaction/dispatch :kind :generic
                           :target {:ref [:slot :target-id]} :payload {} :result :interaction}
   :inventory/consume {:component :inventory/consume :source :main-hand :count 1}
   :entity/spawn {:component :entity/spawn :entity-type "generic"
                  :owner {:ref [:context :owner]} :position vec3-example
                  :velocity vec3-example :result :spawn}
   :entity/discard {:component :entity/discard :entity {:ref [:slot :entity-id]}}
   :entity/configure {:component :entity/configure
                      :entity {:ref [:slot :entity-id]}
                      :block-id "minecraft:iron_block"
                      :place-when-collide? true}
   :data/random-item {:component :data/random-item
                      :items {:ref [:slot :candidates]} :result :candidate}
   :projectile/schedule-beam {:component :projectile/schedule-beam
                              :origin {:ref [:slot :origin]}
                              :destination {:ref [:slot :destination]}
                              :damage 1.0 :damage-type :generic :delay-ticks 1}
   :block/break-budget {:component :block/break-budget :blocks {:ref [:slot :blocks]}
                        :energy 1.0 :order :beam-forward :limit 1}
   :block/break {:component :block/break
                 :position {:ref [:slot :block-position]}
                 :expected-block-id "minecraft:iron_block"
                 :drop? false}
   :block/set {:component :block/set
               :position {:ref [:slot :block-position]}
               :block-id "minecraft:cobblestone"}
   :block/random-break {:component :block/random-break :origin vec3-example
                        :attempts 1 :radius 1.0 :hardness-max 0.3 :seed 0}
   :block/area-break {:component :block/area-break :origin vec3-example
                      :radius 1.0 :hardness-max 0.3
                      :break-probability 1.0 :drop-probability 1.0 :seed 0}
   :world/sound {:component :world/sound :sound-id "generic" :position vec3-example
                 :category :ambient :volume 1.0 :pitch 1.0}
   :world/explosion {:component :world/explosion :position vec3-example
                     :radius 4.0 :fire? false :terrain? true}
   :projectile/redirect {:component :projectile/redirect
                         :entity {:ref [:slot :projectile]}
                         :target-position {:ref [:slot :aim-point]}
                         :velocity {:ref [:slot :velocity]}
                         :difficulty 1.0}
   :resource/enforce-floor {:component :resource/enforce-floor
                            :resource :overload
                            :minimum 1.0}
   :resource/add {:component :resource/add :resource :overload :amount 1.0}
   :effect/vfx {:component :effect/vfx :effect-id :generic :operation :spawn
                :payload {:position vec3-example}}
   :domain/event {:component :domain/event :event-type :generic :payload {}}})

(def expression-examples
  {:math/select {:expr :math/select :args [true 1.0 0.0]}
   :value/eq {:expr :value/eq :args ["minecraft:creeper" "minecraft:creeper"]}
   :collection/contains? {:expr :collection/contains?
                          :args [["minecraft:iron_block"] "minecraft:iron_block"]}
   :collection/concat {:expr :collection/concat :args [[1] [2]]}
   :value/normalize-id {:expr :value/normalize-id :args ["block.minecraft.iron_block"]}
   :value/parse-status-spec {:expr :value/parse-status-spec :args ["jump-boost:1"]}
   :value/status-id {:expr :value/status-id :args ["jump-boost:1"]}
   :value/status-max-amplifier {:expr :value/status-max-amplifier :args ["jump-boost:1"]}
   :vec3/with-z {:expr :vec3/with-z :args [vec3-example 0.0]}
   :vec3/approach {:expr :vec3/approach :args [vec3-example vec3-example 0.1]}
   :vec3/launch {:expr :vec3/launch :args [vec3-example 2.0 -0.174533]}
   :collection/first {:expr :collection/first :args [[{:id "x"}]]}
   :collection/nonempty {:expr :collection/nonempty :args [[]]}})

;; Examples for EDN composite middle-layer components.  These are intentionally
;; parameterized with neutral inputs; concrete skill EDN supplies tunables and
;; policies at the call site.
(def combat-composite-examples
  {:target/raycast-destination
   {:component :target/raycast-destination
    :origin {:ref [:context :eye]}
    :direction {:ref [:context :look]}
    :distance 32.0
    :policy {:direction {:ref [:context :look]}
             :minimum-distance 1.0
             :entity-eye-height 1.6
             :head-clearance? true}}
   :target/directional-destination
   {:component :target/directional-destination
    :origin {:ref [:context :body-pos]}
    :look {:ref [:context :look]}
    :eye-y {:ref [:context :eye-y]}
    :direction :forward :distance 16.0 :policy {}}
   :target/hold-destination
   {:component :target/hold-destination
    :origin {:ref [:context :eye]}
    :direction {:ref [:context :look]}
    :hold-ticks {:ref [:session :hold-ticks]}
    :range-per-hold-tick 2.0
    :maximum-range 64.0
    :available-resource {:ref [:context :resources :energy]}
    :resource-per-distance 4.0
             :policy {:direction {:ref [:context :look]}
             :minimum-distance 1.0
             :entity-eye-height 1.6
             :head-clearance? true}}
   :target/penetration-destination
   {:component :target/penetration-destination
    :origin {:ref [:context :eye]}
    :direction {:ref [:context :look]}
    :distance 32.0
    :scan-step 0.8
    :clearance-steps 4
    :available-resource {:ref [:context :resources :energy]}
    :resource-per-distance 4.0
    :marker-offset-y 1.6}})

(def ir-node-examples
  [{:ir/op :const-double :dst 0 :value 1.0}
   {:ir/op :const-long :dst 0 :value 1}
   {:ir/op :const-boolean :dst 0 :value true}
   {:ir/op :const-object :dst 0 :const-index 0}
   {:ir/op :load-param :type :double :dst 1 :param-index 0}
   {:ir/op :load-context :type :object :dst 1 :context-index 0}
   {:ir/op :move :type :double :dst 2 :src 1}
   {:ir/op :expr :opcode :math/add :dst 3 :args [0 1]}
   {:ir/op :query :capability-id 0 :request-index 0 :result-slot 1}
   {:ir/op :jump :target 4}
   {:ir/op :jump-if-false :condition-slot 0 :target 4}
   {:ir/op :iter-open :batch-slot 1 :iterator-slot 2 :limit 64}
   {:ir/op :iter-next :iterator-slot 2 :item-slot 3 :end-target 8}
   {:ir/op :latch-claim :key-slot 3 :storage-index 0 :result-slot 0}
   {:ir/op :session-patch :patch-index 0}
   {:ir/op :owner-patch :patch-index 0}
   {:ir/op :txn-begin :transaction-index 0}
   {:ir/op :txn-guard :guard-index 0 :fail-target 8}
   {:ir/op :txn-reserve :reservation-index 0}
   {:ir/op :txn-commit :result-slot 1 :fail-target 8}
   {:ir/op :emit-action :action-index 0}
   {:ir/op :emit-vfx :signal-index 0}
   {:ir/op :emit-event :event-index 0}
   {:ir/op :finish :outcome-index 0 :finish-session? true}
   {:ir/op :reject :reason-index 0}])
