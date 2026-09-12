(ns cn.li.mcmod.platform.block-manipulation-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.platform.block-manipulation :as blocks]))

(deftest destroy-gate-receives-world-id
  (let [fw-atom (atom {:platform {:block-destroy-gate
                                  (fn [world-id]
                                    (= world-id "minecraft:overworld"))}})]
    (with-redefs [fw/fw-atom (constantly fw-atom)]
      (is (true? (blocks/destroy-allowed? "minecraft:overworld")))
      (is (false? (blocks/destroy-allowed? "minecraft:the_nether"))))))
