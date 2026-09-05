(ns cn.li.ac.block.wireless-node-gui-sync-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.block.wireless-node.gui-reactive :as node-gui]
            [cn.li.ac.block.wireless-node.logic :as node-logic]
            [cn.li.ac.gui.tech-ui-tabs :as tech-tabs]))

(deftest create-container-normalizes-node-default-state-test
  (let [container (node-gui/create-container node-logic/node-default-state :player)]
    (is (= :node (:container-type container)))
    (is (= :basic @(:node-type container)))
    (is (= 15000 @(:max-energy container)))
    (is (= 0 @(:tab-index container)))
    (is (true? (:presentation-tech-tabs? container)))
    (is (= :node (get-in container [:presentation-wireless :role])))))

(deftest node-tab-switch-updates-index-test
  (let [container (node-gui/create-container node-logic/node-default-state :player)]
    (is (true? (:inv-page-visible? (tech-tabs/page-visibility container))))
    (tech-tabs/switch-tab! container 1)
    (is (= 1 @(:tab-index container)))
    (is (true? (:wireless-page-visible? (tech-tabs/page-visibility container))))
    (is (= 2 (count (tech-tabs/tab-strip-items container))))))
