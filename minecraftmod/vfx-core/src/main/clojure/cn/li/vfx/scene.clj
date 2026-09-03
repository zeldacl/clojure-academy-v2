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

(defn compile-doc!
  "text -> IR. user-types: {capability-key type} for this effect's own
   :user-declared params (?start, ?end, ...), merged with the universal
   :age/:progress every scene gets."
  [text user-types]
  (compile/compile! (surface/parse text)
                    {:vocab vocab/nodes
                     :capabilities (merge universal-capabilities user-types)
                     :fns {}}))

(def host
  {:command! (fn [cap args ^ExecutionFrame fr]
              (.add (.-actions fr) (assoc args :kind cap)))})

(defn compile-program
  "IR -> CompiledProgram. No :query!/:flush! needed -- scene sampling
   never reads a host, only constructs values."
  [ir]
  (emit/compile-program ir {:invoke-op ops/invoke :host host}))

(defn sample!
  "Run `program` once against `input` ({:capabilities {...} ...}),
   returning the vector of ops this sample produced."
  [program input]
  (vec (.-actions (emit/dispatch! program :default (emit/new-frame program input)))))
