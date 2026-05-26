(ns cn.li.mcmod.gui.tabbed-gui-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed]))

(deftest same-container-id-tab-state-is-isolated-by-owner-test
  (let [owner-a {:session-id :session-a :player-uuid "player-a"}
        owner-b {:session-id :session-a :player-uuid "player-b"}
        container-id 9]
    (try
      (tabbed/set-tab-index-by-container-id! owner-a container-id 1)
      (tabbed/set-tab-index-by-container-id! owner-b container-id 0)

      (is (= 1 (tabbed/get-tab-index-by-container-id owner-a container-id)))
      (is (= 0 (tabbed/get-tab-index-by-container-id owner-b container-id)))

      (tabbed/clear-tab-index-by-container-id! owner-a container-id)
      (is (nil? (tabbed/get-tab-index-by-container-id owner-a container-id)))
      (is (= 0 (tabbed/get-tab-index-by-container-id owner-b container-id)))
      (finally
        (tabbed/clear-tab-index-by-container-id! owner-a container-id)
        (tabbed/clear-tab-index-by-container-id! owner-b container-id)))))

(deftest ownerless-tab-state-lookup-fails-fast-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Tabbed GUI container id lookup requires explicit owner"
                        (tabbed/set-tab-index-by-container-id! 9 1)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Tabbed GUI container id lookup requires explicit owner"
                        (tabbed/get-tab-index-by-container-id 9)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Tabbed GUI container id lookup requires explicit owner"
                        (tabbed/clear-tab-index-by-container-id! 9))))
