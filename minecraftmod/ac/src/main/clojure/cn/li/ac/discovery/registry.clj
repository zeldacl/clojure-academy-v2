(ns cn.li.ac.discovery.registry
  "Generic provider registry used by discovery-based bootstrap flows."
  (:require [cn.li.ac.discovery.core :as core]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private providers* (atom {}))
(defonce ^:private providers-frozen? (atom false))

(defn- assert-registry-open!
  []
  (when @providers-frozen?
    (throw (ex-info "Discovery provider registry is frozen" {}))))

(defn provider-registry-snapshot
  []
  {:providers @providers*
   :frozen? @providers-frozen?})

(defn reset-provider-registry-for-test!
  ([]
   (reset-provider-registry-for-test! {}))
  ([{:keys [providers frozen?]
     :or {providers {} frozen? false}}]
   (reset! providers* providers)
   (reset! providers-frozen? frozen?)
   nil))

(defn freeze-provider-registry!
  []
  (reset! providers-frozen? true)
  nil)

(defn register-provider!
  [provider]
  (let [provider* (core/normalize-provider provider)]
    (assert-registry-open!)
    (swap! providers* assoc (:id provider*) provider*)
    (log/debug "Registered discovery provider" (:id provider*))
    provider*))

(defn unregister-provider!
  [provider-id]
  (assert-registry-open!)
  (swap! providers* dissoc provider-id)
  nil)

(defn clear-providers!
  []
  (reset-provider-registry-for-test!))

(defn registered-providers
  []
  (->> @providers*
       vals
       (sort-by core/provider-sort-key)
       vec))
