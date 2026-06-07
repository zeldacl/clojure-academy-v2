(ns cn.li.ac.wireless.gui.tab-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.wireless.gui.tab :as tab]
            [cn.li.ac.wireless.gui.tab.view :as tab-view]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]))

(deftest developer-wireless-tab-lazy-activator-runs-once-test
  (let [calls (atom [])
        panel :wireless-panel
      container {:tile-entity :tile-1 :container-id 9}]
      (with-redefs [tab-view/wireless-panel-from-main (fn [_] panel)
        action-payload/action-payload (fn [_ base] (merge (or base {}) {:container-id 9}))
        tab/install-panel-rebuild! (fn [panel* owner payload cfg opts]
                     (swap! calls conj {:panel panel*
            :owner owner
                        :payload payload
                        :cfg cfg
                        :opts opts}))]
    (let [activate (tab/developer-wireless-tab-lazy-activator :root container "logo")]
      (activate)
      (activate)))
    (is (= 1 (count @calls)))
    (is (= panel (:panel (first @calls))))))
