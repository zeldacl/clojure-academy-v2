(ns cn.li.ac.registry.content-namespaces-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.registry.content-namespaces :as content-ns]
            [cn.li.ac.registry.discovery :as discovery]))

(defn- reset-discovery-fixture [f]
  (discovery/reset-provider-registry-for-test!)
  (try
    (f)
    (finally
      (discovery/reset-provider-registry-for-test!))))

(use-fixtures :each reset-discovery-fixture)

(deftest current-content-load-plan-includes-core-phases-test
  (let [plan (content-ns/current-content-load-plan)
        phases (mapv :phase plan)]
    (is (= #{:block :item :entity :ability :achievement :system}
           (set phases)))
    (let [ability-phase (first (filter #(= :ability (:phase %)) plan))]
      (is (some #(= 'cn.li.ac.content.ability %) (:namespaces ability-phase)))
      (is (some #(= 'cn.li.ac.content.effects/init-effects! %) (:init-fns ability-phase)))
      (is (some #(= 'cn.li.ac.content.particles/init-particles! %) (:init-fns ability-phase)))
      (is (some #(= 'cn.li.ac.content.loot/init-loot! %) (:init-fns ability-phase))))
    (let [system-phase (first (filter #(= :system (:phase %)) plan))]
      (is (= '[cn.li.ac.terminal.init/init-terminal!] (:init-fns system-phase))))))

(deftest register-content-phase-plugin-appends-after-defaults-test
  (content-ns/register-content-phase-plugin!
    {:phase :demo-extension
     :namespaces '[cn.li.ac.registry.content-namespaces-test]
     :init-fns []})
  (let [plan (content-ns/current-content-load-plan)]
    (is (= :demo-extension (:phase (last plan))))))
