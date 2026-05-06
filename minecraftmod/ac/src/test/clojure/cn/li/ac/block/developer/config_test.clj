(ns cn.li.ac.block.developer.config-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.block.developer.config :as cfg]))

(deftest tier-config-default-and-known-tier-test
  (is (= 50000.0 (get-in (cfg/tier-config :normal) [:max-energy])))
  (is (= 200000.0 (get-in (cfg/tier-config :advanced) [:max-energy])))
  (is (= (cfg/tier-config :normal) (cfg/tier-config :missing))))
