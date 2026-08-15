(ns cn.li.ac.ability.combat-content-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.combat-content :as content]
            [cn.li.combat.registry :as registry]
            [cn.li.combat.compiler :as compiler]))

(deftest vec-deviation-uses-source-toggle-contract
  (registry/reset-for-test!)
  (content/register! registry/register-provider!)
  (let [ability (get (compiler/compile-all!) :abilities :vec-deviation)]
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
