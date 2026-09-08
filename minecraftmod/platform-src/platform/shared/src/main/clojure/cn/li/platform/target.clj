(ns cn.li.platform.target
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io PushbackReader]))

(def ^:private target-resource "META-INF/academy-target.edn")

(defn- read-target! []
  (if-let [resource (io/resource target-resource)]
    (with-open [reader (io/reader resource)]
      (edn/read {:readers *data-readers*} (PushbackReader. reader)))
    (throw (ex-info "Platform target metadata missing" {:resource target-resource}))))

;; The target descriptor is fixed for the life of the JVM, but current-target!
;; has ~68 call sites and several sit on the startup path, so re-reading and
;; re-parsing the ~9 KB EDN on every call was pure waste. A delay keeps the read
;; lazy -- nothing runs at class-init time, which this namespace needs because it
;; is AOT-compiled for remapped targets (see docs/dev/AOT_BOOTSTRAP.md).
(def ^:private target* (delay (read-target!)))

(defn current-target! [] @target*)
(defn current-target-key! [] (keyword (:id (current-target!))))
(defn current-loader! [] (:loader (current-target!)))
