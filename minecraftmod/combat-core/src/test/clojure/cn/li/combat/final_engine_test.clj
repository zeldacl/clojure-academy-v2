(ns cn.li.combat.final-engine-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.combat.final-compiler :as compiler]
            [cn.li.combat.final-engine :as engine]
            [cn.li.combat.platform :as platform]
            [cn.li.mcmod.runtime.host :as host]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.teleportation :as teleportation]))
(deftest compiler-lowers-query-after-mutation-to-barrier-test
  (is (map? (compiler/compile-program
             {:component :flow/sequence
              :steps [{:component :combat/damage :args {:amount 1}}
                      {:component :target/raycast :bind :hit}]}))))
(deftest flow-finish-next-phase-is-propagated-test
  (let [runtime (engine/create-engine {:host (host/create {:queries {} :actions {}})
                                       :state-provider (fn [_] {})
                                       :commit-state! (fn [_])})
        result (engine/execute! runtime (compiler/compile-program
                                 {:component :flow/finish :outcome :anti-afk :next-phase :release})
                                {:owner :alice :world "world:test" :ability-id :skill/a
                                 :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= :release (:next-phase result)))))
(deftest engine-preflight-before-state-commit-test
  (let [commits (atom []) host (host/create {:queries {} :actions {:combat/damage (fn [_ _ _] true)}}) engine (engine/create-engine {:host host :state-provider (fn [_] {:resources {:mana 3}}) :commit-state! #(swap! commits conj %)}) compiled (compiler/compile-program {:component :flow/sequence :steps [{:component :resource/try-spend :resource :mana :amount 1 :bind :ok} {:component :combat/damage :args {:amount 2}}]}) result (engine/execute! engine compiled {:owner :alice :world "w" :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= 1 (count @commits)))
    (is (= 2.0 (get-in result [:txn 0 :state :resources :mana])))))

(deftest final-expression-session-and-vfx-contract-test
  (let [sessions (atom {:state {:charge 2}})
        commits (atom [])
        engine (engine/create-engine
                {:host (host/create {:queries {} :actions {}})
                 :state-provider (fn [_] {:resources {:mana 5}})
                 :commit-state! (fn [_])
                 :session-provider (fn [_] @sessions)
                 :commit-session! (fn [_ patches] (swap! commits conj patches))})
        program (compiler/compile-program
                 {:component :flow/sequence
                  :steps [{:component :session/read :key :charge :bind {:value :charge}}
                          {:component :data/bind :to :next
                           :value {:expr :math/add :args [{:ref [:local :charge]} 1]}}
                          {:component :session/write :key :charge :value {:ref [:local :next]}}
                          {:component :cost/spend :budget {:resources {:mana 2}}
                           :bind {:insufficient? :insufficient?}}
                          {:component :effect/vfx :effect-id :ring :operation :spawn
                           :payload {:radius {:ref [:local :next]}}}]})
        result (engine/execute! engine program
                                {:owner :alice :world "w" :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= [{:path [:charge] :mode :assign :value 3.0}] (first @commits)))
    (is (= 1 (count (:vfx-signals result))))
    (is (= :spawn (get-in result [:vfx-signals 0 :op])))
    (is (= 3.0 (get-in result [:vfx-signals 0 :params :radius])))))

(deftest vfx-identity-is-generated-and-ordered-within-one-tick-test
  (let [engine (engine/create-engine {:host (host/create {:queries {} :actions {}})
                                      :state-provider (fn [_] {})
                                      :commit-state! (fn [_])})
        program (compiler/compile-program
                 {:component :flow/sequence
                  :steps [{:component :effect/vfx :effect-id :audio-one-shot
                           :operation :spawn :instance-key [:same]
                           :payload {:volume 1.0}}
                          {:component :effect/vfx :effect-id :audio-one-shot
                           :operation :update :instance-key [:same]
                           :payload {:volume 0.5}}]})
        result (engine/execute! engine program
                                {:owner :alice :ability-id :skill/a :world "w"
                                 :tick 7 :seed 9 :input {}})
        [spawn update] (:vfx-signals result)]
    (is (= :accepted (:status result)))
    (is (= [:same] (:instance-key spawn)))
    (is (= (:instance-key spawn) (:instance-key update)))
    (is (< (:event-seq spawn) (:event-seq update)))
    (is (= 7000001 (:event-seq spawn)))
    (is (= 7000002 (:event-seq update)))))

(deftest cooldown-state-is-keyed-by-ability-and-preserves-frame-test
  (let [commits (atom [])
        engine (engine/create-engine
                {:host (host/create {:queries {} :actions {}})
                 :state-provider (fn [_] {:cooldowns {}})
                 :commit-state! #(reset! commits %)})
        program (compiler/compile-program
                 {:component :cooldown/start :name :main :cooldown {:ticks 12}})
        result (engine/execute! engine program
                                {:owner :alice :ability-id :skill/a
                                 :world "w" :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= {:ticks 12 :max 12}
           (get-in result [:txn 0 :state :cooldowns [:skill/a :main]])))
    (is (= :skill/a (get-in @commits [0 :frame :ability-id])))))

(deftest query-alias-injects-owner-and-world-context-test
  (let [seen (atom nil)
        engine (engine/create-engine
                {:host (host/create
                        {:queries {:raycast (fn [request frame]
                                              (reset! seen [request frame])
                                              {:hit-type :miss})}
                         :actions {}})
                 :state-provider (fn [_] {})
                 :commit-state! (fn [_])})
        program (compiler/compile-program
                 {:component :target/raycast
                  :origin [0.0 0.0 0.0]
                  :direction [0.0 0.0 1.0]
                  :distance 4.0
                  :result :hit})
        result (engine/execute! engine program
                                {:owner :alice :world "world:test"
                                 :ability-id :skill/a :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= :miss (get-in result [:locals :hit :hit-type])))
    (is (= :alice (get-in @seen [0 :owner])))
    (is (= "world:test" (get-in @seen [0 :world-id])))
    (is (= :raycast (get-in @seen [0 :query-kind])))
    (is (= :skill/a (get-in @seen [1 :frame :ability-id])))))

(deftest terrain-propagate-is-a-query-and-binds-plan-test
  (let [seen (atom 0)
        engine (engine/create-engine
                {:host (host/create
                        {:queries {:terrain/propagate (fn [_ _]
                                                         (swap! seen inc)
                                                         {:transforms [{:position [0 64 0]}]})}
                         :actions {}})
                 :state-provider (fn [_] {})
                 :commit-state! (fn [_])})
        result (engine/execute! engine
                                (compiler/compile-program
                                 {:component :terrain/propagate
                                  :origin [0.0 64.0 0.0]
                                  :direction [0.0 0.0 1.0]
                                  :result :plan})
                                {:owner :alice :world "world:test"
                                 :ability-id :skill/a :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= 1 @seen))
    (is (= [{:position [0 64 0]}] (get-in result [:locals :plan :transforms])))
    (is (empty? (get-in result [:host :applied-ids])))))

(deftest foreach-binds-value-and-index-locals-test
  (let [engine (engine/create-engine {:host (host/create {:queries {} :actions {}})
                                      :state-provider (fn [_] {})
                                      :commit-state! (fn [_])})
        result (engine/execute! engine
                                (compiler/compile-program
                                 {:component :flow/foreach :items [10 20]
                                  :as :item :index-as :i
                                  :body {:component :graph/output
                                         :value {:item {:ref [:local :item]}
                                                 :index {:ref [:local :i]}}}})
                                {:owner :alice :world "world:test"
                                 :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= [{:item 10 :index 0} {:item 20 :index 1}]
           (:feedback result)))))

(deftest once-uses-keyed-session-storage-and-on-first-branch-test
  (let [commits (atom [])
        engine (engine/create-engine {:host (host/create {:queries {} :actions {}})
                                      :state-provider (fn [_] {})
                                      :commit-state! (fn [_])
                                      :session-provider (fn [_] {:state {}})
                                      :commit-session! (fn [_ patches] (reset! commits patches))})
        once (fn [key]
               {:component :flow/once :key key :scope :owner
                :strategy :last-key :storage-path [:visited]
                :on-first {:component :graph/output :value key}})
        result (engine/execute! engine
                                (compiler/compile-program
                                 {:component :flow/sequence
                                  :steps [(once :projectile/a)
                                          (once :projectile/a)
                                          (once :projectile/b)]})
                                {:owner :alice :world "world:test"
                                 :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= [:projectile/a :projectile/b] (:feedback result)))
    (is (= [{:path [:visited] :mode :assign :value :projectile/a}
            {:path [:visited] :mode :assign :value :projectile/a}
            {:path [:visited] :mode :assign :value :projectile/b}]
           @commits))))

(deftest action-return-observations-reach-outbox-test
  (let [engine (engine/create-engine
                {:host (host/create
                        {:queries {}
                         :actions {:entity/mark
                                   (fn [phase _ _]
                                     (if (= :preflight phase)
                                       true
                                       {:status :applied
                                        :vfx-signals [{:op :spawn :effect-id :mark-sparks}]}))}})
                 :state-provider (fn [_] {})
                 :commit-state! (fn [_])})
        result (engine/execute! engine
                                (compiler/compile-program
                                 {:component :entity/mark
                                  :target "mob" :mark-type :test
                                  :duration-ticks 5})
                                {:owner :alice :world "world:test"
                                 :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= :mark-sparks (get-in result [:vfx-signals 0 :effect-id])))
    (is (= :entity/mark (get-in result [:host :results 0 :capability])))))

(deftest domain-event-is-emitted-as-an-event-outbox-entry-test
  (let [engine (engine/create-engine
                {:host (host/create {:queries {} :actions {}})
                 :state-provider (fn [_] {})
                 :commit-state! (fn [_])})
        program (compiler/compile-program
                 {:component :domain/event
                  :event-type :achievement/trigger
                  :payload {:id "academy.test"}})
        result (engine/execute! engine program
                                {:owner :alice :world "world:test"
                                 :ability-id :skill/a :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= {:type :achievement/trigger :owner :alice :ability-id :skill/a
            :payload {:id "academy.test"}}
           (first (:events result))))))

(deftest combat-action-capability-aliases-and-charged-area-port-test
  (let [seen (atom [])
        handler (fn [phase command _context]
                  (when (= :apply phase)
                    (swap! seen conj (:capability command)))
                  true)
        engine (engine/create-engine
                {:host (host/create {:queries {}
                                     :actions {:entity/status handler
                                               :combat/charged-area-damage handler}})
                 :state-provider (fn [_] {})
                 :commit-state! (fn [_])})
        program (compiler/compile-program
                 {:component :flow/sequence
                  :steps [{:component :combat/status :target "mob"
                           :status-id :glowing :duration-ticks 5}
                          {:component :combat/charged-area-damage
                           :center [0.0 0.0 0.0] :radius 3.0 :damage 2.0}]})
        result (engine/execute! engine program
                                {:owner :alice :world "world:test"
                                 :ability-id :skill/a :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= [:entity/status :combat/charged-area-damage] @seen))))

(deftest charged-area-platform-port-uses-action-abi-test
  (is (= :failed (:status (platform/charged-area-damage! {})))))

(deftest teleport-entity-honors-neutral-safety-flags-test
  (let [calls (atom [])]
    (with-redefs [world-effects/available? (constantly true)
                  player-motion/available? (constantly true)
                  player-motion/dismount-riding! (fn [owner]
                                                   (swap! calls conj [:dismount owner])
                                                   true)
                  world-effects/teleport-entity! (fn [world owner x y z]
                                                   (swap! calls conj [:teleport world owner x y z])
                                                   true)
                  teleportation/available? (constantly true)
                  teleportation/reset-fall-damage! (fn [owner]
                                                     (swap! calls conj [:reset-fall owner])
                                                     true)]
      (is (= :applied (:status (platform/teleport-entity!
                                {:world-id "world:test"
                                 :target "player-1"
                                 :position [1.0 2.0 3.0]
                                 :dismount? true
                                 :reset-fall-damage? true}))))
      (is (= [[:dismount "player-1"]
              [:teleport "world:test" "player-1" 1.0 2.0 3.0]
              [:reset-fall "player-1"]]
             @calls)))))
(deftest capability-matrix-resolves-to-host-or-ac-port-test
  (let [{:keys [queries actions]} (engine/capability-matrix)
        query-capabilities (set (keys (platform/query-handlers)))
        action-capabilities (set (keys (platform/action-handlers)))
        ac-owned #{:energy/target}]
    (is (every? #(contains? action-capabilities %) (set (vals actions))))
    (is (every? #(or (contains? query-capabilities %)
                     (contains? ac-owned %))
                (set (vals queries))))))

(deftest cost-spend-honors-scale-partial-and-insufficient-flow-test
  (let [runtime (engine/create-engine {:host (host/create {:queries {} :actions {}})
                                       :state-provider (fn [_] {:resources {:cp 1.0 :overload 1.0}})
                                       :commit-state! (fn [_])})
        program (compiler/compile-program
                 {:component :flow/sequence
                  :steps [{:component :cost/spend
                           :budget {:resources {:cp 4.0 :overload 2.0}}
                           :scale 0.5
                           :partial? true
                           :bind {:insufficient? :insufficient?}}
                          {:component :flow/branch
                           :when {:ref [:local :insufficient?]}
                           :then {:component :flow/finish :outcome :partial}
                           :else {:component :flow/finish :outcome :full}}]})
        result (engine/execute! runtime program
                                {:owner :alice :world "world:test" :ability-id :skill/a
                                 :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= :partial (:outcome result)))
    (is (= 0.0 (get-in result [:txn 0 :state :resources :cp])))
    (is (= 0.0 (get-in result [:txn 0 :state :resources :overload])))))

(deftest cost-spend-runs-on-insufficient-flow-arm-test
  (let [runtime (engine/create-engine {:host (host/create {:queries {} :actions {}})
                                       :state-provider (fn [_] {:resources {:cp 0.0}})
                                       :commit-state! (fn [_])})
        program (compiler/compile-program
                 {:component :cost/spend
                  :budget {:resources {:cp 1.0}}
                  :on-insufficient {:component :flow/finish
                                    :outcome :insufficient
                                    :finish-session? true}})
        result (engine/execute! runtime program
                                {:owner :alice :world "world:test" :ability-id :skill/a
                                 :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= :insufficient (:outcome result)))
    (is (true? (:finish-session? result)))))

(deftest ability-caster-binds-neutral-capability-aliases-test
  (let [runtime (engine/create-engine {:host (host/create {:queries {} :actions {}})
                                       :state-provider (fn [_] {})
                                       :commit-state! (fn [_])})
        program (compiler/compile-program
                 {:component :flow/sequence
                  :steps [{:component :ability/caster
                           :bind {:eye :eye :aim :aim :body :body :id :owner-id
                                  :world-id :wid :charge-ticks :charge-ticks
                                  :mastery :mastery :level :level :seed :seed}}
                          {:component :graph/output
                           :value {:eye {:ref [:local :eye]}
                                   :aim {:ref [:local :aim]}
                                   :body {:ref [:local :body]}
                                   :id {:ref [:local :owner-id]}
                                   :world {:ref [:local :wid]}
                                   :charge {:ref [:local :charge-ticks]}
                                   :mastery {:ref [:local :mastery]}
                                   :level {:ref [:local :level]}
                                   :seed {:ref [:local :seed]}}}]})
        result (engine/execute! runtime program
                                {:owner :alice :world "world:test" :ability-id :skill/a
                                 :tick 1 :seed 9
                                 :input {:capabilities {:caster/eye [1.0 2.0 3.0]
                                                        :caster/aim [0.0 0.0 1.0]
                                                        :caster/body [1.0 1.0 1.0]
                                                        :caster/id :alice
                                                        :caster/creative? false
                                                        :world/id "world:test"
                                                        :charge/ticks 4
                                                        :progression/mastery 0.75
                                                        :progression/level 2
                                                        :rng/seed 99}}})]
    (is (= :accepted (:status result)))
    (is (= [{:eye [1.0 2.0 3.0] :aim [0.0 0.0 1.0]
             :body [1.0 1.0 1.0] :id :alice :world "world:test"
             :charge 4 :mastery 0.75 :level 2 :seed 99}]
           (:feedback result)))))

(deftest finish-stops-following-sequence-siblings-test
  (let [applied (atom [])
        runtime (engine/create-engine
                 {:host (host/create {:queries {}
                                      :actions {:entity/damage
                                                (fn [phase command _]
                                                  (when (= :apply phase)
                                                    (swap! applied conj command))
                                                  true)}})
                  :state-provider (fn [_] {})
                  :commit-state! (fn [_])})
        program (compiler/compile-program
                 {:component :flow/sequence
                  :steps [{:component :flow/finish :outcome :blocked}
                          {:component :entity/damage :target "mob" :amount 99}]})
        result (engine/execute! runtime program
                                {:owner :alice :ability-id :skill/a
                                 :world "world:test" :tick 1 :seed 1 :input {}})]
    (is (= :blocked (:outcome result)))
    (is (empty? @applied))))

(deftest foreach-skip-item-control-does-not-run-rest-of-item-test
  (let [applied (atom [])
        runtime (engine/create-engine
                 {:host (host/create {:queries {}
                                      :actions {:entity/damage
                                                (fn [phase command _]
                                                  (when (= :apply phase)
                                                    (swap! applied conj command))
                                                  true)}})
                  :state-provider (fn [_] {})
                  :commit-state! (fn [_])})
        program (compiler/compile-program
                 {:component :flow/foreach :items [0 1] :as :item
                  :body {:component :flow/sequence
                         :steps [{:component :flow/branch
                                  :when {:expr :value/eq
                                         :args [{:ref [:local :item]} 0]}
                                  :then {:component :flow/control :signal :skip-item}}
                                 {:component :entity/damage
                                  :target {:ref [:local :item]} :amount 1}]}})
        result (engine/execute! runtime program
                                {:owner :alice :ability-id :skill/a
                                 :world "world:test" :tick 1 :seed 1 :input {}})]
    (is (= :accepted (:status result)))
    (is (= [1] (mapv #(get-in % [:args :target]) @applied)))))