(ns cn.li.node.api
  "The public surface of node-core, for content modules (ac, and future
   bc/cc) that read/validate/expand a compiled graph. combat-core and
   vfx-core, node-core's other real consumers, are neutral-tier peers and
   may keep requiring node-core's internals directly -- this facade's
   audience is content modules crossing a layer boundary.

   Sized from the V3 editor/catalog consumption points (document expansion,
   schema export, and content hashing;
   scope/structural
   validation), not speculative coverage."
  (:require [cn.li.node.digest :as digest]
            [cn.li.node.document :as document]
            [cn.li.node.graph-document :as graph-document]
            [cn.li.node.document-compile :as document-compile]
            [cn.li.node.schema-export :as schema-export]
            [cn.li.node.scope :as scope]
            [cn.li.node.validate :as validate]
            [cn.li.node.environment :as environment]))

;; ---- content identity ----
(defn content-hash [value] (digest/content-hash value))

;; ---- persisted AC V3 documents -----------------------------------------
(defn validate-document! [value] (document/validate-document! value))
(defn document-kind [value] (document/kind value))
(defn document-semantic-digest [value]
  (digest/content-hash (document/semantic-document value)))
(defn compile-skill-document! [value opts mode]
  (document-compile/compile-skill! value opts mode))

;; ---- V4 graph documents -------------------------------------------------
;; Kept additive while the catalog cutover is in progress.  The old facade
;; remains available until all resources have been converted; no runtime
;; caller should depend on these transitional names after cutover.
(defn validate-v4-document! [value]
  (graph-document/validate-document! value))
(defn v4-document-kind [value]
  (graph-document/kind value))
(defn v4-document-semantic-digest [value]
  (digest/content-hash (graph-document/semantic-document value)))

;; ---- structural validation ----
(defn validate-in-environment! [node-environment program] (validate/validate-in-environment! node-environment program))
(defn check-scope-in-environment! [node-environment program] (scope/check-in-environment! node-environment program))
(defn export-schema [node-environment] (schema-export/export-environment node-environment))

;; ---- environment construction (for a content module assembling its own) ----
(defn build-environment
  "opts is {:descriptors [...] :extra-ops {...}} -- see cn.li.node.environment/build."
  [opts]
  (environment/build opts))
