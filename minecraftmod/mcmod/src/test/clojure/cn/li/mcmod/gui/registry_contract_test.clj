(ns cn.li.mcmod.gui.registry-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.gui.registry-contract :as registry-contract]
            [cn.li.mcmod.network.server :as net-server]))

(deftest handler-contract-validation-test
  (testing "default server GUI contract"
    (is (= :server (:owner-spec (registry-contract/validate-handler-contract!)))))
  (testing "client owner-spec fails for server handlers"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Server GUI handler contract requires :owner-spec :server"
                          (registry-contract/validate-handler-contract!
                           {:owner-spec :client})))))

(deftest screen-contract-validation-test
  (testing "client screen contract passes"
    (is (= :client
           (:owner-spec (registry-contract/validate-screen-contract!
                         {:owner-spec :client})))))
  (testing "server screen contract fails"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires :owner-spec :client"
                          (registry-contract/validate-screen-contract!
                           {:owner-spec :server})))))

(deftest verify-catalog-handlers-test
  (let [catalog {:specs [{:domain :node :action :get-status :msg-id "wireless_node_get_status"}]}
        contracts {:node {:owner-spec :server :payload-routing :sync-routing}}]
    (try
      (net-server/reset-handlers-for-test!)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No network handler registered"
                            (registry-contract/verify-catalog-handlers! {} catalog contracts)))
      (net-server/register-handler "wireless_node_get_status" (fn [_ _] {}) contracts)
      (is (nil? (registry-contract/verify-catalog-handlers!
                 (:handlers (net-server/handlers-snapshot))
                 catalog
                 contracts)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Handler contract does not match"
                            (do
                              (net-server/reset-handlers-for-test!)
                              (net-server/register-handler "wireless_node_get_status"
                                                             (fn [_ _] {})
                                                             {:owner-spec :server
                                                              :payload-routing :none})
                              (registry-contract/verify-catalog-handlers!
                               (:handlers (net-server/handlers-snapshot))
                               catalog
                               contracts))))
      (finally
        (net-server/reset-handlers-for-test!)))))
