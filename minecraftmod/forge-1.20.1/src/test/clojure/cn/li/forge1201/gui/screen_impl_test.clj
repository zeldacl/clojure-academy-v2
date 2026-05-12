(ns cn.li.forge1201.gui.screen-impl-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.forge1201.gui.screen-impl :as screen-impl]))

(deftest cgui-screen-container-contract-test
  (testing "accepts the expected CGUI screen map shape"
    (is (#'screen-impl/cgui-screen-container?
         {:type :cgui-screen-container
          :cgui :root
          :minecraft-container :menu})))
  (testing "rejects incomplete screen data"
    (is (not (#'screen-impl/cgui-screen-container?
              {:type :cgui-screen-container
               :cgui :root}))))))

(deftest slots-enabled-for-click-test
  (testing "defaults to enabled when no tab atom exists"
    (is (#'screen-impl/slots-enabled-for-click? {})))
  (testing "enables clicks only for the inv tab when tab state exists"
    (is (#'screen-impl/slots-enabled-for-click?
         {:current-tab-atom (atom "inv")}))
    (is (not (#'screen-impl/slots-enabled-for-click?
              {:current-tab-atom (atom "stats")})))))