(ns cn.li.ac.ability.server.damage.handler-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.server.damage.handler :as h]))

(defn- reset-registries! [f]
  (reset! @#'cn.li.ac.ability.server.damage.handler/attack-cancel-checks {})
  (reset! @#'cn.li.ac.ability.server.damage.handler/attack-precheck-side-effects {})
  (f)
  (reset! @#'cn.li.ac.ability.server.damage.handler/attack-cancel-checks {})
  (reset! @#'cn.li.ac.ability.server.damage.handler/attack-precheck-side-effects {}))

(use-fixtures :each reset-registries!)

(deftest attack-cancel-check-any-true-test
  (h/register-attack-cancel-check! :false-1 (fn [_ _ _] false))
  (h/register-attack-cancel-check! :true-1 (fn [_ _ _] true))
  (is (true? (h/should-cancel-attack? "p" "a" 5.0 :src))))

(deftest attack-cancel-check-exception-isolated-test
  (h/register-attack-cancel-check! :boom (fn [_ _ _] (throw (Exception. "boom"))))
  (h/register-attack-cancel-check! :true-1 (fn [_ _ _] true))
  (is (true? (h/should-cancel-attack? "p" "a" 5.0 :src))))

(deftest run-attack-precheck-side-effects-success-test
  (let [calls (atom [])]
    (h/register-attack-precheck-side-effect!
      :fx
      (fn [player-id attacker-id damage damage-source]
        (swap! calls conj [player-id attacker-id damage damage-source])
        :ok))
    (is (true? (h/run-attack-precheck-side-effects! "p" "a" 8.0 :magic)))
    (is (= [["p" "a" 8.0 :magic]] @calls))))

(deftest run-attack-precheck-side-effects-exception-isolated-test
  (let [calls (atom 0)]
    (h/register-attack-precheck-side-effect!
      :boom
      (fn [_ _ _ _] (throw (Exception. "boom"))))
    (h/register-attack-precheck-side-effect!
      :ok
      (fn [_ _ _ _] (swap! calls inc) true))
    (is (true? (h/run-attack-precheck-side-effects! "p" "a" 8.0 :magic)))
    (is (= 1 @calls))))
