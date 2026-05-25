(ns cn.li.ac.ability.server.service.context-mgr-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.test.support.contexts :as test-contexts]
            [cn.li.ac.test.support.player-state :as test-player]
            [cn.li.ac.ability.server.service.context-mgr :as cm]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.model.resource :as rd]
            [cn.li.ac.ability.messages :as catalog]))

(defn- reset-fixture [f]
  (let [saved-skills @skill-registry/skill-registry]
    (test-contexts/clean-contexts-fixture
     #(test-player/clean-player-states-fixture
       (fn []
         (cm/register-send-fns! {:to-client nil :to-server nil})
         (try
           (f)
           (finally
             (reset! skill-registry/skill-registry saved-skills)
             (cm/register-send-fns! {:to-client nil :to-server nil}))))))))

(use-fixtures :each reset-fixture)

(defn- seed-player! [uuid skill-kw]
  (skill-registry/register-skill! {:id skill-kw
                                   :category-id :electromaster
                                   :level 1
                                   :pattern :passive})
  (let [ability-data (-> (ad/new-ability-data)
                         (ad/learn-skill skill-kw))
        resource-data (assoc (rd/new-resource-data) :activated true)]
    (ps/set-player-state! uuid {:ability-data ability-data
                                :resource-data resource-data})))

(deftest activate-context-sends-begin-link-test
  (let [out (atom [])]
    (cm/register-send-fns! {:to-server (fn [msg-id payload]
                                          (swap! out conj [msg-id payload]))
                            :to-client nil})
    (let [c (cm/activate-context! "player-1" :arc-gen)]
      (is (= 1 (count @out)))
      (is (= catalog/MSG-CTX-BEGIN-LINK (first (first @out))))
      (is (= :arc-gen (:skill-id (second (first @out)))))
      (is (= (:id c) (:ctx-id (second (first @out))))))))

(deftest establish-context-success-test
  (let [out (atom [])]
    (seed-player! "p2" :arc-gen)
    (cm/register-send-fns! {:to-client (fn [uuid msg-id payload]
                                         (swap! out conj [uuid msg-id payload]))
                            :to-server nil})
    (let [res (cm/establish-context! "p2" "cid-1" :arc-gen)]
      (is (some? res))
      (is (= 1 (count (filter #(= catalog/MSG-CTX-ESTABLISH (second %)) @out)))))))

(deftest establish-context-rejects-unlearned-test
  (let [out (atom [])]
    (seed-player! "p3" :arc-gen)
    (cm/register-send-fns! {:to-client (fn [uuid msg-id payload]
                                         (swap! out conj [uuid msg-id payload]))
                            :to-server nil})
    (cm/establish-context! "p3" "cid-x" :other-skill)
    (is (some #(= catalog/MSG-CTX-TERMINATE (second %)) @out))))

(deftest push-channel-forwards-to-client-test
  (let [out (atom [])]
    (cm/register-send-fns! {:to-client (fn [uuid msg-id payload]
                                         (swap! out conj [uuid msg-id payload]))
                            :to-server nil})
    (cm/push-channel-to-player! "pu" "ctx-9" :fx {:k 1})
    (is (= 1 (count @out)))
    (let [[u mid p] (first @out)]
      (is (= "pu" u))
      (is (= catalog/MSG-CTX-CHANNEL mid))
      (is (= "ctx-9" (:ctx-id p)))
      (is (= :fx (:channel p)))
      (is (= {:k 1} (:payload p))))))

(deftest tick-context-manager-purges-old-terminated-contexts-test
  (let [old-ts (- (System/currentTimeMillis) 5000)
        fresh-ts (System/currentTimeMillis)
        old-ctx-id "ctx-old-terminated"
        fresh-ctx-id "ctx-fresh-terminated"]
    (ctx/register-context! (assoc (ctx/new-server-context "p-old" :arc-gen old-ctx-id)
                                  :status ctx/STATUS-TERMINATED
                                  :terminated-at-ms old-ts))
    (ctx/register-context! (assoc (ctx/new-server-context "p-fresh" :arc-gen fresh-ctx-id)
                                  :status ctx/STATUS-TERMINATED
                                  :terminated-at-ms fresh-ts))
    (cm/tick-context-manager!)
    (is (nil? (ctx/get-context old-ctx-id)))
    (is (some? (ctx/get-context fresh-ctx-id)))))

(deftest tick-context-manager-terminates-expired-alive-context-test
  (let [ctx-id "ctx-timeout"
        terminated (atom [])]
    (ctx/register-context! (assoc (ctx/new-server-context "p-timeout" :arc-gen ctx-id)
                                  :last-keepalive-ms (- (System/currentTimeMillis) 5000)))
    (cm/register-send-fns! {:to-client (fn [_uuid msg-id payload]
                                         (when (= catalog/MSG-CTX-TERMINATE msg-id)
                                           (swap! terminated conj (:ctx-id payload))))
                            :to-server nil})
    (cm/tick-context-manager!)
    (is (= [ctx-id] @terminated))
    (is (= ctx/STATUS-TERMINATED (:status (ctx/get-context ctx-id))))))

(deftest dispatcher-timing-can-be-overridden-via-system-properties-test
  (let [keepalive-key "ac.ctx.keepalive-timeout-ms"
        grace-key "ac.ctx.terminated-grace-ms"
        old-keepalive (System/getProperty keepalive-key)
        old-grace (System/getProperty grace-key)]
    (try
      (System/setProperty keepalive-key "42")
      (System/setProperty grace-key "84")
      (is (= 42 (ctx/keepalive-timeout-ms)))
      (is (= 84 (ctx/terminated-context-grace-ms)))
      (finally
        (if (some? old-keepalive)
          (System/setProperty keepalive-key old-keepalive)
          (System/clearProperty keepalive-key))
        (if (some? old-grace)
          (System/setProperty grace-key old-grace)
          (System/clearProperty grace-key))))))
