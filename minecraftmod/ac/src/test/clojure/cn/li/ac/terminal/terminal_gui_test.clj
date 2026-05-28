(ns cn.li.ac.terminal.terminal-gui-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.terminal.terminal-gui :as terminal-gui]
            [cn.li.mcmod.network.client :as net-client]))

(defn- reset-fixture [f]
  (terminal-gui/call-with-terminal-runtime
    (terminal-gui/create-terminal-runtime)
    (fn []
      (terminal-gui/reset-terminal-states-for-test!)
      (try
        (f)
        (finally
          (terminal-gui/reset-terminal-states-for-test!))))))

(use-fixtures :each reset-fixture)

(deftest query-terminal-state-isolated-by-owner-test
  (let [owner-a {:client-session-id :session-a :screen-id :terminal :player-uuid "a"}
        owner-b {:client-session-id :session-a :screen-id :terminal :player-uuid "b"}]
    (with-redefs [net-client/send-to-server
                  (fn [owner _msg-id _payload callback]
                    (callback (if (= "a" (:player-uuid owner))
                                {:terminal-installed? true
                                 :installed-apps ["media-player"]
                                 :available-apps [{:id :media-player}]}
                                {:terminal-installed? false
                                 :installed-apps []
                                 :available-apps [{:id :map}]})))]
      (terminal-gui/query-terminal-state! owner-a nil)
      (terminal-gui/query-terminal-state! owner-b nil))
    (is (= true (:terminal-installed? (terminal-gui/terminal-state-snapshot owner-a))))
    (is (= #{"media-player"} (:installed-apps (terminal-gui/terminal-state-snapshot owner-a))))
    (is (= false (:terminal-installed? (terminal-gui/terminal-state-snapshot owner-b))))
    (is (= #{} (:installed-apps (terminal-gui/terminal-state-snapshot owner-b))))))

(deftest install-and-uninstall-app-update-only-owner-test
  (let [owner-a {:client-session-id :session-a :screen-id :terminal :player-uuid "a"}
        owner-b {:client-session-id :session-a :screen-id :terminal :player-uuid "b"}]
    (with-redefs [net-client/send-to-server
                  (fn [_owner _msg-id _payload callback]
                    (callback {:success true}))]
      (terminal-gui/install-app! owner-a :media-player nil)
      (terminal-gui/install-app! owner-b :map nil)
      (terminal-gui/uninstall-app! owner-a :media-player nil))
    (is (= #{} (:installed-apps (terminal-gui/terminal-state-snapshot owner-a))))
    (is (= #{:map} (:installed-apps (terminal-gui/terminal-state-snapshot owner-b))))
    (is (= false (:loading? (terminal-gui/terminal-state-snapshot owner-a))))
    (is (= false (:loading? (terminal-gui/terminal-state-snapshot owner-b))))))

(deftest cleared-owner-ignores-stale-callback-test
  (let [owner {:client-session-id :session-a :screen-id :terminal :player-uuid "a"}
        captured-callback (atom nil)
        callback-called? (atom false)]
    (with-redefs [net-client/send-to-server
                  (fn [_owner _msg-id _payload callback]
                    (reset! captured-callback callback))]
      (terminal-gui/install-app! owner :media-player (fn [_] (reset! callback-called? true))))
    (is (= true (:loading? (terminal-gui/terminal-state-snapshot owner))))
    (terminal-gui/clear-terminal-state! owner)
    (@captured-callback {:success true})
    (is (= false @callback-called?))
    (is (= #{} (:installed-apps (terminal-gui/terminal-state-snapshot owner))))
    (is (= false (:loading? (terminal-gui/terminal-state-snapshot owner))))))

(deftest reopened-owner-ignores-old-callback-generation-test
  (let [owner {:client-session-id :session-a :screen-id :terminal :player-uuid "a"}
        callbacks* (atom [])]
    (with-redefs [net-client/send-to-server
                  (fn [_owner _msg-id _payload callback]
                    (swap! callbacks* conj callback))]
      (terminal-gui/query-terminal-state! owner nil)
      (terminal-gui/clear-terminal-state! owner)
      (terminal-gui/query-terminal-state! owner nil))
    ((first @callbacks*) {:terminal-installed? true
                          :installed-apps ["media-player"]
                          :available-apps [{:id :media-player}]})
    (is (= false (:terminal-installed? (terminal-gui/terminal-state-snapshot owner))))
    ((second @callbacks*) {:terminal-installed? false
                           :installed-apps []
                           :available-apps [{:id :map}]})
    (is (= false (:terminal-installed? (terminal-gui/terminal-state-snapshot owner))))
    (is (= #{} (:installed-apps (terminal-gui/terminal-state-snapshot owner))))
    (is (= [{:id :map}] (:available-apps (terminal-gui/terminal-state-snapshot owner))))))

(deftest terminal-owner-key-fails-without-player-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Terminal owner requires :player-uuid"
                        (terminal-gui/terminal-state-snapshot {:client-session-id :session-a
                                                               :screen-id :terminal}))))
