(ns cn.li.presentation.compiler.artifact
  "Deterministic writer for Presentation UI v2 artifacts.

   This namespace is build-only. The runtime consumes the serialized EDN
   produced here and never loads presentation-compiler."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(def artifact-magic :pui2)
(def artifact-schema 2)

(defn- canonicalize [value]
  (cond
    (map? value)
    (into (sorted-map)
          (map (fn [[k v]] [k (canonicalize v)]))
          value)
    (set? value) (vec (sort-by pr-str (map canonicalize value)))
    (vector? value) (mapv canonicalize value)
    (seq? value) (mapv canonicalize value)
    :else value))

(defn- source-hash [source]
  (let [bytes (.getBytes (pr-str (canonicalize source)) StandardCharsets/UTF_8)
        digest (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest bytes)))))

(defn- validate-source! [source path]
  (when-not (map? source)
    (throw (ex-info "UI source must be a map" {:path path})))
  (when-not (or (:view/id source) (:view-id source))
    (throw (ex-info "UI source requires :view/id" {:path path})))
  (when-not (or (:root source) (:nodes source))
    (throw (ex-info "UI source requires :root" {:path path})))
  source)

(defn compile-source [source path]
  (validate-source! source path)
  (let [view-id (or (:view/id source) (:view-id source))
        root (or (:root source) (:nodes source))
        artifact {:magic artifact-magic
                  :schema artifact-schema
                  :view-id view-id
                  :source-hash (source-hash source)
                  :host (or (:host source) {})
                  :state-schema (or (:state-schema source) {})
                  :nodes root
                  :bindings (or (:bindings source) [])
                  :actions (or (:actions source) [])
                  :resources (or (:resources source) [])
                  :semantics (or (:semantics source) {})
                  :capabilities (or (:capabilities source)
                                    (:requires-capabilities source)
                                    #{})}]
    (canonicalize artifact)))

(defn- write-edn! [^Path output value]
  (Files/createDirectories (.getParent output)
                           (make-array java.nio.file.attribute.FileAttribute 0))
  (spit (.toFile output) (str (pr-str value) "\n") :encoding "UTF-8"))

(defn compile-directory! [source-root output-root]
  (let [source-root (.toPath (io/file source-root))
        output-root (.toPath (io/file output-root))
        files (->> (file-seq (.toFile source-root))
                   (filter #(.isFile ^java.io.File %))
                   (filter #(str/ends-with? (.getName ^java.io.File %) ".ui.edn"))
                   (sort-by #(.toString (.toPath ^java.io.File %))))
        entries (for [file files
                      :let [source (edn/read-string (slurp file :encoding "UTF-8"))
                            artifact (compile-source source (.getPath ^java.io.File file))
                            view-id (:view-id artifact)
                            relative (str "assets/academy/presentation-compiled/"
                                          (name (or (namespace view-id) "academy")) "/"
                                          (name view-id) ".uic.edn")
                            target (.resolve output-root relative)]]
                  (do
                    (write-edn! target artifact)
                    [view-id {:resource relative
                              :source-hash (:source-hash artifact)
                              :schema artifact-schema
                              :host (:host artifact)}]))
        manifest {:magic :pui2-manifest
                  :schema artifact-schema
                  :views (into (sorted-map) entries)
                  :generated-by :presentation-compiler}
        manifest-path (.resolve output-root "META-INF/academy-presentation-views.edn")]
    (write-edn! manifest-path (canonicalize manifest))
    manifest))