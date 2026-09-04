(ns cn.li.ability.editor.check
  "Diagnostics + static cost readout for the node editor -- a thin
   wrapper, not a second compiler: both delegate straight to cn.li.node.
   compile's existing :collect mode and cn.li.node.cost/analyze, the same
   functions the real build-time compile path uses. This namespace's
   only job is normalizing the raw :form (cn.li.node.surface/read-doc's
   output, cn.li.ability.editor.document's shape) into what compile-
   program expects, and shaping the result for editor consumption."
  (:require [cn.li.node.compile :as compile]
            [cn.li.node.surface :as surface]
            [cn.li.node.cost :as cost]))

(defn- compile-collect [raw-form opts]
  (compile/compile-program (surface/normalize raw-form) opts :collect))

(defn diagnostics
  "raw-form, opts ({:vocab :capabilities :tunable-types :state-types
   :fns}) -> the full diagnostics vector (never throws, :collect mode
   surfaces every error in one pass -- see cn.li.node.compile/report!'s
   own docstring). Each entry carries :nid when the offending form was
   stamped (cn.li.node.nid/stamp) or hand-annotated, letting the editor
   jump straight to and highlight the failing node; :nid is nil for an
   unstamped form, same fallback nid-for! already has everywhere else."
  [raw-form opts]
  (:diagnostics (compile-collect raw-form opts)))

(defn cost-summary
  "raw-form, opts -> {:complexity :host-commands :effects
   :max-iterations} (cn.li.node.cost/analyze's shape) if raw-form
   compiles cleanly, else nil -- a broken graph has no meaningful cost
   reading, and the editor's diagnostics panel is where a compile error
   belongs, not a stale/misleading cost number."
  [raw-form opts]
  (let [{:keys [ir diagnostics]} (compile-collect raw-form opts)]
    (when (empty? diagnostics)
      (cost/analyze ir (:vocab opts)))))

(defn ok?
  "raw-form, opts -> true if raw-form compiles with zero diagnostics.
   Convenience for a caller that only needs a boolean (e.g. gating
   whether 'export to source tree' is enabled) without the full
   diagnostics vector."
  [raw-form opts]
  (empty? (diagnostics raw-form opts)))
