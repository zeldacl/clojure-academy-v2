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
            [cn.li.node.graph-compile :as graph-compile]
            [cn.li.vfx.dsl-vocabulary :as vocab]
            [cn.li.mcmod.runtime.effect-emit :as emit])
  (:import [cn.li.mcmod.runtime.effect CompiledProgram ExecutionFrame]))

(def ^:private universal-capabilities
  "Present on every scene sample regardless of which effect declared what.
   :user's own per-effect param types (?start, ?end, ...) are supplied by
   the caller at compile time and merged with these -- see compile-doc!."
  {:age :double :progress :double :seed :long :source-player-id :any})

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
   never reads a host, only constructs values. :prim-ops ops/prim-table
   gives effect-emit a primitive fast path for :math/* and :long/*
   :pure instructions -- see that table's own docstring."
  [ir]
  (emit/compile-program ir {:invoke-op ops/invoke :prim-ops ops/prim-table :host host}))

;; ---------------------------------------------------------------------------
(defn compile-v4-document!
  "Compile persisted :ac/vfx-v4 graph documents."
  [document]
  (let [input-types (into {} (map (fn [[k spec]] [k (:type spec)]) (or (:inputs document) (:parameters document))))
        {:keys [ir diagnostics]} (graph-compile/compile-vfx! document {:vocab vocab/nodes :capabilities (capabilities-for input-types) :fns {}} :throw)]
    (when (seq diagnostics) (throw (ex-info "V4 VFX graph compilation failed" {:diagnostics diagnostics})))
    (compile-program ir)))
(defn- sample-entry
  "V4 graph VFX compiles its sole graph as entry `:render` (`:vfx/render`
   trigger). Surface-DSL scenes (`:do`) still compile as `:default`. Prefer
   `:render`, then `:default`; never guess an unrelated entry name."
  [^CompiledProgram program]
  (let [entries ^java.util.Map (.-entries program)]
    (cond
      (.containsKey entries :render) :render
      (.containsKey entries :default) :default
      :else (throw (ex-info "VFX program has no :render or :default sample entry"
                            {:known (vec (.keySet entries))})))))

(defn sample!
  "Run `program` once against `input` ({:capabilities {...} ...}),
   returning the vector of ops this sample produced."
  [program input]
  (vec (.-actions (emit/dispatch! program (sample-entry program)
                                  (emit/new-frame program input)))))

(defn sample-into!
  "As sample!, but reuses `frame` (see cn.li.mcmod.runtime.effect-emit/
   reset-frame!) instead of allocating a fresh one every call -- the
   caller (cn.li.vfx.runtime, one frame cached per effect-id) owns frame's
   whole lifecycle and must not call this concurrently for the same
   frame. `frame` must already be sized for `program` (built via new-frame
   against the SAME program, or one with identical register counts)."
  [program frame input]
  (vec (.-actions (emit/dispatch! program (sample-entry program)
                                  (emit/reset-frame! frame input)))))





