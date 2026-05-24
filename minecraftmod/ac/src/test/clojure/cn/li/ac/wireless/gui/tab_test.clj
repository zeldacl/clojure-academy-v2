(ns cn.li.ac.wireless.gui.tab-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.wireless.gui.tab :as tab]
            [cn.li.ac.wireless.gui.tab.view :as tab-view]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]))

(deftest developer-wireless-tab-lazy-activator-runs-once-test
  (let [calls (atom [])
        panel :wireless-panel
        container {:tile-entity :tile-1}
        activate (with-redefs [tab-view/wireless-panel-from-main (fn [_] panel)
                               net-helpers/tile-pos-payload (fn [_] {:pos-x 1 :pos-y 2 :pos-z 3})
                               tab/install-panel-rebuild! (fn [panel* payload cfg opts]
                                                           (swap! calls conj {:panel panel*
                                                                              :payload payload
                                                                              :cfg cfg
                                                                              :opts opts}))]
                   (tab/developer-wireless-tab-lazy-activator :root container "logo"))]
    (activate)
    (activate)
    (is (= 1 (count @calls)))
    (is (= panel (:panel (first @calls))))))
