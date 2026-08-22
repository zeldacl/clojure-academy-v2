(ns cn.li.ac.ability.server.damage.runtime-reflection-pipeline-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.adapters.server-hooks :as server-hooks]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.test.support.framework :refer [with-fresh-framework]]
            [cn.li.mcmod.hooks.core :as hooks]))

;; Runtime hooks live in the Framework atom ([:registry :hooks :runtime-hooks]);
;; a fresh framework per test isolates the registrations.
(use-fixtures :each with-fresh-framework)

(deftest runtime-hooks-delegate-precheck-side-effects-test
  (let [calls (atom [])]
    (hooks/register-power-runtime-hooks!
      {:run-attack-precheck-side-effects!
       (fn [player-id attacker-id damage damage-source]
         (swap! calls conj [player-id attacker-id damage damage-source])
         true)})
    (is (true? (hooks/run-attack-precheck-side-effects! "p" "a" 12.0 :magic)))
    (is (= [["p" "a" 12.0 :magic]] @calls))))

(deftest server-hooks-attack-precheck-side-effects-are-inert-test
  ;; Attack prechecks are a pure Combat Core decision now -- there is no
  ;; second callback phase that could observe or mutate the event.
  (let [hooks-map (server-hooks/runtime-server-hooks)]
    (is (false? ((:run-attack-precheck-side-effects! hooks-map)
                 "p" "a" 8.0 :src)))))

(deftest server-hooks-route-attack-cancel-to-combat-core-test
  (let [calls (atom [])]
    (with-redefs [combat-runtime/apply-attack-precheck!
                  (fn [& args]
                    (swap! calls conj args)
                    true)]
      (let [hooks-map (server-hooks/runtime-server-hooks)]
        (is (true? ((:should-cancel-attack-interception? hooks-map)
                    "p" "a" 8.0 :src)))
        (is (= [["p" "a" 8.0 :src]] @calls))))))
