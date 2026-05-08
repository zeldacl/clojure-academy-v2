(ns cn.li.mc1201.runtime.edn-state
  (:require [clojure.edn :as edn]))

(defn decode-edn-safe
  "Decode EDN string and return nil on failure.

  `on-error` is an optional callback called with the thrown exception."
  ([s] (decode-edn-safe s nil))
  ([s on-error]
   (try
     (edn/read-string s)
     (catch Exception e
       (when on-error
         (on-error e))
       nil))))

(defn encode-edn
  "Encode value to EDN string via pr-str."
  [v]
  (pr-str v))
