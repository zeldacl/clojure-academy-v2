(ns cn.li.presentation.compiler.artifact
  "Deterministic compiler for Presentation UI artifacts.

   This namespace is build-only. The runtime consumes the serialized EDN
   produced here and never loads presentation-compiler."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(def artifact-magic :pui4)
(def artifact-schema 4)
(def ui-source-schema 1)

(def primitive-types
  #{:absolute :row :column :grid :stack :clip :scroll :portal :repeater :conditional :switch :transform :mask
    :rect :image :nine-slice :text :line :gradient :progress :radial-progress :glow-line
    :button :text-input :item-preview :model-preview :slot-anchor :composite})

(def semantic-roles
  #{:generic :heading :button :textbox :dialog :list :list-item :image :slot :progress :scrollbar})

;; Compile-time catalog only.  Names in this map are never serialized; the
;; artifact carries the stable numeric blueprint id and primitive expansion.
(def component-catalog
  {:tree-view {:blueprint-id 1 :primitive :scroll :edit-policy :replace-only
               :props-schema {:items :binding :selected :binding}
               :slot-schema {}}
   :list-view {:blueprint-id 2 :primitive :scroll :edit-policy :replace-only
               :props-schema {:items :binding :selected :binding}
               :slot-schema {}}})

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

(defn- fail [path message]
  (throw (ex-info (str path ": " message) {:path path})))

(defn- state-path? [value]
  (and (vector? value) (= :state (first value)) (<= 2 (count value))))

(defn- collect-binding-paths [value]
  (cond
    (state-path? value) [value]
    (map? value) (mapcat collect-binding-paths (vals value))
    (vector? value) (mapcat collect-binding-paths value)
    (seq? value) (mapcat collect-binding-paths value)
    :else []))

(defn- normalize-semantics [semantics path]
  (when (some? semantics)
    (when-not (map? semantics) (fail path "must be a map"))
    (when-let [role (:role semantics)]
      (when-not (contains? semantic-roles role)
        (fail (str path ".role") (str "unsupported role " role)))))
  (or semantics {}))

(defn- normalize-component-type [value]
  (when value
    (let [keyword-value (if (keyword? value) value (keyword (str value)))]
      (keyword (name keyword-value)))))

(declare expand-components)

(defn- expand-components [source]
  (let [component (normalize-component-type (or (:component source) (:type source)))
        descriptor (get component-catalog component)]
    (if descriptor
      (let [props (or (:props source) {})
            expanded (-> source
                         (dissoc :component :type :props)
                         (merge props)
                         (assoc :type (:primitive descriptor)
                                :blueprint-id (:blueprint-id descriptor))
                         (update :semantics #(merge {:role :list} (or % {}))))]
        (update expanded :children #(mapv expand-components (or % []))))
      (do
        (when-not (contains? primitive-types (keyword (str (:type source))))
          ;; Leave unknown values for normalize-node so it produces the same
          ;; source-path diagnostic as ordinary primitive validation.
          source)
        (update source :children #(mapv expand-components (or % [])))))))

(defn- normalize-node [source path index bindings actions]
  (when-not (map? source) (fail path "node must be a map"))
  (let [type (:type source)
        type (if (keyword? type) type (keyword (str type)))
        _ (when-not (contains? primitive-types type)
            (fail (str path ".type") (str "unsupported primitive " type)))
        key (or (:key source) (keyword (str "node-" index)))
        bind (or (:bind source) {})
        on (or (:on source) {})
        _ (when-not (map? bind) (fail (str path ".bind") "must be a map"))
        _ (when-not (map? on) (fail (str path ".on") "must be a map"))
        binding-ids (into {}
                          (for [[attribute expression] (sort-by (comp pr-str key) bind)
                                :let [paths (vec (collect-binding-paths expression))
                                      path* (first paths)]
                                :when path*]
                            (let [id (or (get @bindings path*) (count @bindings))]
                              (swap! bindings assoc path* id)
                              [attribute id])))
        action-ids (into {}
                         (for [[event action] (sort-by (comp pr-str key) on)]
                           (let [action (if (keyword? action) action (keyword (str action)))
                                 id (or (get @actions action) (count @actions))]
                             (swap! actions assoc action id)
                             [event id])))
        semantics (normalize-semantics (:semantics source) (str path ".semantics"))
        children (or (:children source) [])
        _ (when-not (sequential? children) (fail (str path ".children") "must be sequential"))]
    (cond-> {:id (vec (conj path index))
     :key key
     :type type
     :layout (or (:layout source) {})
     :style (or (:style source) {})
     :bind bind
     :binding-ids binding-ids
     :on on
     :action-ids action-ids
     :semantics semantics
     :children (mapv #(normalize-node % (conj path index) %2 bindings actions)
                     children (range))}
      (:blueprint-id source) (assoc :blueprint-id (int (:blueprint-id source))))))

(defn- compile-nodes [root]
  (let [bindings (atom {})
        actions (atom {})
        node (normalize-node (expand-components root) [] 0 bindings actions)
        ordered-bindings (->> @bindings
                              (sort-by val)
                              (map (fn [[path id]] {:id id :path path}))
                              vec)
        ordered-actions (->> @actions
                             (sort-by val)
                             (map (fn [[action id]] {:id id :name action}))
                             vec)]
    {:nodes node
     :bindings ordered-bindings
     :actions ordered-actions
     :boundaries (letfn [(walk [node]
                           (concat
                            (when-let [id (:blueprint-id node)]
                              (let [descriptor (some (fn [[_ value]]
                                                       (when (= id (:blueprint-id value)) value))
                                                     component-catalog)]
                                [[(:key node)
                                  (select-keys descriptor [:blueprint-id :edit-policy
                                                            :props-schema :slot-schema])]]))
                            (mapcat walk (:children node))))]
                  (into {} (walk node)))}))
(defn- validate-source! [source path]
  (when-not (map? source)
    (fail path "source must be a map"))
  (when-not (or (:view/id source) (:view-id source))
    (fail path "requires :view/id"))
  (when-not (or (:root source) (:nodes source))
    (fail path "requires :root"))
  (let [schema (:ui/schema source)]
    (when-not (= ui-source-schema schema)
      (fail (str path ".ui/schema")
            (if (nil? schema)
              (str "required source schema " ui-source-schema)
              (str "unsupported source schema " schema)))))
  source)

(defn compile-source [source path]
  (validate-source! source path)
  (let [view-id (or (:view/id source) (:view-id source))
        compiled (compile-nodes (or (:root source) (:nodes source)))
        blueprints (into {}
                         (map (fn [[_ descriptor]]
                                [(:blueprint-id descriptor)
                                 (select-keys descriptor [:blueprint-id :primitive
                                                           :edit-policy :props-schema
                                                           :slot-schema])]))
                         component-catalog)]
    (canonicalize
     {:magic artifact-magic
      :schema artifact-schema
      :ui/schema ui-source-schema
      :view-id view-id
      :source-hash (source-hash source)
      :host (or (:host source) {})
      :state-schema (or (:state-schema source) {})
      :nodes (:nodes compiled)
      :bindings (:bindings compiled)
      :actions (:actions compiled)
      :blueprint-catalog blueprints
      :boundaries (:boundaries compiled)
      :resources (or (:resources source) [])
      :semantics (or (:semantics source) {})
       :editor {:artifact-version 4
                :source-path path
                :root-id [:view view-id]}
      :capabilities (or (:capabilities source)
                        (:requires-capabilities source)
                        #{})})))

(defn- write-edn! [^Path output value]
  (Files/createDirectories (.getParent output)
                           (make-array java.nio.file.attribute.FileAttribute 0))
  (spit (.toFile output) (str (pr-str value) "\n") :encoding "UTF-8"))

(defn- clear-output! [^File root]
  (when (.exists root)
    (doseq [^File file (reverse (sort-by #(.getPath ^File %) (file-seq root)))]
      (.delete file))))
(defn compile-directory! [source-root output-root content-id]
  (let [source-root (.toPath (io/file source-root))
        output-file (io/file output-root)
        _ (when-not (and content-id (not (str/blank? (str content-id))))
            (fail "content-id" "must be a non-empty identifier"))
        content-id (str content-id)
        _ (clear-output! output-file)
        output-root (.toPath output-file)
        files (->> (file-seq (.toFile source-root))
                   (filter #(.isFile ^File %))
                   (filter #(str/ends-with? (.getName ^File %) ".ui.edn"))
                   (sort-by #(.toString (.toPath ^File %))))
        entries (for [^File file files
                      :let [source (edn/read-string (slurp file :encoding "UTF-8"))
                            relative-source (.replace (.toString (.relativize source-root (.toPath file))) "\\" "/")
                            artifact (assoc (compile-source source relative-source)
                                            :content-id content-id)
                            view-id (:view-id artifact)
                            relative (str "assets/" content-id "/presentation-compiled/"
                                          (name (or (namespace view-id) content-id)) "/"
                                          (name view-id) ".uic.edn")
                            target (.resolve output-root relative)]]
                  (do
                    (write-edn! target artifact)
                    [view-id {:resource relative
                              :content-id content-id
                              :source-hash (:source-hash artifact)
                              :schema artifact-schema
                              :host (:host artifact)}]))
        manifest {:magic :pui4-catalog
                  :schema artifact-schema
                  :content-id content-id
                  :content-ids [content-id]
                  :views (into (sorted-map) entries)
                  :generated-by :presentation-compiler}
        manifest-path (.resolve output-root "META-INF/presentation/catalog.edn")]
    (write-edn! manifest-path (canonicalize manifest))
    manifest))
