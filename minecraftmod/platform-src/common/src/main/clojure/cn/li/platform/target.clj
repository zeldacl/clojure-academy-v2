(ns cn.li.platform.target
  "Runtime access to the generated platform target metadata."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private target-resource "META-INF/academy-target.edn")

(defn current-target!
  []
  (if-let [resource (io/resource target-resource)]
    (with-open [reader (io/reader resource)]
      (edn/read {:readers *data-readers*} reader))
    (throw (ex-info "Platform target metadata missing"
                    {:resource target-resource}))))

(defn current-target-key!
  []
  (keyword (:id (current-target!))))

(defn current-loader!
  []
  (:loader (current-target!)))
