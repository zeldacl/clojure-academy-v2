(ns cn.li.ac.discovery.registry
  "Generic provider registry used by discovery-based bootstrap flows."
  (:require [cn.li.ac.discovery.core :as core]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private providers* (atom {}))

(defn register-provider!
  [provider]
  (let [provider* (core/normalize-provider provider)]
    (swap! providers* assoc (:id provider*) provider*)
    (log/debug "Registered discovery provider" (:id provider*))
    provider*))

(defn unregister-provider!
  [provider-id]
  (swap! providers* dissoc provider-id)
  nil)

(defn clear-providers!
  []
  (reset! providers* {})
  nil)

(defn registered-providers
  []
  (->> @providers*
       vals
       (sort-by core/provider-sort-key)
       vec))
