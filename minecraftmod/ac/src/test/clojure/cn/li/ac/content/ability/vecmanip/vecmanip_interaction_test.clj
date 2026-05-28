(ns cn.li.ac.content.ability.vecmanip.vecmanip-interaction-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.content.ability.vecmanip.arbitration :as arbitration]))

(defn- with-fresh-arbitration-runtime [f]
  (arbitration/call-with-projectile-arbitration-runtime
    (arbitration/create-projectile-arbitration-runtime)
    (fn []
      (try
        (f)
        (finally
          (arbitration/reset-projectile-locks-for-test!))))))

(use-fixtures :each with-fresh-arbitration-runtime)

(deftest arbitration-priority-defaults-to-reflection-test
  (with-redefs [skill-config/tunable-string-list
                (fn [_skill-id _field-id] [])]
    (is (= :vec-reflection (arbitration/preferred-skill-id)))
    (is (true? (arbitration/skill-allowed-in-dual-active? :vec-reflection)))
    (is (false? (arbitration/skill-allowed-in-dual-active? :vec-deviation)))))

(deftest arbitration-priority-config-switches-to-deviation-test
  (with-redefs [skill-config/tunable-string-list
                (fn [_skill-id _field-id] ["deviation-first"])]
    (is (= :vec-deviation (arbitration/preferred-skill-id)))
    (is (true? (arbitration/skill-allowed-in-dual-active? :vec-deviation)))
    (is (false? (arbitration/skill-allowed-in-dual-active? :vec-reflection)))))

(deftest same-projectile-same-tick-is-claimed-by-one-skill-only-test
  (with-redefs [cn.li.ac.content.ability.vecmanip.arbitration/current-tick (fn [] 42)]
    (is (true? (arbitration/claim-projectile! "p1" :vec-reflection "arrow-1")))
    (is (false? (arbitration/claim-projectile! "p1" :vec-deviation "arrow-1")))
    ;; same skill can re-enter on same tick
    (is (true? (arbitration/claim-projectile! "p1" :vec-reflection "arrow-1")))))

(deftest same-projectile-can-be-reclaimed-on-next-tick-test
  (let [tick* (atom 100)]
    (with-redefs [cn.li.ac.content.ability.vecmanip.arbitration/current-tick (fn [] @tick*)]
      (is (true? (arbitration/claim-projectile! "p1" :vec-reflection "arrow-1")))
      (is (false? (arbitration/claim-projectile! "p1" :vec-deviation "arrow-1")))
      (swap! tick* inc)
      (is (true? (arbitration/claim-projectile! "p1" :vec-deviation "arrow-1"))))))

(deftest projectile-locks-can-clear-one-player-test
  (with-redefs [cn.li.ac.content.ability.vecmanip.arbitration/current-tick (fn [] 50)]
    (arbitration/claim-projectile! "p1" :vec-reflection "arrow-1")
    (arbitration/claim-projectile! "p2" :vec-reflection "arrow-1")
    (arbitration/clear-player-projectile-locks! "p1")
    (is (nil? (get-in (arbitration/projectile-locks-snapshot) [:owners ["p1" "arrow-1"]])))
    (is (= :vec-reflection
           (get-in (arbitration/projectile-locks-snapshot) [:owners ["p2" "arrow-1"]])))))

(deftest projectile-arbitration-runtime-isolation-test
  (let [runtime-a (arbitration/create-projectile-arbitration-runtime)
        runtime-b (arbitration/create-projectile-arbitration-runtime)]
    (with-redefs [cn.li.ac.content.ability.vecmanip.arbitration/current-tick (fn [] 77)]
      (arbitration/call-with-projectile-arbitration-runtime runtime-a
        (fn []
          (is (true? (arbitration/claim-projectile! "p1" :vec-reflection "arrow-1")))))
      (arbitration/call-with-projectile-arbitration-runtime runtime-b
        (fn []
          (is (true? (arbitration/claim-projectile! "p1" :vec-deviation "arrow-1")))
          (is (= :vec-deviation
                 (get-in (arbitration/projectile-locks-snapshot) [:owners ["p1" "arrow-1"]])))))
      (arbitration/call-with-projectile-arbitration-runtime runtime-a
        (fn []
          (is (= :vec-reflection
                 (get-in (arbitration/projectile-locks-snapshot) [:owners ["p1" "arrow-1"]]))))))))

(deftest projectile-arbitration-fallback-works-without-binding-test
  (with-redefs [cn.li.ac.content.ability.vecmanip.arbitration/current-tick (fn [] 123)]
    (binding [arbitration/*projectile-arbitration-runtime* nil]
      (arbitration/reset-projectile-locks-for-test!)
      (is (true? (arbitration/claim-projectile! "p1" :vec-reflection "arrow-fallback")))
      (is (= :vec-reflection
             (get-in (arbitration/projectile-locks-snapshot)
                     [:owners ["p1" "arrow-fallback"]]))))))
