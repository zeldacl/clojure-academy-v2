(ns cn.li.ac.block.machine-wireless-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.machine.wireless-handlers :as wireless-handlers]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as sync-handler]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.platform.position :as pos]))

(defn- register-handlers!
  [spec]
  (let [handlers (atom {})]
    (with-redefs [net-server/register-handler (fn [msg-id handler]
                                                (swap! handlers assoc (peek msg-id) handler))
                  msg-registry/msg (fn [_domain action] [:test-domain action])]
      (wireless-handlers/register-link-handlers! spec)
      @handlers)))

(deftest link-handlers-list-nodes-safe-defaults-test
  (let [handlers (register-handlers!
                   {:message-domain :test
                    :get-linked-node (fn [_] nil)
                    :link! (fn [& _] true)
                    :unlink! (fn [_] nil)})]
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)
                  pos/position-get-block-pos (fn [_] :tile-pos)
                  wireless-api/get-nodes-in-range (fn [_ _] [])]
      (is (= {:linked nil :avail []}
             ((:list-nodes handlers) {:pos-x 1 :pos-y 2 :pos-z 3} :player))))))

(deftest link-handlers-connect-and-disconnect-test
  (testing "connect success path"
    (let [link-calls (atom [])
          handlers (register-handlers!
                     {:message-domain :test
                      :get-linked-node (fn [_] nil)
                      :link! (fn [recv node pass need-auth?]
                               (swap! link-calls conj [recv node pass need-auth?])
                               true)
                      :unlink! (fn [_] nil)})]
      (with-redefs [sync-handler/get-world (fn [_] :world)
                    sync-handler/get-tile-at (fn [_ payload]
                                               (if (contains? payload :node-x) :node :recv))]
        (is (= {:success true}
               ((:connect handlers)
                {:pos-x 1 :pos-y 2 :pos-z 3
                 :node-x 9 :node-y 8 :node-z 7
                 :password "pw"
                 :need-auth? false}
                :player)))
        (is (= [[:node :recv "pw" false]] @link-calls)))))

  (testing "connect failure without node payload"
    (let [handlers (register-handlers!
                     {:message-domain :test
                      :get-linked-node (fn [_] nil)
                      :link! (fn [& _] true)
                      :unlink! (fn [_] nil)})]
      (with-redefs [sync-handler/get-world (fn [_] :world)
                    sync-handler/get-tile-at (fn [_ _] :recv)]
        (is (= {:success false}
               ((:connect handlers) {:pos-x 1 :pos-y 2 :pos-z 3} :player))))))

  (testing "disconnect success path"
    (let [unlink-calls (atom 0)
          handlers (register-handlers!
                     {:message-domain :test
                      :get-linked-node (fn [_] nil)
                      :link! (fn [& _] true)
                      :unlink! (fn [_] (swap! unlink-calls inc))})]
      (with-redefs [sync-handler/get-world (fn [_] :world)
                    sync-handler/get-tile-at (fn [_ _] :recv)]
        (is (= {:success true}
               ((:disconnect handlers) {:pos-x 1 :pos-y 2 :pos-z 3} :player)))
        (is (= 1 @unlink-calls))))))
