(ns cn.li.combat.recipe
  "Safe static EDN catalog loader and generic component compiler."
  (:require [cn.li.combat.components :as components]
            [cn.li.combat.dataflow :as dataflow]
            [cn.li.combat.ir :as ir]
            [cn.li.mcmod.runtime.safe-edn :as safe-edn]
            [clojure.java.io :as io]
            [clojure.walk :as walk])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:private max-ir-nodes 4096)
(def ^:private max-composite-depth 16)
(def ^:private max-expanded-nodes 4096)
(def ^:private supported-activations #{:instant :session :toggle :passive})

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- canonical [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[key nested]] [key (canonical nested)]))
                       value)
    (vector? value) (mapv canonical value)
    :else value))

(defn content-hash [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str (canonical value))
                                   StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- validate-node! [node path]
  (when-not (map? node)
    (fail "component node must be a map" {:path path :node node}))
  (when-not (keyword? (:component node))
    (fail "component node requires keyword :component" {:path path :node node}))
  (let [descriptor (components/descriptor (:component node))]
    (when-not descriptor
      (fail "unknown component" {:path path :component (:component node)}))
    (doseq [field (get-in descriptor [:schema :required])]
      (when-not (contains? node field)
        (fail "component field is missing"
              {:path path :component (:component node) :field field})))
    node))

(defn- compile-node [compiler node path]
  (validate-node! node path)
  (let [component (components/descriptor (:component node))
        compiler ((:lower component) compiler node)]
    (when (> (count (:ir compiler)) max-ir-nodes)
      (fail "combat program exceeds IR budget"
            {:path path :limit max-ir-nodes}))
    compiler))

(defn- composite-node-input-keys
  "The subset of a composite's declared :inputs that are :type :node -- the
   only keys on a composite *invocation* that can hold a nested child node."
  [composite-doc]
  (keep (fn [[k v]] (when (= :node (:type v)) k)) (:inputs composite-doc)))

(defn- nested-nodes
  "Discover every nested child-node position on `node`, driven entirely by
   the component's own descriptor (`:children` for builtins, node-typed
   `:inputs` for composite invocations) -- never a hardcoded global key
   list. A component that doesn't declare a child position simply has none
   to walk; there is no way for a new node shape to silently bypass
   validation/expansion the way a fixed key list allowed before."
  [node composites]
  (let [component (:component node)]
    (if-let [composite (get composites component)]
      (keep (fn [k] (when (map? (get node k)) [[k] (get node k)]))
            (composite-node-input-keys composite))
      (let [children-spec (:children (components/descriptor component))]
        (reduce-kv
          (fn [acc key spec]
            (case (:kind spec)
              :single (if (map? (get node key))
                        (conj acc [[key] (get node key)])
                        acc)
              :seq (into acc (map-indexed (fn [index value] [[key index] value])
                                          (filter map? (get node key))))
              :case-map (into acc (keep (fn [[k v]] (when (map? v) [[key k] v]))
                                        (get node key)))
              acc))
          [] children-spec)))))

(defn- collect-vfx-nodes
  "Every :effect/vfx node anywhere in `node`'s already-expanded subtree,
   walked via the same descriptor-driven nested-nodes as compile-tree/
   validate-tree -- no composites remain post-expansion, so the composites
   arg is always {}."
  [node acc]
  (let [acc (if (= :effect/vfx (:component node)) (conj acc node) acc)]
    (reduce (fn [acc [_path child]] (collect-vfx-nodes child acc))
            acc (nested-nodes node {}))))

(defn vfx-signal-requirements
  "Every :effect/vfx node in a compiled ability's :program, as
   {:ability-id :effect-id :operation :payload-keys}. This module only
   extracts what the ability's program actually sends; the neutral VFX
   module (which owns the VFX contract) is the one that judges whether an
   effect document satisfies it -- see that module's install namespace,
   validate-requirements!."
  [ability]
  (mapv (fn [node]
          {:ability-id (:id ability)
           :effect-id (:effect-id node)
           :operation (or (:operation node) :spawn)
           :payload-keys (set (keys (:payload node)))})
        (collect-vfx-nodes (:program ability) [])))

(defn- input-reference [value]
  (let [reference (:ref value)]
    (when (and (map? value)
               (vector? reference)
               (= :input (first reference))
               (keyword? (second reference)))
      [(second reference) (subvec reference 2)])))

