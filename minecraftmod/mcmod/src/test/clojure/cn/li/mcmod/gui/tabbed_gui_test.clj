(ns cn.li.mcmod.gui.tabbed-gui-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed]))

(use-fixtures
  :each
  (fn [f]
    (container-state/call-with-container-state-runtime
      (container-state/create-container-state-runtime)
      (fn []
        (try
          (f)
          (finally
            (container-state/clear-all!)))))))

(deftest same-container-id-tab-state-is-isolated-by-owner-test
  (let [owner-a {:logical-side :client :client-session-id :session-a :player-uuid "player-a"}
        owner-b {:logical-side :client :client-session-id :session-a :player-uuid "player-b"}
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

(deftest attach-tab-sync-skips-network-send-when-owner-and-runtime-session-missing-test
  (let [pages [{:id "inv"} {:id "wireless"}]
        tech-ui {:current (atom "inv")}
        container {:tab-index (atom 0)}
        sent-calls (atom [])]
    (binding [runtime-hooks/*client-session-id* nil]
      (with-redefs [tabbed/send-set-tab! (fn [& args]
                                           (swap! sent-calls conj args))]
        (tabbed/attach-tab-sync! pages tech-ui container 17)
        (reset! (:current tech-ui) "wireless")))
    (is (empty? @sent-calls))
    (is (= 1 @(:tab-index container)))))

(deftest attach-tab-sync-sends-scoped-owner-when-available-test
  (let [owner {:client-session-id :session-a :player-uuid "player-a"}
        pages [{:id "inv"} {:id "wireless"}]
        tech-ui {:current (atom "inv")}
        container {:tab-index (atom 0)
                   :owner owner}
        sent-calls (atom [])]
    (with-redefs [tabbed/send-set-tab! (fn [& args]
                                         (swap! sent-calls conj args))]
      (tabbed/attach-tab-sync! pages tech-ui container 7)
      (reset! (:current tech-ui) "wireless"))
    (is (= [[owner 0 7]
            [owner 1 7]]
           @sent-calls))))

