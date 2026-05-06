(ns cn.li.ac.ability.server.service.context-mgr-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.server.service.context-mgr :as cm]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.model.resource :as rd]
            [cn.li.mcmod.ability.catalog :as catalog]))

(defn- reset-fixture [f]
  (doseq [id (keys (ctx/get-all-contexts))]
    (ctx/remove-context! id))
  (reset! ps/player-states {})
  (cm/register-send-fns! {:to-client nil :to-server nil})
  (f)
  (doseq [id (keys (ctx/get-all-contexts))]
    (ctx/remove-context! id))
  (reset! ps/player-states {})
  (cm/register-send-fns! {:to-client nil :to-server nil}))

(use-fixtures :each reset-fixture)

(defn- seed-player! [uuid skill-kw]
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
