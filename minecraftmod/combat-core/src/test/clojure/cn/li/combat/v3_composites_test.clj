(ns cn.li.combat.v3-composites-test
  "End-to-end proof that combat's composite-expansion pipeline (register ->
   expand -> :flow/sequence/:flow/branch execution -> real primitive
   dispatch) works, using the exact 'release lightning' example
   NODE_LANGUAGE.md section 8 was written around. Mirrors vfx-core's
   :vfx.fx/charge-ring proof for the same milestone on the other domain."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as node]
            [cn.li.node.flow :as node-flow]
            [cn.li.combat.host-primitives :as host-primitives]
            [cn.li.combat.policy-primitives :as policy-primitives]
            [cn.li.combat.structural-primitives :as structural]
            [cn.li.combat.v3-composites :as v3-composites]))

(use-fixtures :each
  (fn [f]
    (node/reset-for-test!)
    (node-flow/install!)
    (host-primitives/install!)
    (policy-primitives/install!)
    (structural/install!)
    (v3-composites/install!)
    (f)
    (node/reset-for-test!)))

(defn- base-ctx [& {:keys [resources dispatch-action! dispatch-query! emit-action! emit-vfx!]}]
  {:locals {} :seed 0 :dispatch structural/dispatch
   :world-id "overworld" :owner "player-1" :activation-seed 7 :ability-id :thunder-clap
   :resources* (atom (or resources {:cp 100.0}))
   :dispatch-action! (or dispatch-action! (fn [_capability _request] nil))
   :dispatch-query! (or dispatch-query! (fn [_capability _request] nil))
   :emit-action! (or emit-action! (fn [_action] nil))
   :emit-vfx! (or emit-vfx! (fn [_signal] nil))
   :emit-event! (fn [_event] nil)})

(deftest lightning-strike-registers-as-mid-composite-test
  (let [d (node/descriptor :fx/lightning-strike)]
    (is (some? d))
    (is (= :mid (:layer d)))
    (is (nil? (:impl d)))))

(deftest lightning-strike-applies-lightning-damage-and-vfx-when-affordable-test
  (let [actions (atom [])
        vfx (atom [])
        ctx (base-ctx :resources {:cp 10.0}
                      :dispatch-action! (fn [capability request] (swap! actions conj [capability request]))
                      :emit-vfx! (fn [signal] (swap! vfx conj signal)))
        call {:component :fx/lightning-strike
              :target "zombie-1" :position {:vec3 [1.0 64.0 1.0]}
              :power 12.0 :budget {:resources {:cp 4.0}}}
        result (node-flow/execute! call ctx)]
    (is (not (:finished? result)) "an affordable strike must not hit the :insufficient-resource finish")
    (is (= 6.0 (:cp @(:resources* ctx))) "the composite's :cost/spend really deducted the budget")
    (is (= #{:world/lightning :entity/damage}
           (set (map first @actions))))
    (let [damage-request (second (first (filter #(= :entity/damage (first %)) @actions)))]
      (is (= "zombie-1" (:target damage-request)))
      (is (= 12.0 (:amount damage-request))))
    (is (= 1 (count @vfx)))
    (is (= :lightning-impact (:effect-id (first @vfx))))))

(deftest lightning-strike-finishes-insufficient-without-side-effects-when-unaffordable-test
  (let [actions (atom [])
        vfx (atom [])
        ctx (base-ctx :resources {:cp 1.0}
                      :dispatch-action! (fn [capability request] (swap! actions conj [capability request]))
                      :emit-vfx! (fn [signal] (swap! vfx conj signal)))
        call {:component :fx/lightning-strike
              :target "zombie-1" :position {:vec3 [0.0 64.0 0.0]}
              :power 12.0 :budget {:resources {:cp 4.0}}}
        result (node-flow/execute! call ctx)]
    (is (:finished? result))
    (is (= :insufficient-resource (:outcome result)))
    (is (= 1.0 (:cp @(:resources* ctx))) "nothing was spent")
    (is (empty? @actions) "no lightning/damage action reached the dispatch pipeline")
    (is (empty? @vfx) "no VFX signal was emitted")))

(deftest area-damage-registers-as-mid-composite-test
  (let [d (node/descriptor :combat/area-damage)]
    (is (some? d))
    (is (= :mid (:layer d)))))

(deftest area-damage-damages-every-entity-the-query-returns-test
  (let [seen-request (atom nil)
        actions (atom [])
        ctx (base-ctx :dispatch-query! (fn [_capability request] (reset! seen-request request)
                                         [{:id "zombie-1"} {:id "zombie-2"} {:id "zombie-3"}])
                      :dispatch-action! (fn [capability request] (swap! actions conj [capability request])))
        call {:component :combat/area-damage :center {:vec3 [0.0 64.0 0.0]} :radius 4.0 :amount 6.0}
        result (node-flow/execute! call ctx)]
    (is (= {:type :sphere :center {:vec3 [0.0 64.0 0.0]} :radius 4.0} (:shape @seen-request))
        "the shape sent to :target/entities reflects the composite's own inputs")
    (is (= 128 (:limit @seen-request)) "the composite's own :limit default was used")
    (is (= 3 (count @actions)))
    (is (= #{"zombie-1" "zombie-2" "zombie-3"} (set (map (comp :target second) @actions))))
    (is (every? #(= 6.0 (:amount (second %))) @actions))
    (is (not (:finished? result)))))

(deftest area-damage-with-no-entities-found-does-nothing-test
  (let [actions (atom [])
        ctx (base-ctx :dispatch-query! (fn [_capability _request] [])
                      :dispatch-action! (fn [capability request] (swap! actions conj [capability request])))]
    (node-flow/execute! {:component :combat/area-damage :center {:vec3 [0.0 0.0 0.0]} :radius 1.0 :amount 1.0} ctx)
    (is (empty? @actions))))
