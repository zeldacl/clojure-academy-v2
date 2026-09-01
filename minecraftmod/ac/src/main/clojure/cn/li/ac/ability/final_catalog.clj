(ns cn.li.ac.ability.final-catalog
  "Pure AC content assembly for the final graph architecture.

   This namespace owns resource loading and typed registration metadata only.
   It never invokes a Minecraft API, a legacy evaluator, or a client callback.
   Combat programs remain source graphs until their nodes have been lowered to
  the final compiler vocabulary; VFX descriptors are validated immediately so
  the combat catalog can depend on a stable VFX ABI first."
  (:require [clojure.java.io :as io]
            [cn.li.ability.compose :as ability-compose]
            [cn.li.node.composite :as composite]
            [cn.li.node.composite-loader :as composite-loader]
            [cn.li.node.digest :as digest]
            [cn.li.vfx.vocabulary :as vfx-vocabulary]
            [cn.li.vfx.system-compiler :as vfx-system-compiler]
            [cn.li.combat.vocabulary :as vocabulary]))
(def ^:const schema-version 1)
(def ^:const expected-combat-sources 39)
(def ^:const expected-combat-registrations 50)
(def ^:const expected-vfx-effects 36)

(defn content-hash
  "Deterministic, cross-process content identity -- see cn.li.node.digest.
   Previously this file's own copy fed the same kind of catalog value into
   clojure.core/hash, a JVM-LOCAL hash meaningless across two processes
   (two players' clients, or a client and a server) even though it was used
   for exactly that purpose."
  [value]
  (digest/content-hash value))

(defn read-resource
  "Read one classpath EDN resource.

   Uses clojure.java.io/resource (works under every loader's classloader,
   unlike ClassLoader/getSystemResource, which under Forge/Fabric's module
   classloaders is not necessarily the mod's own classloader) but
   deliberately keeps the permissive read-string parser rather than
   cn.li.mcmod.runtime.safe-edn's stricter reader: safe-edn rejects any map
   with a non-keyword key, and real content already ships that shape (e.g.
   ac/combat/abilities/groundshock.edn's :energy-cost and :block-transforms
   are keyed by block-id strings like \"minecraft:stone\" -- a pre-existing,
   already-documented content defect, not something this relocation should
   silently start rejecting)."
  [resource]
  (let [url (io/resource resource)]
    (when-not url
      (throw (ex-info "AC catalog resource not found" {:resource resource})))
    (binding [*read-eval* false]
      (read-string (slurp url)))))

(defn- require-keyword [label value data]
  (when-not (keyword? value)
    (throw (ex-info (str label " must be a keyword") (assoc data :value value))))
  value)

(defn- validate-bindings [bindings]
  (when-not (map? bindings)
    (throw (ex-info "registration bindings must be a map" {:bindings bindings})))
  (let [allowed #{:inputs :constants :metadata :presentation :prerequisites}
        unknown (seq (remove allowed (keys bindings)))]
    (when unknown
      (throw (ex-info "registration contains undeclared binding sections"
                      {:unknown unknown}))))
  bindings)

(defn compile-registration
  "Compile one specialization using explicit bindings.

   The old deep :overrides merge is deliberately rejected. A catalog validation step
   may convert old files into named binding sections before calling this
   function, but the final catalog never evaluates an untyped override map."
  [{:keys [id source-id resource bindings] :as registration} source]
  (require-keyword "registration id" id {:registration registration})
  (when-not (string? resource)
    (throw (ex-info "registration resource must be a classpath string"
                    {:registration registration :resource resource})))
  (when (contains? registration :overrides)
    (throw (ex-info "deep registration overrides are not part of final ABI"
                    {:id id :keys (keys (:overrides registration))})))
  (let [bindings (validate-bindings (or bindings {}))]
    {:id id
     :source-id (or source-id (:id source))
     :source-resource resource
     :bindings bindings
     :graph (:program source)}))

(defn- validate-manifest [manifest kind]
  (when-not (= schema-version (:schema-version manifest))
    (throw (ex-info "unsupported AC catalog schema version"
                    {:kind kind :schema-version (:schema-version manifest)})))
  (let [documents (:documents manifest)]
    (when-not (vector? documents)
      (throw (ex-info "AC manifest documents must be a vector" {:kind kind})))
    (when-not (= (count documents) (count (set (map :id documents))))
      (throw (ex-info "AC manifest contains duplicate ids" {:kind kind}))))
  manifest)

(defn- load-composite-docs
  "Load a composite manifest's documents through cn.li.node.composite-loader
   -- the generic manifest+document loader combat-core and vfx-core's own
   composites now route through too (see P1.2/P2.1 refactor commits) --
   instead of this file's own former copy of the same
   schema-version/duplicate-id/id-match/:composite-layer checks."
  [manifest-resource]
  (:documents (composite-loader/load-documents
               {:manifest-resource manifest-resource :document-loader read-resource})))

(defn- load-combat [combat-manifest node-environment composites]
  (let [manifest (validate-manifest (read-resource combat-manifest) :combat)
        sources (reduce (fn [result {:keys [id resource kind source-id]}]
                          (when-not (= :ability kind)
                            (throw (ex-info "combat manifest contains non-ability"
                                            {:id id :kind kind})))
                          (let [source (-> (read-resource resource)
                                           (update :program #(composite/expand-with-environment-and-composites
                                                              node-environment % composites)))
                                source-key (or source-id id)]
                            (when-not (or (= source-key (:id source))
                                          (= id (:id source)))
                              (throw (ex-info "combat source id mismatch"
                                              {:manifest-id id :source-key source-key
                                               :source-id (:id source)})))
                            (assoc result source-key source)))
                        {} (:documents manifest))
        registrations (mapv (fn [registration]
                              (let [source (get sources (or (:source-id registration)
                                                            (:id registration)))]
                                (when-not source
                                  (throw (ex-info "combat registration source missing"
                                                  {:registration (:id registration)
                                                   :source-id (:source-id registration)})))
                                (compile-registration registration source)))
                            (:documents manifest))
        source-files (set (map :source-resource registrations))]
    {:manifest manifest
     :sources sources
     :registrations registrations
     :source-count (count sources)
     :registration-count (count registrations)
     :source-files source-files
      :content-hash (content-hash {:sources sources :registrations registrations})
      :composites composites}))

(defn- load-vfx [vfx-manifest node-environment composites]
  (let [manifest (validate-manifest (read-resource vfx-manifest) :vfx)
        effects (mapv (fn [{:keys [id resource kind]}]
                        (when-not (= :vfx/system kind)
                          (throw (ex-info "vfx manifest contains non-effect"
                                          {:id id :kind kind})))
                        (let [effect (read-resource resource)]
                          (when-not (= id (:id effect))
                            (throw (ex-info "vfx source id mismatch"
                                            {:manifest-id id :source-id (:id effect)})))
                           (let [expanded (vfx-system-compiler/expand-graph node-environment (:control-graph effect) composites)]
                             (vfx-system-compiler/validate-vfx-graph! expanded id)
                             (vfx-system-compiler/compile-system (assoc effect :control-graph expanded)))))
                      (:documents manifest))
        catalog (into (sorted-map) (map (fn [effect] [(:id effect) effect]) effects))]
    {:manifest manifest
     :effects catalog
     :effect-count (count catalog)
     :content-hash (content-hash catalog)
     ;; Keep the loaded descriptor map available to audit tooling. Runtime
     ;; execution receives only the expanded effect graphs above; this field
     ;; is metadata and is never consulted by the VFX sampler.
     :composites composites}))

(defn assemble
  "Load the complete AC content catalog in VFX-before-combat order."
  ([] (assemble {}))
  ([{:keys [combat-manifest vfx-manifest combat-composites vfx-composites]
     :or {combat-manifest "ac/combat/manifest.edn"
          vfx-manifest "ac/vfx/manifest.edn"
          combat-composites "cn/li/combat/composites/manifest.edn"
          vfx-composites "cn/li/vfx/composites/manifest.edn"}}]
   (let [combat-composite-docs (load-composite-docs combat-composites)
         vfx-composite-docs (load-composite-docs vfx-composites)
         vfx-node-environment (vfx-vocabulary/environment vfx-composite-docs)
         vfx (load-vfx vfx-manifest vfx-node-environment vfx-composite-docs)
         node-environment (vocabulary/environment combat-composite-docs)
         combat (load-combat combat-manifest node-environment combat-composite-docs)]
     (let [bundle (ability-compose/compose-catalog :ac node-environment combat vfx)]
       (assoc bundle
              :schema-version schema-version
              :counts {:combat-sources (:source-count combat)
                       :combat-registrations (:registration-count combat)
                       :vfx-effects (:effect-count vfx)}
              :content-hash (content-hash
                             (ability-compose/catalog-fingerprint-input bundle)))))))









