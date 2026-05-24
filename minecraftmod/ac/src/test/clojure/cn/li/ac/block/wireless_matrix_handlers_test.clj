(ns cn.li.ac.block.wireless-matrix-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.wireless-matrix.handlers :as handlers]
            [cn.li.ac.block.wireless-matrix.network-infra :as infra]))

(deftest init-network-requires-owner-test
  (testing "non-owner init is rejected"
    (with-redefs [infra/owner-controller (fn [_ _] nil)
                  infra/create-network! (fn [& _] (throw (ex-info "should-not-call" {})))]
      (is (= {:success false}
             (handlers/handle-init-network {:ssid "s" :password "p"} :player)))))

  (testing "owner init calls create-network"
    (let [called (atom nil)]
      (with-redefs [infra/owner-controller (fn [_ _] {:ctrl :tile})
                    infra/create-network! (fn [tile ssid password]
                                           (reset! called [tile ssid password])
                                           true)]
        (is (= {:success true}
               (handlers/handle-init-network {:ssid "abc" :password "pw"} :player)))
        (is (= [:tile "abc" "pw"] @called)))))

  (testing "owner init failure is normalized"
    (with-redefs [infra/owner-controller (fn [_ _] {:ctrl :tile})
                  infra/create-network! (fn [& _] (throw (RuntimeException. "boom")))]
      (is (= {:success false}
             (handlers/handle-init-network {:ssid "abc" :password "pw"} :player))))))

(deftest change-operations-require-owner-test
  (testing "non-owner change requests are rejected"
    (with-redefs [infra/owner-controller (fn [_ _] nil)
                  infra/wireless-network (fn [& _] (throw (ex-info "should-not-call" {})))
                  infra/change-ssid! (fn [& _] (throw (ex-info "should-not-call" {})))
                  infra/change-password! (fn [& _] (throw (ex-info "should-not-call" {})))]
      (is (= {:success false}
             (handlers/handle-change-ssid {:new-ssid "new"} :player)))
      (is (= {:success false}
             (handlers/handle-change-password {:new-password "newpw"} :player)))))

  (testing "owner change-ssid success path"
    (let [called (atom nil)]
      (with-redefs [infra/owner-controller (fn [_ _] {:ctrl :tile})
                    infra/wireless-network (fn [_] :net)
                    infra/change-ssid! (fn [net ssid]
                                         (reset! called [net ssid])
                                         true)]
        (is (= {:success true}
               (handlers/handle-change-ssid {:new-ssid "new"} :player)))
        (is (= [:net "new"] @called)))))

  (testing "owner change-password success path"
    (let [called (atom nil)]
      (with-redefs [infra/owner-controller (fn [_ _] {:ctrl :tile})
                    infra/wireless-network (fn [_] :net)
                    infra/change-password! (fn [net password]
                                             (reset! called [net password])
                                             true)]
        (is (= {:success true}
               (handlers/handle-change-password {:new-password "newpw"} :player)))
        (is (= [:net "newpw"] @called)))))

  (testing "owner change failure is normalized"
    (with-redefs [infra/owner-controller (fn [_ _] {:ctrl :tile})
                  infra/wireless-network (fn [_] :net)
                  infra/change-ssid! (fn [& _] (throw (RuntimeException. "boom")))
                  infra/change-password! (fn [& _] (throw (RuntimeException. "boom")))]
      (is (= {:success false}
             (handlers/handle-change-ssid {:new-ssid "x"} :player)))
      (is (= {:success false}
             (handlers/handle-change-password {:new-password "y"} :player))))))
