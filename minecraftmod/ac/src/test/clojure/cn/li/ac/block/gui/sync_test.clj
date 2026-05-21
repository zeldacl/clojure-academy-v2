(ns cn.li.ac.block.gui.sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.gui.sync :as gui-sync]))

(def sample-schema
  [{:key :energy
    :gui-sync? true
    :gui-container-key :energy-atom
    :gui-init (fn [state] (:energy state))}
   {:key :label
    :gui-only? true
    :gui-container-key :label-atom
    :gui-init (fn [state] (:label state))}
   {:key :mode
    :gui-sync? false
    :gui-container-key :mode-atom
    :gui-init (fn [_] :manual)}])

(deftest create-schema-container-exposes-sync-hooks-test
  (testing "schema-backed containers expose cached sync helpers"
    (let [container (gui-sync/create-schema-container sample-schema nil nil :test {:state {:energy 7 :label "hello"}})]
      (is (fn? (:sync-get container)))
      (is (contains? container :sync-last-sent))
      (is (contains? container :sync-has-sent?))
      (is (= {:energy-atom 7}
             ((:sync-get container) container)))
      (is (= "hello" @(get container :label-atom)))
      (is (= 7 @(get container :energy-atom))))))