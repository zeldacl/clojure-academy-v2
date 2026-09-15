(ns cn.li.node.api
  "Public node-core API for V4 skill/vfx documents and neutral compilation."
  (:require [clojure.pprint :as pp]
            [clojure.walk :as walk]
            [cn.li.node.digest :as digest]
            [cn.li.node.compile :as compile]
            [cn.li.node.surface :as surface]
            [cn.li.node.graph-document :as graph-document]
            [cn.li.node.graph-compile :as graph-compile]
            [cn.li.node.schema-export :as schema-export]
            [cn.li.node.cost :as cost]
            [cn.li.node.environment :as environment]
            [cn.li.node.static-check :as static-check]))

(defn input-problems [ir input] (static-check/problems ir input))
(defn content-hash [value] (digest/content-hash value))
(defn validate-document! [value] (graph-document/validate-document! value))
(defn document-kind [value] (graph-document/kind value))
(defn document-semantic-digest [value] (digest/content-hash (graph-document/semantic-document value)))
(defn validate-v4-document! [value] (validate-document! value))
(defn v4-document-kind [value] (document-kind value))
(defn v4-document-semantic-digest [value] (document-semantic-digest value))
(defn compile-v4-skill-document! [value opts mode] (graph-compile/compile-skill! value opts mode))

(defn read-surface-document
  "Surface DSL text -> the doc-map compile-surface-document! takes.

   Separate from compilation because callers want the document itself --
   ac reads :costs/:cooldown/:presentation off it, and the content digest
   hashes it. read-doc is clojure.edn with *read-eval* false and tagged
   literals rejected, so content is data and never code."
  [text]
  (surface/read-doc text))

(def ^:private document-key-order
  "The order a document's top-level sections are written in.

   Not alphabetical, and not the order a map happens to iterate: a reader
   wants identity first, then the inputs, then the declarations that
   consume them, then the body -- which is the bulk and belongs last. Keys
   not listed follow, sorted, so an unrecognised one is still written
   deterministically rather than dropped or floated."
  [:schema :id :skill :activation
   :parameters :state :tunables
   :costs :cooldown :progression :invariants
   :requires :entry-triggers
   :name-key :description-key :metadata :presentation :mark-policies
   :phases :do])

(defn- canonical
  "doc -> the same doc with its top-level keys in a fixed order.

   Without this an editor save is not byte-stable: a map of more than
   eight keys iterates in hash order, which is deterministic for identical
   content but has no relation to how the file was written, so a no-op
   save rewrote every line and every real edit would have arrived as a
   whole-file diff."
  [doc]
  (let [known (filter #(contains? doc %) document-key-order)
        rest* (sort (remove (set document-key-order) (keys doc)))]
    (apply array-map (mapcat (fn [k] [k (get doc k)]) (concat known rest*)))))

(defn- stamps-only
  "doc -> the same doc with every form's metadata reduced to its :nid.

   The reader attaches :line/:column to any form that carries explicit
   metadata, which for this content means every ^{:nid} stamp gets a
   position for free -- useful in a diagnostic, wrong in a file. A position
   is derived from the text, so persisting it is both redundant and
   self-invalidating: it is stale the moment anything above it moves, and
   writing it back made a no-op save rewrite every stamp downstream of the
   first change. :nid is the only metadata that is actually authored."
  [doc]
  (walk/postwalk
   (fn [f]
     (if-let [m (meta f)]
       (if-let [nid (:nid m)] (with-meta f {:nid nid}) (with-meta f nil))
       f))
   doc))

(defn write-surface-document
  "A surface document -> the text to put on disk. The inverse of
   read-surface-document, and the two must stay that way: an editor save
   that is not read-back-identical silently rewrites content.

   Two bindings carry the weight. *print-meta* is what keeps the ^{:nid}
   stamps, which anchor every diagnostic and the editor's jump-to-node --
   pr-str drops them by default, so a save without it would strip node
   identity from the file while looking like it worked. And pretty-printing
   rather than one line, because a diff a reviewer can read is half the
   reason content is in this form at all."
  [doc]
  (binding [*print-meta* true *print-length* nil *print-level* nil]
    (with-out-str (pp/pprint (stamps-only (canonical doc))))))

(defn compile-surface-document!
  "A surface document (already read) -> {:ir ir :diagnostics [...]}.

   This is the whole pipeline for persisted content now: normalize, then
   compile. The graph path reaches the same compile-program after lowering
   a node/wire serialization into these same forms first, which is why the
   two produce identical IR -- proven document by document over the whole
   catalog in ac's surface-migration-test."
  [doc opts mode]
  (compile/compile-program (surface/normalize doc) opts mode))
(defn compile-v4-vfx-document! [value opts mode] (graph-compile/compile-vfx! value opts mode))
(defn cost-summary [ir vocab] (cost/analyze ir vocab))
(defn export-schema [node-environment] (schema-export/export-environment node-environment))
(defn build-environment [opts] (environment/build opts))
