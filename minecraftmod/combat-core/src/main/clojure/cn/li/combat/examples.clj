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
   :guard/held-item {:component :guard/held-item :source :main-hand
                     :item-ids ["minecraft:stick"]}
   :target/raycast {:component :target/raycast :origin vec3-example
                    :direction {:vec3 [0.0 0.0 1.0]} :distance 1.0
                    :result :hit}
   :target/entities {:component :target/entities
                     :shape {:type :sphere :center vec3-example :radius 1.0}
                     :projection [:id :type :position] :limit 1 :result :entities}
   :target/blocks {:component :target/blocks
                   :shape {:type :cylinder :start vec3-example
                           :direction {:vec3 [0.0 0.0 1.0]}
                           :radius 1.0 :length 1.0 :step 1.0}
                   :projection [:position :hardness] :limit 1 :result :blocks}
   :combat/beam {:component :combat/beam :origin vec3-example
                 :direction {:vec3 [0.0 0.0 1.0]} :length 1.0 :radius 0.1
                 :entity-limit 1 :result :beam}
   :combat/damage {:component :combat/damage :target {:ref [:slot :target-id]} :amount 1.0}
   :combat/damage-impact {:component :combat/damage-impact
                          :target {:ref [:slot :target-id]}
                          :amount 1.0
                          :damage-type :generic
                          :impact-context {:ref [:slot :impact]}
                          :on-impact finish-example}
   :combat/impulse {:component :combat/impulse :target {:ref [:slot :target-id]}
                    :vector vec3-example}
   :combat/status {:component :combat/status :target {:ref [:slot :target-id]}
                   :status-id :generic :duration-ticks 1 :amplifier 0}
   :interaction/dispatch {:component :interaction/dispatch :kind :generic
                           :target {:ref [:slot :target-id]} :payload {} :result :interaction}
   :inventory/consume {:component :inventory/consume :source :main-hand :count 1}
   :entity/spawn {:component :entity/spawn :entity-type "generic"
                  :owner {:ref [:context :owner]} :position vec3-example
                  :velocity vec3-example :result :spawn}
   :entity/discard {:component :entity/discard :entity {:ref [:slot :entity-id]}}
   :block/break-budget {:component :block/break-budget :blocks {:ref [:slot :blocks]}
                        :energy 1.0 :order :beam-forward :limit 1}
   :world/sound {:component :world/sound :sound-id "generic" :position vec3-example
                 :category :ambient :volume 1.0 :pitch 1.0}
   :effect/vfx {:component :effect/vfx :effect-id :generic :operation :spawn
                :payload {:position vec3-example}}
   :domain/event {:component :domain/event :event-type :generic :payload {}}})

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
