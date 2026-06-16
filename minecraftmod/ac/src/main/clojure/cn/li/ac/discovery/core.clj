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

(defn- ns-string
  [ns-sym]
  (if-let [ns (namespace ns-sym)]
    (str ns "." (name ns-sym))
    (name ns-sym)))

(defn base-family
  "Resolve family keyword from ability namespace symbol.
  Example: cn.li.ac.content.ability.electromaster/railgun -> :electromaster"
  [ns-sym]
  (let [path (or (namespace ns-sym) (name ns-sym))]
    (when-let [after-ability (second (str/split path #"ability\." 2))]
      (keyword (first (str/split after-ability #"\."))))))

(defn fx-namespace?
  [ns-sym]
  (str/ends-with? (name ns-sym) "-fx"))
