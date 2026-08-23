(ns cn.li.ac.ability.service.v3-ability-content-test
  "Proves the full v3 pipeline works with REAL shipped content, not just
   this session's own test fixtures: :electromaster/brain-course
   (ac/combat/abilities/brain_course.edn) is the first real ability
   marked :engine :v3, loaded through the real manifest and
   combat-catalog/initialize! exactly like every v2 ability, and its
   :program runs through cn.li.combat.skill-runtime/execute!'s v3 branch
   to a real :accepted result -- proving cn.li.combat.recipe/compile-ability's
   engine branch, cn.li.combat.skill-runtime/execute!'s engine branch, and
   the whole AC bootstrap path all compose correctly end to end.

   brain_course.edn's :program is deliberately trivial (every phase is
   just :flow/finish :outcome :passive, unchanged from what a v2 program
   for this ability already looked like -- see NODE_LANGUAGE.md's
   :flow/phases/:flow/finish, node-core builtins shared by both engines)
   -- its real gameplay effect is :passive-effects, a separate mechanism
   cn.li.ac.ability.registry.category/combat-passives already applies
   independently of :program execution. This is intentionally the
   lowest-risk possible first real conversion: proving the pipeline
   itself, not exercising the v3 primitive/composite library (already
   covered thoroughly by cn.li.combat.skill-runtime-v3-engine-test and
   friends with synthetic content)."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.runtime.capabilities :as capabilities]
            [cn.li.ac.ability.service.combat-catalog :as catalog]
            [cn.li.combat.skill-runtime :as skill-runtime]))

(defn- with-fake-raycast-handler [hit f]
  (let [previous (get (:queries (capabilities/snapshot)) :raycast)]
    (try
      (capabilities/register-query! :raycast (fn [_request _frame] hit))
      (f)
      (finally
        (when previous
          (capabilities/register-query! :raycast previous))))))

(deftest brain-course-compiles-with-engine-v3-and-is-available-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :electromaster/brain-course])]
    (is (nil? (get-in state [:combat :errors :electromaster/brain-course])))
    (is (= :v3 (:engine ability)))
    (is (map? (:compiled-program ability)))
    (is (catalog/available? :electromaster/brain-course))))

(deftest brain-course-v3-program-executes-to-accepted-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :electromaster/brain-course "owner-1" {:action :start})]
    (is (= :accepted (:status result)))
    (is (empty? (:actions result)))
    (is (empty? (:vfx-signals result)))))

;; --- :mine-detect: a substantial real conversion (cost/spend + branch,
;; combat/status, effect/vfx, score/mark, cooldown/start, source nodes) ---

(deftest mine-detect-compiles-with-engine-v3-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :mine-detect])]
    (is (nil? (get-in state [:combat :errors :mine-detect])))
    (is (= :v3 (:engine ability)))
    (is (catalog/available? :mine-detect))))

(deftest mine-detect-v3-program-spends-budget-and-applies-effects-when-affordable-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :mine-detect "owner-1"
                {:action :start
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                        :world/id "overworld" :progression/mastery 0.9 :progression/level 5}
                 :tunables {:blindness-duration-ticks 40 :blindness-amplifier 1 :targeting-range 20.0
                            :cost-down-cp 5.0 :cost-down-overload 0.0 :cooldown-ticks 200 :exp-cast 0.02}
                 :context {:resources {:cp 10.0}}})]
    (is (= :accepted (:status result)))
    (is (some #(and (= :entity/status (:capability %)) (= "owner-1" (:target %))
                    (= :blindness (:status-id %)) (= 1 (:amplifier %)))
              (:actions result)))
    (is (some #(= :owner-patch (:type %)) (:actions result))
        "cost/spend + score/mark + cooldown/start all emit owner-patch actions")
    (is (= 1 (count (:vfx-signals result))))
    (let [signal (first (:vfx-signals result))]
      (is (= :block-scan-transient (:effect-id signal)))
      (is (true? (get-in signal [:params :advanced?])) "mastery 0.9 > 0.5 and level 5 >= 4")
      (is (= 20.0 (get-in signal [:params :range]))))))

