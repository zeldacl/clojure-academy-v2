(ns cn.li.vfx.scene
  "Declarative per-frame geometry/audio/camera sampling: an ordinary DSL
   program (surface DSL -> node-core IR -> mcmod CompiledProgram) whose
   leaf calls (cn.li.vfx.dsl-vocabulary) each append one draw/audio/camera
   op to the frame's own outbox instead of dispatching a real host command
   -- see dsl-vocabulary's docstring for why every scene node is
   :action-kind. Sampling has no side effects on the world; it is re-run
   every real frame against fresh :user params (this effect instance's own
   spawn/current arguments) plus :age/:progress.

   cn.li.mcmod.runtime.effect-emit's :action compiler discards whatever
   :command! returns -- it is called purely for effect. So THIS host's
   :command! is where the accumulation itself happens: it appends the
   constructed {:kind cap ...args} op directly onto the frame's own
   .actions, which the caller (cn.li.vfx.compile / a future runtime.clj)
   reads back afterward as this sample's ops."
  (:require [cn.li.node.compile :as compile]
            [cn.li.node.ops :as ops]
            [cn.li.node.surface :as surface]
            [cn.li.vfx.dsl-vocabulary :as vocab]
            [cn.li.mcmod.runtime.effect-emit :as emit])
  (:import [cn.li.mcmod.runtime.effect ExecutionFrame]))

(def ^:private universal-capabilities
  "Present on every scene sample regardless of which effect declared what.
   :user's own per-effect param types (?start, ?end, ...) are supplied by
   the caller at compile time and merged with these -- see compile-doc!."
  {:age :double :progress :double})

(defn capabilities-for
  "user-types ({capability-key type}, an effect's own :inputs :spawn
   declaration) -> the full :capabilities map a scene doc compiles
   against, universal-capabilities merged in. Public (unlike universal-
   capabilities itself) so a caller that needs the SAME :capabilities
   value compile-doc! builds internally -- the node editor's scene mode,
   cn.li.ability.editor.check -- can get it without duplicating this
   merge or reaching into a private var."
  [user-types]
  (merge universal-capabilities user-types))

(defn compile-doc!
  "text -> IR. user-types: {capability-key type} for this effect's own
   :user-declared params (?start, ?end, ...), merged with the universal
   :age/:progress every scene gets."
  [text user-types]
  (compile/compile! (surface/parse text)
                    {:vocab vocab/nodes
                     :capabilities (capabilities-for user-types)
                     :fns {}}))

(def host
  {:command! (fn [cap args ^ExecutionFrame fr]
              (.add (.-actions fr) (assoc args :kind cap)))})

(defn compile-program
  "IR -> CompiledProgram. No :query!/:flush! needed -- scene sampling
   never reads a host, only constructs values."
  [ir]
  (emit/compile-program ir {:invoke-op ops/invoke :host host}))

;; ---------------------------------------------------------------------------
;; V3 document bridge
;; ---------------------------------------------------------------------------
;; Persisted VFX is map-shaped so an editor can address every node by :nid.
;; The scene executor still consumes the small, tested surface compiler. This
;; bridge renders V3 nodes to that surface data model only at compile time;
;; no source string is retained in a runtime instance.

(defn- ref-key-string [key]
  (if (keyword? key)
    (if-let [ns (namespace key)]
      (str ns "/" (name key))
      (name key))
    (str key)))

(defn- ref->surface-form [[scope key & path]]
  (let [prefix (case scope
                 :context "?"
                 :parameter "$"
                 :state "%"
                 :local ""
                 :input "?input/"
                 :module-input "?module/"
                 "?")
        base (symbol (str prefix (ref-key-string key)))]
    (reduce (fn [form field]
              (list (if (keyword? field) field (keyword (str field))) form))
            base
            path)))

(declare v3-form)

(defn- v3-map [value]
  (into {}
        (map (fn [[key child]] [key (v3-form child)]))
        value))

(defn- v3-form [value]
  (cond
    (and (map? value) (:ref value))
    (ref->surface-form (:ref value))

    (and (map? value) (:component value))
    (let [component (:component value)
          head (if-let [ns (namespace component)]
                 (symbol ns (name component))
                 (symbol (name component)))]
      (if (contains? value :inputs)
        (list head (v3-map (:inputs value)))
        (apply list head (map v3-form (:args value)))))

    (map? value) (v3-map value)
    (vector? value) (mapv v3-form value)
    (seq? value) (apply list (map v3-form value))
    :else value))

(defn- v3-statements [nodes]
  (mapv (fn [node]
          (cond
            (= :bind (:flow node))
            (list 'let (symbol (name (:name node))) (v3-form (:value node)))

            (= :when (:flow node))
            (apply list 'when (v3-form (:condition node))
                   (v3-statements (:do node)))

            (= :if (:flow node))
            (list 'if (v3-form (:condition node))
                  (vec (v3-statements (:then node)))
                  (vec (v3-statements (:else node))))

            (= :foreach (:flow node))
            (let [binding (if (:index-as node)
                            [(:as node) (:index-as node)]
                            (:as node))]
              (apply list 'each binding (v3-form (:collection node))
                     (v3-statements (:do node))))

            (= :repeat (:flow node))
            (apply list 'each 'i (list 'range (:count node))
                   (v3-statements (:do node)))

            (= :finish (:flow node))
            (list 'finish (v3-form (:result node)))

            (:component node) (v3-form node)
            :else (throw (ex-info "unsupported V3 VFX statement" {:node node}))))
        nodes))

(defn compile-v3-document!
  "Compile an :ac/vfx-v3 document's :system/:render stage using the scene
   vocabulary. The persisted document remains structured; the generated
   surface form exists only during compilation and is not runtime state."
  [document]
  (let [validate! (requiring-resolve 'cn.li.node.document/validate-document!)
        _ (validate! document)
        unsupported-stages (seq (keys (dissoc (or (:system document) {}) :render)))
        _ (when unsupported-stages
            (throw (ex-info "V3 VFX runtime only supports :system/:render"
                            {:id (:id document)
                             :unsupported-stages (vec unsupported-stages)})))
        input-types (into {}
                         (map (fn [[key spec]] [key (:type spec)]))
                         (:inputs document))
        surface-doc {:ability (:id document)
                     :do (v3-statements (get-in document [:system :render] []))}]
    (compile-program (compile-doc! (pr-str surface-doc) input-types))))

(defn sample!
  "Run `program` once against `input` ({:capabilities {...} ...}),
   returning the vector of ops this sample produced."
  [program input]
  (vec (.-actions (emit/dispatch! program :default (emit/new-frame program input)))))
