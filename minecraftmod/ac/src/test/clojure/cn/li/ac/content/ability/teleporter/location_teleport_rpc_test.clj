(ns cn.li.ac.content.ability.teleporter.location-teleport-rpc-test
  "Regression coverage for P1b's single-source-of-cost fix: the UI cost
   preview must read location_teleport.edn's own compiled :tunables (via
   combat-skill-runtime/materialize-tunables) instead of a hand-rolled copy
   of the config lookups, so a config change can never let preview and real
   dispatch drift apart."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.combat-catalog :as catalog]
            [cn.li.ac.content.ability.teleporter.location-teleport-rpc :as rpc]))

(deftest cp-cost-matches-configured-default-curve-test
  (catalog/initialize!)
  ;; Defaults: cp-base [200.0 150.0], cross-dimension-multiplier 2.0,
  ;; min-distance-multiplier 8.0, distance-cap 800.0 (ac/ability/skill_config/
  ;; teleporter.clj). mastery 0.5 lerps cp-base to 175.0; distance 100.0
  ;; square-roots to 10.0 (above the 8.0 floor); same-dimension keeps the
  ;; multiplier at 1.0.
  (is (= 1750.0 (#'rpc/cp-cost 0.5 100.0 false)))
  ;; Cross-dimension applies the 2.0 multiplier on top.
  (is (= 3500.0 (#'rpc/cp-cost 0.5 100.0 true)))
  ;; A very short hop is floored at the 8.0 minimum distance multiplier, not
  ;; sqrt(1.0) = 1.0.
  (is (= 1400.0 (#'rpc/cp-cost 0.5 1.0 false))))
