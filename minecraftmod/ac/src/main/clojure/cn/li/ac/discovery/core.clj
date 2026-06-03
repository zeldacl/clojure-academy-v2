(ns cn.li.ac.discovery.core
  "Shared contracts for namespace discovery providers."
  (:require [clojure.string :as str]))

(defn normalize-provider
  [{:keys [id priority skill-namespaces fx-namespaces]
    :or {priority 100}}]
  {:pre [(keyword? id)]}
  {:id id
   :priority (long priority)
   :skill-namespaces (->> (or skill-namespaces []) distinct vec)
   :fx-namespaces (->> (or fx-namespaces []) distinct vec)})

(defn provider-sort-key
  [provider]
  [(:priority provider) (name (:id provider))])

(defn base-family
  "Resolve family keyword from ability namespace symbol.
  Example: cn.li.ac.content.ability.electromaster.railgun -> :electromaster"
  [ns-sym]
  (when-let [family (nth (str/split (name ns-sym) #"\.") 5 nil)]
    (keyword family)))

(defn fx-namespace?
  [ns-sym]
  (str/ends-with? (name ns-sym) "-fx"))
