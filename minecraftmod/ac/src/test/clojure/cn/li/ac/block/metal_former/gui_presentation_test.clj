(ns cn.li.ac.block.metal-former.gui-presentation-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.block.metal-former.gui-reactive :as gui]))

(deftest presentation-button-ids-map-to-server-directions
  (let [directions (atom [])]
    (with-redefs-fn {#'gui/request-alternate!
                     (fn [_ direction] (swap! directions conj direction))}
      (fn []
        (#'gui/handle-button-click! {} 0 nil)
        (#'gui/handle-button-click! {} 1 nil)
        (#'gui/handle-button-click! {} 99 nil)))
    (is (= [-1 1] @directions))))