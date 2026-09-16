(ns cn.li.ac.vfx.fx-catalog-v4
  "Directory-backed V4 VFX catalog."
  (:require [clojure.java.io :as io] [cn.li.ac.util.classpath-edn :as classpath-edn] [cn.li.mcmod.util.log :as log] [cn.li.node.api :as node-api]))
(defn resource-names [root] (classpath-edn/edn-resource-names root {:sentinels ["beam-arc-fade.edn" "beam-session.edn"]}))
(defn- read-resource [r] (let [u (or (classpath-edn/find-resource r) (io/resource r))] (when-not u (throw (ex-info "V4 VFX resource not found" {:resource r}))) (node-api/read-surface-document (slurp u))))
(defn- read-effect
  "One ac/vfx-v4/*.edn -> the catalog entry.

   Effects are surface DSL, so there is no graph structure to validate --
   the rules that layer enforced were about wires. What is still worth
   refusing here is a document of the wrong kind sitting in the directory."
  [r]
  (let [d (read-resource r)]
    (when-not (= :ac/vfx-v4 (:schema d))
      (throw (ex-info "V4 VFX catalog contains non-VFX document" {:resource r :schema (:schema d)})))
    {:resource r :id (:id d) :document d
     :user-types (into {} (map (fn [[k v]] [k (:type v)]) (:parameters d)))
     ;; Carried through to cn.li.vfx.runtime, which compiles one emitter
     ;; stack per instance off (:emitters decl). Without this the whole
     ;; particle subsystem is unreachable from content -- it was, and no
     ;; effect declared any.
     :emitters (:emitters d)
     :phases (:phases d) :lifecycle (get-in d [:lifecycle :mode])}))
(defn assemble ([] (assemble {})) ([{:keys [resource-root] :or {resource-root "ac/vfx-v4"}}] (let [resources (resource-names resource-root) _ (when (empty? resources) (log/warn "VFX catalog enumerated no V4 documents" {:resource-root resource-root})) effects (mapv read-effect resources) ids (map :id effects)] (when-not (= (count ids) (count (set ids))) (throw (ex-info "V4 VFX ids must be unique" {:ids ids}))) {:effects effects :by-id (into {} (map (juxt :id identity)) effects) :resource-count (count resources)})))

