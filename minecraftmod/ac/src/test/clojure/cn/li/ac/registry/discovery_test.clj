(ns cn.li.ac.registry.discovery-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.registry.discovery :as discovery]))

(defn- reset-provider-registry-fixture [f]
  (discovery/reset-provider-registry-for-test!)
  (try
    (f)
    (finally
      (discovery/reset-provider-registry-for-test!))))

(use-fixtures :each reset-provider-registry-fixture)

(deftest provider-registry-discovers-in-order-test
  (discovery/register-provider! {:id :b :priority 20 :phases [{:phase :b}]})
  (discovery/register-provider! {:id :a :priority 10 :phases [{:phase :a}]})
  (is (= [:a :b] (map :id (discovery/discover-providers))))
  (is (= [{:phase :a} {:phase :b}]
         (discovery/discovered-content-phases))))

(deftest content-provider-registry-freeze-policy-test
  (discovery/freeze-provider-registry!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Content discovery provider registry is frozen"
                        (discovery/register-provider! {:id :demo :priority 0 :phases []})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Content discovery provider registry is frozen"
                        (discovery/unregister-provider! :demo))))
