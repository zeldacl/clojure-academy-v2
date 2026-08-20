(ns cn.li.mcmod.runtime.safe-edn
  "Strict, data-only EDN loading shared by combat and VFX content.

   The reader is intentionally followed by a recursive admissibility check:
   clojure.edn has built-in tagged readers, while content files must contain
   only immutable scalar/vector/map data."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io PushbackReader Reader]))

(defn- finite-number? [value]
  (or (integer? value)
      (and (double? value)
           (Double/isFinite (double value)))))

(defn- valid-value? [value]
  (cond
    (or (nil? value) (boolean? value) (string? value)
        (keyword? value) (finite-number? value)) true
    (vector? value) (every? valid-value? value)
    (map? value) (and (every? keyword? (keys value))
                      (every? valid-value? (vals value)))
    :else false))

(defn read-one!
  "Read exactly one data-only EDN value from reader; reject tags and trailing forms." 
  [^Reader reader]
  (let [stream (PushbackReader. reader)
        reject-tag (fn [tag value]
                     (throw (ex-info "EDN tag is forbidden"
                                     {:tag tag :value value})))
        options {:eof ::eof :readers {} :default reject-tag}
        value (edn/read options stream)
        trailing (edn/read options stream)]
    (when (= value ::eof)
      (throw (ex-info "empty EDN document" {})))
    (when-not (= trailing ::eof)
      (throw (ex-info "multiple EDN forms are forbidden" {})))
    (when-not (valid-value? value)
      (throw (ex-info "unsupported EDN value" {:value value})))
    value))

(defn read-resource!
  "Load one classpath resource and attach its resource path to failures." 
  [resource-path]
  (when-not (string? resource-path)
    (throw (ex-info "resource path must be a string" {:resource resource-path})))
  (if-let [stream (io/resource resource-path)]
    (try
      (with-open [reader (java.io.InputStreamReader. (.openStream stream)
                                                    "UTF-8")]
        (read-one! reader))
      (catch Throwable throwable
      (throw (ex-info "invalid EDN resource"
                        {:resource resource-path}
                        throwable))))
    (throw (ex-info "EDN resource not found" {:resource resource-path}))))
