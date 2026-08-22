(ns cn.li.ac.ability.service.combat-runtime-damage-reaction-single-path-test
  "Regression coverage for a real gap P4's final verification pass found:
   the single-execution-path mandate (see [[feedback-single-combat-
   execution-path]]) was fully closed for primary ability casts (P1b), but
   reflected/reaction damage -- the :type :damage world-effect
   combat-core/reactions.clj's apply! produces when a marked entity takes a
   hit and a reflect-style reaction fires -- was still being applied by
   combat_runtime.clj's world-effect-handler* calling AC's own raw
   entity-damage/apply-direct-damage! platform port directly, bypassing
   combat-core's registered :entity/damage capability entirely. Fixed to
   route through the same capability handler every EDN ability's own
   :combat/damage component uses."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.runtime.capabilities :as capabilities]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]))

(deftest reflected-damage-world-effect-routes-through-the-entity-damage-capability-test
  (combat-runtime/reset-for-test!)
  (combat-runtime/initialize!)
  (let [previous (get (:actions (capabilities/snapshot)) :entity/damage)
        seen (atom nil)]
    (try
      (capabilities/register-action!
       :entity/damage
       (fn [request] (reset! seen request) {:status :applied}))
      (let [result (combat-runtime/execute-world-effects!
                    "owner-1"
                    {:world-effects
                     [{:type :damage
                       :request {:world-id "world" :target "target-1"
                                 :base 6.0 :type :skill :source "owner-1"}}]})]
        (is (= :applied (get-in result [:effect-results 0 :status]))
            "the effect result must reflect the capability handler's own status")
        (is (some? @seen)
            "the registered :entity/damage capability handler, not a raw platform port call, must receive the reflected hit")
        (is (= {:world-id "world" :target "target-1" :amount 6.0
                :damage-type :skill :owner "owner-1"}
               @seen)))
      (finally
        (when previous
          (capabilities/register-action! :entity/damage previous))))))
