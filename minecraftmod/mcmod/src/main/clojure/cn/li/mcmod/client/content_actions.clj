(ns cn.li.mcmod.client.content-actions
  "Platform-neutral client content action hooks (terminal, tutorial sync, etc.)."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(def ^:private ^:dynamic *content-actions* nil)

(defn install-client-content-actions!
  [actions label]
  (prt/install-impl! #'*content-actions* actions (or label "client-content-actions")))

(defn content-actions-available?
  []
  (prt/impl-available? #'*content-actions*))

(defn- action-op [k & args]
  (when-let [ops (prt/impl-current #'*content-actions*)]
    (when-let [f (get ops k)]
      (apply f args))))

(defn toggle-terminal!
  [player]
  (action-op :toggle-terminal! player))

(defn tick-tutorial-background-sync!
  []
  (action-op :tick-tutorial-background-sync!))

(defn reset-client-content-actions-for-test!
  []
  (alter-var-root #'*content-actions* (constantly nil))
  nil)