(defn- substitute-inputs [value inputs path]
  (if-let [[key suffix] (input-reference value)]
    (if-not (contains? inputs key)
      (fail "composite input is missing" {:path path :input key})
      (let [replacement (get inputs key)]
        (if (seq suffix)
          (get-in replacement suffix)
          replacement)))
    (cond
      (map? value)
      (reduce-kv (fn [result key nested]
                   (assoc result key
                          (substitute-inputs nested inputs (conj path key))))
                 (empty value) value)
      (vector? value)
      (mapv #(substitute-inputs % inputs path) value)
      :else value)))

(defn- composite-inputs [descriptor node path]
  (let [definitions (:inputs descriptor)
        supplied (dissoc node :component)
        unknown (seq (remove #(contains? definitions %) (keys supplied)))]
    (when unknown
      (fail "composite has unknown inputs"
            {:path path :component (:id descriptor) :inputs unknown}))
    (reduce-kv
      (fn [result key definition]
        (if (contains? supplied key)
          (assoc result key (get supplied key))
          (if (and (map? definition) (contains? definition :default))
            (assoc result key (:default definition))
            (fail "composite input is missing"
                  {:path path :component (:id descriptor) :input key}))))
      {} definitions)))

;; Schema v2 design 0(d) (U3; defect #5's actual fix): a composite's own
;; internal loop/iteration variable (its :flow/foreach's :as) is private to
;; its body. Before this, the only way for a caller to compute a per-item
;; value was to guess that internal name and write {:ref [:slot <name>
;; ...]}; a substitution-only expander happily spliced that guess into the
;; composite body, so it "worked" by lucky positional inlining -- and would
;; silently break the moment the composite's author renamed their own local
;; variable, since nothing declared the coupling.
;;
;; :iterates lets a composite publish an abstract per-item port instead:
;; `:iterates {:item {:as :area-target :fields [:id :position]}}` says "my
;; body iterates something bound internally as :area-target; callers may
;; see these fields of it, addressed as :item, never as :area-target." A
;; composite :inputs entry can then declare `{:type :expr-per-item :port
;; :item}`, and callers write {:ref [:item :position]} -- never learning
;; the composite's real internal name. Expansion rewrites :item refs to the
;; composite's own :as name right here, at the one place that already knows
;; both sides of the mapping; every other stage of the pipeline (dataflow
;; checking, the VM) only ever sees ordinary {:ref [:slot ...]}` refs, same
;; as before -- this is authoring-time sugar, not a new runtime concept.
(defn- item-ref? [value]
  (and (map? value)
       (vector? (:ref value))
       (= :item (first (:ref value)))
       (keyword? (second (:ref value)))))

(defn- collect-item-refs
  "Every {:ref [:item field ...]} anywhere in `value`."
  [value acc]
  (cond
    (item-ref? value) (conj acc value)
    (map? value) (reduce-kv (fn [acc _ v] (collect-item-refs v acc)) acc value)
    (vector? value) (reduce (fn [acc v] (collect-item-refs v acc)) acc value)
    :else acc))

(defn- rewrite-item-refs
  "Desugar every {:ref [:item field & path]} in `value` to {:ref [:slot
   as-name field & path]} -- the composite's own internal loop-variable
   name, used here and nowhere else."
  [value as-name]
  (walk/postwalk
    (fn [x]
      (if (item-ref? x)
        (assoc x :ref (into [:slot as-name] (rest (:ref x))))
        x))
    value))

(defn- check-per-item-input
  "Validate and desugar one composite-invocation input value. An
   :expr-per-item input's {:ref [:item field]}` uses must all name a field
   the composite's :iterates entry actually publishes; any other input must
   not reference the :item scope at all -- it has no meaning there."
  [descriptor key value path]
  (let [definition (get (:inputs descriptor) key)]
    (if (= :expr-per-item (:type definition))
      (let [port (:port definition)
            iterates (get (:iterates descriptor) port)
            as-name (:as iterates)
            fields (set (:fields iterates))]
        (when-not as-name
          (fail "composite input declares :type :expr-per-item for a :port with no matching :iterates entry"
                {:path path :component (:id descriptor) :input key :port port}))
        (doseq [ref (collect-item-refs value #{})]
          (let [field (second (:ref ref))]
            (when-not (contains? fields field)
              (fail "per-item field is not published by the composite's :iterates entry"
                    {:path path :component (:id descriptor) :input key
                     :port port :field field :published (vec fields)}))))
        (rewrite-item-refs value as-name))
      (do
        (when (seq (collect-item-refs value #{}))
          (fail "{:ref [:item ...]} used on an input that is not :type :expr-per-item"
                {:path path :component (:id descriptor) :input key}))
        value))))

(defn- fail-on-residual-item-refs! [program]
  (when (seq (collect-item-refs program #{}))
    (fail "{:ref [:item ...]} may only appear as the value of a composite's :type :expr-per-item input"
          {})))

(declare expand-node)

(defn- expand-node [node composites path stack depth]
  (when (> depth max-composite-depth)
    (fail "composite expansion depth exceeded"
          {:path path :limit max-composite-depth}))
  (when-not (map? node)
    (fail "component node must be a map" {:path path :node node}))
  (if-let [descriptor (get composites (:component node))]
    (let [id (:id descriptor)]
      (when (contains? stack id)
        (fail "composite expansion cycle" {:path path :component id}))
      (let [inputs (composite-inputs descriptor node path)
            inputs (into {} (map (fn [[key value]]
                                   [key (check-per-item-input descriptor key value path)]))
                        inputs)
            body (substitute-inputs (:body descriptor) inputs path)]
        (expand-node body composites path (conj stack id) (inc depth))))
    (reduce (fn [result [suffix child]]
              (assoc-in result suffix
                        (expand-node child composites (into path suffix)
                                     stack depth)))
            node
            (nested-nodes node composites))))

(defn expand-composite-tree
  "Expand composite components once during catalog compilation."
  ([node composites] (expand-composite-tree node composites [:program]))
  ([node composites path]
   (let [expanded (expand-node node composites path #{} 0)
         nodes (tree-seq map? #(map second (nested-nodes % composites)) expanded)]
     (when (> (count (filter map? nodes)) max-expanded-nodes)
       (fail "expanded combat tree exceeds node budget"
             {:path path :limit max-expanded-nodes}))
     expanded)))

(defn- validate-tree [node composites path]
  (validate-node! node path)
  (doseq [[suffix child] (nested-nodes node composites)]
    (validate-tree child composites (into path suffix)))
  node)

(defn- compile-tree [compiler node composites path]
  ;; Nested nodes remain embedded in the root component constant.  They are
  ;; validated here, then interpreted by the root component's Clojure
  ;; implementation; emitting each child as a second top-level opcode would
  ;; execute inactive phases as well.
  (validate-tree node composites path)
  (compile-node compiler node path))

(defn- namespace-vfx-instance-keys
  "Prefix every :effect/vfx :instance-key with the owning ability id.

   Schema v2 design 0(g): an :instance-key is a hand-written literal
   (e.g. [:activation :some-effect]); nothing prevented two abilities from
   picking the same literal and silently sharing one VFX instance slot.
   Namespacing by ability id at compile time makes cross-ability collision
   structurally impossible without requiring EDN authors to type the
   ability id themselves, and without changing what they write."
  [node ability-id]
  (walk/postwalk
    (fn [x]
      (if (and (map? x) (= :effect/vfx (:component x)) (vector? (:instance-key x)))
        (update x :instance-key #(into [ability-id] %))
        x))
    node))

(defn compile-ability
  ([ability] (compile-ability ability {}))
  ([ability {:keys [composites] :or {composites {}}}]
  (components/register-builtins!)
  (when-not (keyword? (:id ability))
    (fail "ability id must be a keyword" {:ability ability}))
  (when-not (contains? supported-activations (:activation ability))
    (fail "unsupported ability activation"
          {:id (:id ability) :activation (:activation ability)}))
  (when-not (map? (:program ability))
    (fail "ability requires :program" {:id (:id ability)}))
  (let [;; Schema v2 design D (:fragments): a fragment is a composite scoped
        ;; to this ability's own document instead of a separate shared
        ;; manifest file -- same :inputs/:body shape, same expansion
        ;; mechanism (expand-composite-tree already resolves any
        ;; {:component <name>} whose name is a key in `composites`; a
        ;; fragment just adds its entries to that map before expansion).
        ;; This is what collapses a technique's duplicated branches
        ;; (e.g. two near-identical "detonate" sequences) into one body
        ;; referenced from both call sites.
        fragments (into {}
                        (map (fn [[fragment-id doc]]
                               (when (contains? composites fragment-id)
                                 (fail "fragment id collides with a shared composite"
                                       {:ability-id (:id ability) :fragment fragment-id}))
                               (when (components/descriptor fragment-id)
                                 (fail "fragment id collides with a builtin component"
                                       {:ability-id (:id ability) :fragment fragment-id}))
                               [fragment-id (assoc doc :id fragment-id)]))
                        (:fragments ability))
        composites (merge composites fragments)
        expanded-program (-> (:program ability)
                             (expand-composite-tree composites)
                             (namespace-vfx-instance-keys (:id ability)))
        expanded-ability (assoc ability :program expanded-program)
        ;; Post-expansion no composite refs remain in the tree (they were
        ;; all substituted above), so validate-tree never needs `composites`
        ;; to resolve a node -- passed through anyway so a future relaxation
        ;; of expand-node doesn't silently make validation composite-blind.
        ;; A well-formed {:ref [:item ...]} is always rewritten to a plain
        ;; :slot ref during expansion (see check-per-item-input above); one
        ;; surviving here means it was never inside a :type :expr-per-item
        ;; input at all (e.g. written directly in the ability's own
        ;; program), which check-per-item-input never gets a chance to see.
        _ (fail-on-residual-item-refs! expanded-program)
        _ (try
            (dataflow/check-program! expanded-program)
            (catch clojure.lang.ExceptionInfo e
              (fail "single-direction-dependency check failed"
                    (assoc (ex-data e) :ability-id (:id ability)))))
        compiler (compile-tree
                   {:ir [] :slots {:double 0 :long 0 :boolean 0 :object 0}}
                   expanded-program
                   composites
                   [:program])]
    (assoc expanded-ability
           :compiled? true
           :program-hash (content-hash expanded-ability)
           :compiled-ir (:ir compiler)
           :slot-counts (:slots compiler)
           :compiled-program (ir/encode (:ir compiler) (:slots compiler))))))

(defn load-manifest! [resource-path]
  (safe-edn/read-resource! resource-path))

(defn- collect-input-refs
  "Every :input key actually referenced (as a value ref or a full node ref)
   anywhere in `value`."
  [value acc]
  (cond
    (and (map? value) (vector? (:ref value))
         (= :input (first (:ref value))) (keyword? (second (:ref value))))
    (conj acc (second (:ref value)))
    (map? value) (reduce-kv (fn [acc _ v] (collect-input-refs v acc)) acc value)
    (vector? value) (reduce (fn [acc v] (collect-input-refs v acc)) acc value)
    :else acc))

(defn- unused-composite-inputs
  "Inputs a composite declares but whose :body never reads -- the shape of
   the guarded-owner-patch bug, where a declared :entries input was quietly
   dropped by every caller because the composite's own body never used it."
  [composite]
  (let [declared (set (keys (:inputs composite)))
        used (collect-input-refs (:body composite) #{})]
    (seq (remove used declared))))

(defn load-composites!
  [{:keys [manifest-resource document-loader]
    :or {document-loader safe-edn/read-resource!}}]
  (let [manifest (load-manifest! manifest-resource)
        documents (mapv (fn [{:keys [kind id resource]}]
                          (when-not (= :composite kind)
                            (fail "unsupported combat composite kind" {:kind kind}))
                          (let [composite (document-loader resource)]
                            (when-not (= id (:id composite))
                              (fail "composite manifest/document id mismatch"
                                    {:manifest-id id :document-id (:id composite)}))
                            (when-not (and (= :composite (:kind composite))
                                           (keyword? (:id composite))
                                           (integer? (:revision composite))
                                           (map? (:inputs composite))
                                           (map? (:body composite)))
                              (fail "invalid combat composite document"
                                    {:id id :document composite}))
                            (when-let [unused (unused-composite-inputs composite)]
                              (fail "composite declares inputs its body never reads"
                                    {:id id :unused (vec unused)}))
                            composite))
                        (:documents manifest))]
    (when-not (= (count documents) (count (set (map :id documents))))
      (fail "duplicate combat composite id" {:ids (map :id documents)}))
    {:schema-version 1
     :manifest manifest
     :composites (into {} (map (juxt :id identity) documents))
     :content-hash (content-hash documents)}))

(defn load-catalog!
  "Compile every ability the manifest lists. A single ability's document
   being unparseable, mismatched, or failing a compile-time invariant (e.g.
   the single-direction-dependency check) disables only that ability --
   :errors carries its id and failure so the caller can log it -- and every
   other ability still loads. One bad EDN file must not be able to take the
   whole mod down at boot (Design E: fail-closed per ability, not globally)."
  [{:keys [manifest-resource composites-manifest-resource document-loader
           document-transform]
    :or {document-loader safe-edn/read-resource!}}]
  (components/register-builtins!)
  (let [document-loader (if document-transform
                          (fn [resource]
                            (document-transform (document-loader resource)))
                          document-loader)
        manifest (load-manifest! manifest-resource)
        composites (if composites-manifest-resource
                     (:composites (load-composites!
                                    {:manifest-resource composites-manifest-resource
                                     :document-loader document-loader}))
                     {})
        outcomes (mapv (fn [{:keys [kind id resource]}]
                         (try
                           (when-not (= :ability kind)
                             (fail "unsupported combat document kind" {:kind kind}))
                           (let [ability (document-loader resource)]
                             (when-not (= id (:id ability))
                               (fail "manifest/document id mismatch"
                                     {:manifest-id id :document-id (:id ability)}))
                             {:id id :ability (compile-ability ability {:composites composites})})
                           (catch clojure.lang.ExceptionInfo e
                             {:id id :error {:message (ex-message e) :data (ex-data e)}})))
                       (:documents manifest))
        documents (keep :ability outcomes)
        errors (into {} (keep (fn [{:keys [id error]}] (when error [id error])) outcomes))]
    {:schema-version 1
     :manifest manifest
     :composites composites
     :abilities (into {} (map (juxt :id identity) documents))
     :errors errors
     :content-hash (content-hash {:abilities documents :composites composites})}))
