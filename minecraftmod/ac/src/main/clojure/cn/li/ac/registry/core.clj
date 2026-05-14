(ns cn.li.ac.registry.core
  "Core contracts for AC content discovery."
  (:require [cn.li.mcmod.util.log :as log]))

(defprotocol IContentProvider
  (provider-id [this]
    "Stable unique id for the provider.")
  (priority [this]
    "Sort priority; lower values load first.")
  (content-phases [this]
    "Return a vector of content phase specs contributed by this provider."))

(defrecord ContentProvider [id priority-value phases]
  IContentProvider
  (provider-id [_this] id)
  (priority [_this] priority-value)
  (content-phases [_this] (vec phases)))

(defn content-provider
  "Create a content provider record from plain data."
  [{:keys [id phases priority] :or {priority 100}}]
  {:pre [(some? id) (sequential? phases)]}
  (->ContentProvider id priority phases))

(defn content-provider?
  [value]
  (satisfies? IContentProvider value))

(defn normalize-provider
  [value]
  (cond
    (content-provider? value) value
    (map? value) (content-provider value)
    :else (throw (ex-info "Unsupported content provider" {:value value}))))
