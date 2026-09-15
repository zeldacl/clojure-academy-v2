(ns cn.li.node.api
  "Public node-core API for V4 skill/vfx documents and neutral compilation."
  (:require [cn.li.node.digest :as digest]
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
