(ns cn.li.ac.ability.combat-content-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.combat-content :as content]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.combat.registry :as registry]
            [cn.li.combat.compiler :as compiler]
            [cn.li.combat.runtime :as runtime]))

(deftest vec-deviation-uses-source-toggle-contract
  (registry/reset-for-test!)
  (content/reset-for-test!)
  (content/register! registry/register-provider!)
  (let [ability (get-in (compiler/compile-all!) [:abilities :vec-deviation])]
    ;; These values are the authoritative vec_deviation.clj config contract:
    ;; release-cast + keep-active, activation overload [80,50], scan CP
    ;; [13,5], normal tick CP [5,2.5], normal overload [0.5,0.2].
    (is (= :toggle (:activation ability)))
    (is (= :pulse (:cost-phase ability)))
    (is (= {:overload {:op :scale :min 80.0 :max 50.0}}
           (:activation-cost ability)))
    (is (= {:cp {:op :add :values [{:op :scale :min 13.0 :max 5.0}
                                   {:op :scale :min 5.0 :max 2.5}]}
            :overload {:op :scale :min 0.5 :max 0.2}}
           (:cost ability)))
    (is (= 5.0 (get-in ability [:program :pulse :steps 0 :radius])))))

(deftest vec-deviation-dispatches-pulse-and-toggle-abort
  (registry/reset-for-test!)
  (content/reset-for-test!)
  (content/register! registry/register-provider!)
  (let [catalog (compiler/compile-all!)
        engine (runtime/create-engine
                {:catalog catalog
                 :now-tick (constantly 0)
                 :initial-owner-state
                 (fn [_] {:resources {:cp 100.0 :overload 100.0}})
                 :query-port
                 {:vec-deviation (fn [_ _]
                                   {:center {:x 0.0 :y 64.0 :z 0.0}
                                    :radius 5.0
                                    :entities [{:uuid "victim"}]})}})
        started (runtime/dispatch-intent!
                 engine "owner"
                 {:intent-id 1 :op :start :ability-id :vec-deviation})
        pulsed (first (runtime/tick! engine 1))
        stopped (runtime/dispatch-intent!
                 engine "owner"
                 {:intent-id 2 :op :start :ability-id :vec-deviation})]
    (is (= :accepted (:status started)))
    (is (= [[:resource :overload -80.0]] (:state-patch started)))
    (is (= :vec-deviation (get-in pulsed [:world-effects 0 :type])))
    (is (= [{:uuid "victim"}]
           (get-in pulsed [:world-effects 0 :query-result :entities])))
    (is (= :abort (get-in stopped [:session-ops 0 :op])))
    (is (= :end (get-in stopped [:vfx-signals 0 :event])))))

(deftest light-shield-is-a-combat-core-toggle-recipe
  (registry/reset-for-test!)
  (content/reset-for-test!)
  (content/register! registry/register-provider!)
  (let [ability (get-in (compiler/compile-all!) [:abilities :light-shield])]
    (is (= :toggle (:activation ability)))
    (is (= :light-shield
           (get-in ability [:program :pulse :steps 2 :effect-type])))
    (is (= :light-shield
           (get-in ability [:program :pulse :steps 3 :effect-id])))))

(deftest attack-precheck-uses-one-combat-request
  (let [calls (atom 0)]
    (with-redefs [cn.li.combat.runtime/process-damage-request
                  (fn [_ request]
                    (swap! calls inc)
                    (assoc request :base 3.0))
                  combat-runtime/engine (fn [] {})]
      (is (= false (:cancelled?
                    (combat-runtime/process-attack-precheck!
                     "victim" "attacker" 4.0 {:damage-type :generic}))))
      (is (= 1 @calls)))))

(deftest combat-catalog-covers-all-authoritative-skill-ids
  ;; Every AC skill has one Combat Core recipe.  This is the replacement
  ;; boundary used by the composition root when legacy namespace init hooks
  ;; are removed.
  (let [expected #{:arc-gen :blood-retrograde :body-intensify
                   :current-charging :dim-folding-theorem :directed-blastwave
                   :directed-shock :electron-bomb :electron-missile :flashing
                   :flesh-ripping :groundshock :jet-engine :light-shield
                   :location-teleport :mag-manip :mag-movement :mark-teleport
                   :meltdowner :mine-detect :mine-ray-basic :mine-ray-expert
                   :mine-ray-luck :penetrate-teleport :plasma-cannon
                   :rad-intensify :railgun :ray-barrage :scatter-bomb
                   :shift-teleport :space-fluct :storm-wing
                   :threatening-teleport :thunder-bolt :thunder-clap
                   :vec-accel :vec-deviation :vec-reflection}]
    (is (= expected content/ability-ids))
    (is (= 38 (count content/ability-ids)))))
