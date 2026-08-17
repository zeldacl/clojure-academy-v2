(ns cn.li.ac.ability.client.presentation-hud-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [cn.li.ac.ability.client.presentation-hud :as hud])
  (:import [cn.li.presentation.core ActionId ActionPayload ActionResult ActionResult$Status]))

(defn- dispatch! [model action-key]
  (.dispatch model (ActionId. (get (set/map-invert hud/action-ids) action-key))
             (ActionPayload/empty)))

(deftest select-skill-cycles-through-available-slots
  (let [observed (atom [])
        {:keys [model snapshot]} (hud/combat-view-model
                                    #uuid "00000000-0000-0000-0000-000000000000"
                                    (fn [action payload] (swap! observed conj [action payload])))]
    (reset! snapshot {:skill-slots [{:skill-id :a} {:skill-id :b} {:skill-id :c}]})
    (testing "each select-skill call advances to the next slot and wraps"
      (is (= (ActionResult/accepted) (dispatch! model :combat/select-skill)))
      (is (= 0 (:selected-skill @snapshot)))
      (dispatch! model :combat/select-skill)
      (is (= 1 (:selected-skill @snapshot)))
      (dispatch! model :combat/select-skill)
      (is (= 2 (:selected-skill @snapshot)))
      (dispatch! model :combat/select-skill)
      (is (= 0 (:selected-skill @snapshot)) "wraps back to the first slot"))
    (testing "the injected dispatch-action! observer sees every dispatched action"
      (is (= 4 (count @observed))))))

(deftest toggle-skill-wheel-flips-a-boolean
  (let [{:keys [model snapshot]} (hud/combat-view-model
                                    #uuid "00000000-0000-0000-0000-000000000000"
                                    (fn [_ _] nil))]
    (dispatch! model :combat/toggle-skill-wheel)
    (is (true? (:skill-wheel-open? @snapshot)))
    (dispatch! model :combat/toggle-skill-wheel)
    (is (false? (:skill-wheel-open? @snapshot)))))

(deftest refresh-preserves-ui-only-state-across-server-snapshot-updates
  (let [{:keys [model snapshot refresh!]} (hud/combat-view-model
                                             #uuid "00000000-0000-0000-0000-000000000000"
                                             (fn [_ _] nil))]
    (reset! snapshot {:skill-slots [{:skill-id :a} {:skill-id :b}]})
    (dispatch! model :combat/select-skill)
    (dispatch! model :combat/toggle-skill-wheel)
    (is (= 0 (:selected-skill @snapshot)))
    (is (true? (:skill-wheel-open? @snapshot)))
    ;; refresh! pulls a fresh server snapshot (reactive-hud/build-snapshot never
    ;; sets :selected-skill/:skill-wheel-open?) — it must not wipe the click state.
    (with-redefs [cn.li.ac.ability.client.reactive-hud/build-snapshot
                  (fn [_ _ _ _] {:skill-slots [{:skill-id :a} {:skill-id :b}]})]
      (refresh! 320 180 {}))
    (is (= 0 (:selected-skill @snapshot)))
    (is (true? (:skill-wheel-open? @snapshot)))))

(deftest dispatch-rejects-unknown-action-id
  (let [{:keys [model]} (hud/combat-view-model
                          #uuid "00000000-0000-0000-0000-000000000000"
                          (fn [_ _] nil))
        result (.dispatch model (ActionId. 99) (ActionPayload/empty))]
    (is (= ActionResult$Status/REJECTED (.status result)))
    (is (= "unknown combat HUD action" (.message result)))))
