(ns cn.li.ac.wireless.gui.message-registry
  (:require [cn.li.ac.wireless.gui.messages-dsl :as msg-dsl]))

(def ^:private registry (atom {}))

(defn register-block-messages!
  [domain actions]
  (let [spec (msg-dsl/build-domain-spec domain actions)]
    (swap! registry assoc domain spec)
    spec))

(defn get-domain-spec [domain]
  (get @registry domain))

(defn build-catalog []
  (msg-dsl/build-catalog (vals @registry)))

(defn msg [domain action]
  (msg-dsl/msg-id (build-catalog) domain action))
