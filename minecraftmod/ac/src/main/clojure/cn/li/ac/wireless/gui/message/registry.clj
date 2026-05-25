(ns cn.li.ac.wireless.gui.message.registry
  (:require [cn.li.mcmod.gui.message.dsl :as msg-dsl]))

(def ^:private registry (atom {}))

(def ^:private message-prefix "wireless")

(defn register-block-messages!
  [domain actions]
  (let [spec (msg-dsl/build-domain-spec message-prefix domain actions)]
    (swap! registry assoc domain spec)
    spec))

(defn get-domain-spec [domain]
  (get @registry domain))

(defn build-catalog []
  (msg-dsl/build-catalog (vals @registry)))

(defn msg [domain action]
  (msg-dsl/msg-id (build-catalog) domain action))
