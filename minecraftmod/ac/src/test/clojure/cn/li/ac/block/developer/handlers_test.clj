(ns cn.li.ac.block.developer.handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.developer.handlers :as handlers]
            [cn.li.ac.wireless.gui.sync.handler :as sync-handler]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.network.server :as net-server]))

(deftest handle-get-status-defaults-without-tile-test
  (with-redefs [sync-handler/get-world (fn [_] :world)
                sync-handler/get-tile-at (fn [_ _] nil)]
    (is (= {:energy 0.0
            :max-energy 0.0
            :tier "normal"
            :user-uuid ""
            :user-name ""
            :development-progress 0.0
            :is-developing false
            :structure-valid false
            :linked nil
            :avail []}
           (handlers/handle-get-status {:pos-x 1 :pos-y 2 :pos-z 3} :player)))))

(deftest handle-get-status-returns-state-test
  (with-redefs [sync-handler/get-world (fn [_] :world)
                sync-handler/get-tile-at (fn [_ _] :tile)
                platform-be/get-custom-state (fn [_]
                                               {:energy 12.0
                                                :max-energy 20.0
                                                :tier "advanced"
                                                :user-uuid "u1"
                                                :user-name "User"
                                                :development-progress 0.8
                                                :is-developing true
                                                :structure-valid true})
                wireless-api/get-node-conn-by-receiver (fn [_] nil)]
    (is (= {:energy 12.0
            :max-energy 20.0
            :tier "advanced"
            :user-uuid "u1"
            :user-name "User"
            :development-progress 0.8
            :is-developing true
            :structure-valid true
            :linked nil
            :avail []}
           (handlers/handle-get-status {:pos-x 1 :pos-y 2 :pos-z 3} :player)))))

(deftest handle-start-development-guards-and-success-test
  (testing "rejects invalid structure"
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:structure-valid false})]
      (is (= {:success false :reason "invalid-structure"}
             (handlers/handle-start-development {:pos-x 1 :pos-y 2 :pos-z 3} :player)))))

  (testing "rejects wrong user"
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:structure-valid true :user-uuid "owner"})
                  uuid/player-uuid (fn [_] "other")]
      (is (= {:success false :reason "wrong-user"}
             (handlers/handle-start-development {:pos-x 1 :pos-y 2 :pos-z 3} :player)))))

  (testing "starts development for current user"
    (let [saved (atom nil)
          changed (atom 0)]
      (with-redefs [sync-handler/get-world (fn [_] :world)
                    sync-handler/get-tile-at (fn [_ _] :tile)
                    platform-be/get-custom-state (fn [_] {:structure-valid true :user-uuid ""})
                    platform-be/set-custom-state! (fn [_ st] (reset! saved st))
                    platform-be/set-changed! (fn [_] (swap! changed inc))
                    uuid/player-uuid (fn [_] "self")
                    entity/player-get-name (fn [_] "Player")]
        (is (= {:success true}
               (handlers/handle-start-development {:pos-x 1 :pos-y 2 :pos-z 3} :player)))
        (is (= "self" (:user-uuid @saved)))
        (is (= "Player" (:user-name @saved)))
        (is (true? (:is-developing @saved)))
        (is (= 1 @changed))))))

(deftest handle-stop-development-test
  (let [saved (atom nil)]
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:is-developing true})
                  platform-be/set-custom-state! (fn [_ st] (reset! saved st))
                  platform-be/set-changed! (fn [_] nil)]
      (is (= {:success true}
             (handlers/handle-stop-development {:pos-x 1 :pos-y 2 :pos-z 3} :player)))
      (is (false? (:is-developing @saved))))))

(deftest handle-list-nodes-safe-defaults-test
  (with-redefs [sync-handler/get-world (fn [_] :world)
                sync-handler/get-tile-at (fn [_ _] :tile)
                pos/position-get-block-pos (fn [_] :tile-pos)
                wireless-api/get-node-conn-by-receiver (fn [_] nil)
                wireless-api/get-nodes-in-range (fn [_ _] [])]
    (is (= {:linked nil :avail []}
           (handlers/handle-list-nodes {:pos-x 1 :pos-y 2 :pos-z 3} :player)))))

(deftest handle-connect-and-disconnect-test
  (testing "connect success path"
    (let [link-calls (atom [])]
      (with-redefs [sync-handler/get-world (fn [_] :world)
                    sync-handler/get-tile-at (fn [_ payload]
                                               (if (contains? payload :node-x) :node :recv))
                    wireless-api/link-receiver-to-node! (fn [recv node pass need-auth?]
                                                          (swap! link-calls conj [recv node pass need-auth?])
                                                          true)]
        (is (= {:success true}
               (handlers/handle-connect {:pos-x 1 :pos-y 2 :pos-z 3
                                         :node-x 9 :node-y 8 :node-z 7
                                         :password "pw"
                                         :need-auth? false}
                                        :player)))
        (is (= [[:node :recv "pw" false]] @link-calls)))))

  (testing "connect failure without node payload"
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :recv)]
      (is (= {:success false}
             (handlers/handle-connect {:pos-x 1 :pos-y 2 :pos-z 3} :player)))))

  (testing "disconnect success path"
    (let [unlink-calls (atom 0)]
      (with-redefs [sync-handler/get-world (fn [_] :world)
                    sync-handler/get-tile-at (fn [_ _] :recv)
                    wireless-api/unlink-receiver-from-node! (fn [_] (swap! unlink-calls inc))]
        (is (= {:success true}
               (handlers/handle-disconnect {:pos-x 1 :pos-y 2 :pos-z 3} :player)))
        (is (= 1 @unlink-calls))))))

(deftest register-network-handlers-registers-all-actions-test
  (let [calls (atom [])]
    (with-redefs [net-server/register-handler (fn [msg-id _handler]
                          (swap! calls conj msg-id))
            msg-registry/msg (fn [_ action] [:developer action])]
      (handlers/register-network-handlers!)
      (is (= 6 (count @calls))))))
