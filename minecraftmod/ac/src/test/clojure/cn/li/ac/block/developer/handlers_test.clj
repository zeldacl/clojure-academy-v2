(ns cn.li.ac.block.developer.handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.developer.handlers :as handlers]
            [cn.li.ac.block.machine.handlers :as machine-handlers]
            [cn.li.ac.wireless.gui.sync.handler :as sync-handler]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.network.server :as net-server]))

(def ^:private payload {:container-id 7})

(deftest handle-start-development-guards-and-success-test
  (testing "rejects invalid structure"
    (with-redefs [machine-handlers/open-container-tile (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:structure-valid false})]
      (is (= {:success false :reason "invalid-structure"}
             (handlers/handle-start-development payload :player)))))

  (testing "rejects wrong user"
    (with-redefs [machine-handlers/open-container-tile (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:structure-valid true :user-uuid "owner"})
                  uuid/player-uuid (fn [_] "other")]
      (is (= {:success false :reason "wrong-user"}
             (handlers/handle-start-development payload :player)))))

  (testing "starts development for current user"
    (let [saved (atom nil)
          changed (atom 0)]
      (with-redefs [machine-handlers/open-container-tile (fn [_ _] :tile)
                    sync-handler/get-world (fn [_] :world)
                    platform-be/get-custom-state (fn [_] {:structure-valid true :user-uuid ""})
                    platform-be/set-custom-state! (fn [_ st] (reset! saved st))
                    platform-be/set-changed! (fn [_] (swap! changed inc))
                    uuid/player-uuid (fn [_] "self")
                    entity/player-get-name (fn [_] "Player")]
        (is (= {:success true}
               (handlers/handle-start-development payload :player)))
        (is (= "self" (:user-uuid @saved)))
        (is (= "Player" (:user-name @saved)))
        (is (true? (:is-developing @saved)))
        (is (= 1 @changed))))))

(deftest handle-stop-development-test
  (let [saved (atom nil)]
    (with-redefs [machine-handlers/open-container-tile (fn [_ _] :tile)
                  sync-handler/get-world (fn [_] :world)
                  platform-be/get-custom-state (fn [_] {:is-developing true})
                  platform-be/set-custom-state! (fn [_ st] (reset! saved st))
                  platform-be/set-changed! (fn [_] nil)]
      (is (= {:success true}
             (handlers/handle-stop-development payload :player)))
      (is (false? (:is-developing @saved))))))

(deftest register-network-handlers-registers-all-actions-test
  (let [calls (atom [])]
    (with-redefs [net-server/register-handler (fn [msg-id _handler]
                                                (swap! calls conj msg-id))
                  msg-registry/msg (fn [_ action] [:developer action])]
      (handlers/register-network-handlers!)
      (is (= 5 (count @calls))))))
