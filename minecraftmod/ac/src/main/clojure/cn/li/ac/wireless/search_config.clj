(ns cn.li.ac.wireless.search-config
  (:require [cn.li.ac.config.common :as config-common]
            [cn.li.mcmod.config.registry :as config-reg]))

(def descriptors
  [{:key :search-node-range
    :section :search
    :path "search.node-search-range"
    :type :double
    :default 20.0
    :min 0.0
    :max 4096.0
    :comment "Search radius for generators/receivers querying nearby nodes."}
   {:key :search-max-results
    :section :search
    :path "search.max-results"
    :type :int
    :default 100
    :min 1
    :max 10000
    :comment "Maximum results returned by wireless range searches."}])

(def default-values
  (into {} (map (juxt :key :default) descriptors)))

(defn- cfg []
  (merge default-values
         (config-reg/get-config-values config-common/wireless-domain)))

(defn node-search-range []
  (:search-node-range (cfg)))

(defn max-results []
  (:search-max-results (cfg)))