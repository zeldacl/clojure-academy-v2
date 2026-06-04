(ns cn.li.forge1201.gui.screen-impl-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mc1201.gui.screen.impl :as screen-impl]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(deftest cgui-screen-container-contract-test
  (testing "accepts the expected CGUI screen map shape"
    (is (#'screen-impl/cgui-screen-container?
         {:type :cgui-screen-container
          :cgui :root
          :minecraft-container :menu})))
  (testing "rejects incomplete screen data"
    (is (not (#'screen-impl/cgui-screen-container?
              {:type :cgui-screen-container
               :cgui :root})))))

(deftest slots-enabled-for-click-test
  (testing "defaults to enabled when no tab atom exists"
    (is (#'screen-impl/slots-enabled-for-click? {})))
  (testing "enables clicks only for the inv tab when tab state exists"
    (is (#'screen-impl/slots-enabled-for-click?
         {:current-tab-atom (atom "inv")}))
    (is (not (#'screen-impl/slots-enabled-for-click?
              {:current-tab-atom (atom "stats")})))))

    (deftest quick-move-tab-gate-follows-slot-visibility-test
      (testing "screen bridge quick-move gate allows inventory tab and blocks non-inventory tabs"
        (is (#'screen-impl/slots-enabled-for-click?
          {:current-tab-atom (atom "inv")}))
        (is (not (#'screen-impl/slots-enabled-for-click?
            {:current-tab-atom (atom "wireless")})))))

(deftest with-screen-client-owner-binds-runtime-owner-test
  (let [menu (Object.)
        container {:owner {:logical-side :client
                            :client-session-id :session-a
                            :player-uuid "player-a"}}
        captured (atom nil)]
    (with-redefs [cn.li.mcmod.gui.container-state/get-container-for-menu (fn [_] container)]
      (screen-impl/with-screen-client-owner menu
        #(reset! captured {:client-session-id runtime-hooks/*client-session-id*
                           :owner runtime-hooks/*player-state-owner*})))
    (is (= :session-a (:client-session-id @captured)))
    (is (= "player-a" (:player-uuid (:owner @captured))))))

(deftest owner-for-screen-menu-requires-client-owner-test
  (let [menu (Object.)]
    (with-redefs [cn.li.mcmod.gui.container-state/get-container-for-menu
                  (fn [_] {:owner {:server-session-id [:server 1]
                                   :player-uuid "player-a"
                                   :logical-side :server}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"client-owner contract violation"
                            (screen-impl/owner-for-screen-menu menu))))))