;; --- three more all-passive :engine :v3 conversions (rad-intensify,
;; dim-folding-theorem, space-fluct): same trivial :flow/phases/:flow/finish
;; :passive shape as brain-course, their real gameplay is :reactions /
;; :mark-policies, mechanisms independent of :program execution ---

(deftest rad-intensify-v3-program-executes-to-accepted-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :rad-intensify])
        result (skill-runtime/execute!
                state :rad-intensify "owner-1" {:action :start})]
    (is (nil? (get-in state [:combat :errors :rad-intensify])))
    (is (= :v3 (:engine ability)))
    (is (= :accepted (:status result)))))

(deftest dim-folding-theorem-v3-program-executes-to-accepted-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :dim-folding-theorem])
        result (skill-runtime/execute!
                state :dim-folding-theorem "owner-1" {:action :start})]
    (is (nil? (get-in state [:combat :errors :dim-folding-theorem])))
    (is (= :v3 (:engine ability)))
    (is (= :accepted (:status result)))))

(deftest space-fluct-v3-program-executes-to-accepted-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :space-fluct])
        result (skill-runtime/execute!
                state :space-fluct "owner-1" {:action :start})]
    (is (nil? (get-in state [:combat :errors :space-fluct])))
    (is (= :v3 (:engine ability)))
    (is (= :accepted (:status result)))))

;; --- :arc-gen: raycast query + entity/block branch + :combat/impact-strike
;; composite (:on-impact callback) + :domain/event ---

(def ^:private arc-gen-fixture
  {:action :start
   :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
          :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld" :progression/mastery 0.5}
   :tunables {:damage 12.0 :max-distance 32.0 :ignite-probability 0.2
              :fishing-probability 0.1 :fishing-exp-threshold 0.5 :creeper-charge-chance 0.0
              :cost-cp 3.0 :cost-overload 0.0 :cooldown-ticks 40 :exp-entity 0.05 :exp-block 0.02}
   :context {:resources {:cp 10.0}}})

(deftest arc-gen-v3-program-strikes-an-entity-when-raycast-hits-one-test
  (with-fake-raycast-handler
    {:entity-id "target-1" :position {:vec3 [0.0 65.6 5.0]} :creeper? false}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute! state :arc-gen "owner-1" arc-gen-fixture)]
        (is (= :accepted (:status result)))
        (is (some #(and (= :entity/damage (:capability %)) (= "target-1" (:target %)) (= 12.0 (:amount %)))
                  (:actions result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)))
        (is (= 1 (count (:vfx-signals result))))
        (is (= :achievement/trigger (:type (first (filter #(= :achievement/trigger (:type %)) (:events result))))))))))

(deftest arc-gen-v3-program-triggers-block-impact-when-raycast-misses-entities-test
  (with-fake-raycast-handler
    {:entity-id nil :position {:vec3 [0.0 65.6 5.0]} :block-position {:vec3 [0 65 5]} :water? false}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute! state :arc-gen "owner-1" arc-gen-fixture)]
        (is (= :accepted (:status result)))
        (is (not (some #(= :entity/damage (:capability %)) (:actions result))))
        (is (some #(= :world/block-impact (:type %)) (:events result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)))))))

(deftest mine-detect-v3-program-rejects-blindness-when-insufficient-resource-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :mine-detect "owner-1"
                {:action :start
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                        :world/id "overworld" :progression/mastery 0.1 :progression/level 1}
                 :tunables {:blindness-duration-ticks 40 :blindness-amplifier 1 :targeting-range 20.0
                            :cost-down-cp 5.0 :cost-down-overload 0.0 :cooldown-ticks 200 :exp-cast 0.02}
                 :context {:resources {:cp 0.0}}})]
    (is (= :accepted (:status result)) "execute! itself is always :accepted for v3, see execute-v3!")
    (is (empty? (:actions result)))
    (is (empty? (:vfx-signals result)))))
