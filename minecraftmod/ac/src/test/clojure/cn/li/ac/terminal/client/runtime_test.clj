(ns cn.li.ac.terminal.client.runtime-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.terminal.client.runtime :as runtime]
            [cn.li.mcmod.network.client :as net-client]))

(defn- reset-fixture [f]
  (runtime/call-with-runtime
    (runtime/create-runtime)
    (fn []
      (runtime/reset-states-for-test!)
      (try
        (f)
        (finally
          (runtime/reset-states-for-test!))))))

(use-fixtures :each reset-fixture)

(deftest query-state-isolated-by-owner-test
  (let [owner-a {:client-session-id :session-a :screen-id :terminal :player-uuid "a"}
        owner-b {:client-session-id :session-a :screen-id :terminal :player-uuid "b"}]
    (with-redefs [net-client/send-to-server
                  (fn [_owner _msg-id _payload callback]
                    (callback {:terminal-installed? true
                               :installed-apps ["media-player"]
                               :available-apps ["media-player"]}))]
      (runtime/dispatch-event! owner-a :terminal/query-response
                               {:terminal-installed? true
                                :installed-apps ["media-player"]
                                :available-apps ["media-player"]})
      (runtime/dispatch-event! owner-b :terminal/query-response
                               {:terminal-installed? false
                                :installed-apps []
                                :available-apps ["map"]}))
    (is (= true (:terminal-installed? (runtime/state-snapshot owner-a))))
    (is (= #{:media-player} (:installed-apps (runtime/state-snapshot owner-a))))
    (is (= false (:terminal-installed? (runtime/state-snapshot owner-b))))
    (is (= #{} (:installed-apps (runtime/state-snapshot owner-b))))))

(deftest install-and-uninstall-app-update-only-owner-test
  (let [owner-a {:client-session-id :session-a :screen-id :terminal :player-uuid "a"}
        owner-b {:client-session-id :session-a :screen-id :terminal :player-uuid "b"}]
    (runtime/dispatch-event! owner-a :terminal/install-app-result
                             {:success true :app-id (keyword "media-player")})
    (runtime/dispatch-event! owner-b :terminal/install-app-result {:success true :app-id :map})
    (runtime/dispatch-event! owner-a :terminal/uninstall-app-result
                             {:success true :app-id (keyword "media-player")})
    (is (= #{} (:installed-apps (runtime/state-snapshot owner-a))))
    (is (= #{:map} (:installed-apps (runtime/state-snapshot owner-b))))))

(deftest cleared-owner-ignores-stale-generation-test
  (let [owner {:client-session-id :session-a :screen-id :terminal :player-uuid "a"}
        generation (runtime/ensure-owner! owner)]
    (runtime/dispatch-event! owner :terminal/install-app-start nil)
    (is (= true (:loading? (runtime/state-snapshot owner))))
    (runtime/clear-state! owner)
    (is (= false (runtime/owner-active? owner generation)))))

(deftest owner-key-requires-player-uuid-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Terminal owner requires :player-uuid"
                        (runtime/state-snapshot {:client-session-id :session-a
                                                 :screen-id :terminal}))))
