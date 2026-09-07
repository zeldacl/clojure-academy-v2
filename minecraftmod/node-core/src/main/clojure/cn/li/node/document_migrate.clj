(ns cn.li.node.document-migrate
  "Deterministic one-way migration from the old surface program to AC V3.

   This namespace is intentionally a content-tool boundary, not a runtime
   compatibility layer. It reads an old program once, emits map-shaped V3
   nodes with stable explicit nids, and can then be removed after resources
   have been migrated. The generated document contains no program string."
  (:require [clojure.string :as str]))

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- surface []
  (requiring-resolve 'cn.li.node.surface/read-doc))

(defn- normalize []
  (requiring-resolve 'cn.li.node.surface/normalize))

(defn- clean [value]
  (let [s (-> (str value)
              str/lower-case
              (str/replace #"[^a-z0-9]+" "-"))]
    (if (seq s) s "node")))

(defn- nid [path]
  (let [base (->> path (map clean) (str/join "-") (str "n-"))
        base (if (<= (count base) 31) base
                 (str (subs base 0 20) "-" (Integer/toHexString (hash base))))]
    (keyword "n" base)))

(defn- ref-node [value path]
  (let [[scope key] ((requiring-resolve 'cn.li.node.surface/sigil) value)]
    {:nid (nid path)
     :ref [(case scope
             :tunable :parameter
             :capability :context
             :state :state
             :local :local
             (fail "unsupported old surface sigil" {:value value :scope scope}))
           (if (= :local scope) (keyword (name key)) key)]}))

(declare expr)

(defn- literal-map [m path]
  (into {}
        (map (fn [[k v]] [k (expr v (conj path (clean k)))]))
        m))

(defn- literal-vector [v path]
  (mapv #(expr % (conj path %2)) v (range)))

(defn- call-component [head args path]
  (let [component (keyword (namespace head) (name head))]
    (if (and (= 1 (count args)) (map? (first args)))
      {:nid (nid path)
       :component component
       :inputs (literal-map (first args) (conj path "inputs"))}
      {:nid (nid path)
       :component component
       :args (mapv #(expr %1 (conj path %2)) args (range))})))

(defn expr [value path]
  (cond
    (symbol? value) (ref-node value path)
    (seq? value)
    (let [head (first value)
          args (next value)]
      (cond
        (keyword? head)
        (do
          (when-not (= 1 (count args))
            (fail "old field access must have one argument" {:form value :path path}))
          {:nid (nid path)
           :component :value/field
           :inputs {:field head :value (expr (first args) (conj path "value"))}})

        (symbol? head) (call-component head args path)
        :else (fail "unsupported old expression head" {:form value :path path})))

    (map? value) (literal-map value path)
    (vector? value) (literal-vector value path)
    :else value))

(declare stmt)

(defn- statements [body path]
  (vec (mapcat (fn [form index]
                 (let [out (stmt form (conj path index))]
                   (if (vector? out) out [out])))
               body (range))))

(defn stmt [form path]
  (when-not (seq? form)
    (fail "old ability statement must be a list" {:form form :path path}))
  (let [head (first form)
        args (next form)]
    (case head
      let
      (let [[local-name value & extra] args]
        (when (or (not (symbol? local-name)) (seq extra))
          (fail "old let must be (let symbol expression)" {:form form :path path}))
        {:nid (nid path) :flow :bind :name (keyword (clojure.core/name local-name))
         :value (expr value (conj path "value"))})

      when
      (let [[condition & body] args]
        {:nid (nid path) :flow :when
         :condition (expr condition (conj path "condition"))
         :do (statements body (conj path "do"))})

      if
      (let [[condition then else] args]
        {:nid (nid path) :flow :if
         :condition (expr condition (conj path "condition"))
         :then (if (vector? then) (statements then (conj path "then"))
                   (statements [then] (conj path "then")))
         :else (if (vector? else) (statements else (conj path "else"))
                   (statements [else] (conj path "else")))})

      each
      (let [[binding collection & body] args
            [local-name index-name] (if (vector? binding) binding [binding nil])]
        (when-not (symbol? local-name)
          (fail "old each binding must be a symbol or [symbol index]" {:form form :path path}))
        (when (and index-name (not (symbol? index-name)))
          (fail "old each index binding must be a symbol" {:form form :path path}))
        {:nid (nid path) :flow :foreach :as (keyword (clojure.core/name local-name))
         :index-as (some-> index-name clojure.core/name keyword)
         :collection (expr collection (conj path "collection"))
         :limit 256
         :do (statements body (conj path "do"))})

      finish
      {:nid (nid path) :flow :finish
       :result (literal-map (or (first args) {}) (conj path "result"))}

      do
      (statements args path)

      (let [node (expr form path)]
        (when-not (:component node)
          (fail "old effect statement did not lower to a component" {:form form :path path}))
        ;; A field read is an expression, but the old surface accepted it in
        ;; a branch vector. Preserve evaluation order with an explicit throw-
        ;; away bind instead of emitting a bare `(:field value)` statement,
        ;; which the register compiler correctly rejects.
        (if (= :value/field (:component node))
          {:nid (nid path) :flow :bind
           :name (keyword (str "_discard-" (name (nid path))))
           :value node}
          node)))))

(defn- entry-trigger [entry]
  (case entry
    :default :activation/start
    :activate :activation/start
    (keyword "phase" (name entry))))

(defn migrate-skill
  "Convert one old skill resource map (with a string :program) to skill-v3."
  [old]
  (let [program-text (:program old)
        raw ((surface) program-text)
        program ((normalize) raw)
        parameters (into {}
                         (map (fn [[k spec]]
                                [k {:type (or (:type spec) :any)
                                    :default (get spec :default nil)}]))
                         (:tunables program))
        entries (:entries program)]
    {:schema :ac/skill-v3
     :id (:id old)
     :skill {:category (or (:category old) :migrated) :level 1}
     :activation {:mode (or (:activation program) :instant)}
     :parameters parameters
     :state (or (:state program) {})
     :entries (into {}
                    (map (fn [[entry body]]
                           [entry {:on (entry-trigger entry)
                                   :do (statements body [:entry entry])}]))
                    entries)}))

(defn migrate-registration
  "Expand one old manifest registration into its own public V3 document.

   The old `:source-id`/`:bindings` indirection is intentionally not emitted.
   Registration metadata becomes document metadata, and the public id is the
   registration id, so the resulting directory is directly editable and has
   one file per public skill."
  [registration old]
  (let [metadata (get-in registration [:bindings :metadata])
        presentation (get-in registration [:bindings :presentation])
        category (or (:category-id metadata) (:category old) :migrated)
        migrated (migrate-skill (assoc old :id (:id registration)))]
    (cond-> (assoc-in migrated [:skill :category] category)
      (some? metadata) (assoc :metadata metadata)
      (contains? (:bindings registration) :presentation)
      (assoc :presentation presentation))))

(defn migrate-vfx
  "Convert one old VFX wrapper (whose :scene is an ability-shaped surface
   document) into the structured VFX V3 contract.  The render program is
   placed under :system/:render; no source text is retained in the result.
   Emitter declarations are carried as structured :emitters so Niagara-like
   module stacks remain editable and can be compiled by vfx-core later."
  [old]
  (let [skill (migrate-skill {:id (:id old) :program (:scene old)})
        spawn (get-in old [:inputs :spawn] {})
        update (get-in old [:inputs :update] {})
        type-of (fn [v] (if (map? v) (:type v) v))
        inputs (into {}
                     (map (fn [[key spec]] [key {:type (type-of spec)}]))
                     (merge spawn update))
        state (into {}
                    (map (fn [[key spec]] [key {:type (type-of spec) :default nil}]))
                    (or (:state-slots old) {}))]
    {:schema :ac/vfx-v3
     :id (:id old)
     :lifecycle {:mode (:lifecycle old)}
     :inputs inputs
     :state state
     :system {:render (get-in skill [:entries :default :do] [])}
     :emitters (vec (or (:emitters old) []))}))
