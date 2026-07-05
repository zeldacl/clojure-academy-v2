(ns cn.li.ac.ability.client.screens.preset-editor-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.client.screens.preset-editor :as screen]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-screen-fixture [f]
  (managed-screens/call-with-managed-screen-runtime
    (managed-screens/create-managed-screen-runtime)
    (fn []
      (runtime-hooks/with-client-ctx {:session-id :test-session}
        (f)))))

(use-fixtures :each reset-screen-fixture)

(deftest editor-state-isolated-by-player-owner-test
  (screen/open-screen! "player-1")
  (screen/on-preset-tab-click "player-1" 2)
  (screen/on-skill-select "player-1" :railgun)
  (screen/on-slot-click "player-1" 1)
  (screen/open-screen! "player-2")
  (screen/on-preset-tab-click "player-2" 3)
  (screen/on-skill-select "player-2" :meltdowner)
  (screen/on-slot-click "player-2" 0)
  (is (= 2 (:selected-preset (screen/editor-state-snapshot "player-1"))))
  (is (= {2 {1 :railgun}}
         (:pending-changes (screen/editor-state-snapshot "player-1"))))
  (is (= 3 (:selected-preset (screen/editor-state-snapshot "player-2"))))
  (is (= {3 {0 :meltdowner}}
         (:pending-changes (screen/editor-state-snapshot "player-2"))))
  (screen/close-screen! "player-1")
  (is (nil? (:player-uuid (screen/editor-state-snapshot "player-1"))))
  (is (= "player-2" (:player-uuid (screen/editor-state-snapshot "player-2")))))

(deftest editor-owner-requires-explicit-session-and-player-test
  (runtime-hooks/with-client-ctx {:session-id nil}
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Client read model owner requires :client-session-id"
                          (screen/editor-state-snapshot "player-1"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Client read model owner requires :player-uuid"
                        (screen/editor-state-snapshot {:client-session-id :session-a}))))


