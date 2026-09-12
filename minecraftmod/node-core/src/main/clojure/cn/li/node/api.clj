(ns cn.li.node.api
  "Public node-core API for V4 graph documents and neutral compilation."
  (:require [cn.li.node.digest :as digest]
            [cn.li.node.graph-document :as graph-document]
            [cn.li.node.graph-compile :as graph-compile]
            [cn.li.node.schema-export :as schema-export]
            [cn.li.node.scope :as scope]
            [cn.li.node.cost :as cost]
            [cn.li.node.validate :as validate]
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
(defn compile-v4-vfx-document! [value opts mode] (graph-compile/compile-vfx! value opts mode))
(defn cost-summary [ir vocab] (cost/analyze ir vocab))
(defn validate-in-environment! [node-environment program] (validate/validate-in-environment! node-environment program))
(defn check-scope-in-environment! [node-environment program] (scope/check-in-environment! node-environment program))
(defn export-schema [node-environment] (schema-export/export-environment node-environment))
(defn build-environment [opts] (environment/build opts))
