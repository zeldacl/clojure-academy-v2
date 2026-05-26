(ns cn.li.ac.ability.client.screens.preset-editor-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.screens.preset-editor :as screen]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-screen-fixture [f]
  (screen/reset-editor-states-for-test!)
  (try
    (binding [runtime-hooks/*client-session-id* :test-session]
      (f))
    (finally
      (screen/reset-editor-states-for-test!))))

(use-fixtures :each reset-screen-fixture)

(deftest editor-state-isolated-by-player-owner-test
  (screen/open-screen! "player-1")
  (screen/on-preset-tab-click 2)
  (screen/on-skill-select :railgun)
  (screen/on-slot-click 1)
  (screen/open-screen! "player-2")
  (screen/on-preset-tab-click 3)
  (screen/on-skill-select :meltdowner)
  (screen/on-slot-click 0)
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
  (binding [runtime-hooks/*client-session-id* nil]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Preset editor owner requires :client-session-id"
                          (screen/editor-state-snapshot "player-1"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Preset editor owner requires :player-uuid"
                        (screen/editor-state-snapshot {:client-session-id :session-a}))))
