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
            [cn.li.node.types :as types]
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

;; --- wire type check (editor-side EARLY FEEDBACK, never a defence) --------

(defn wire-type-error
  "from-type, to-type, to-role -> nil when the connection is allowed, else
   {:code :incompatible-pin-types :message ...}.

   A nil type means \"not statically known here\" (a :local-get source, an
   unrecognised port) and is always allowed: the editor must never reject a
   wire the compiler would accept.

   THE JUDGEMENT IS NOT ITS OWN. It is cn.li.node.types/assignable? and
   condition-type? -- the same two functions cn.li.node.compile applies,
   not an editor-side reimplementation of them. That matters more than it
   looks: V4 graphs can be generated without ever opening the editor
   (scripts/batch*_promote.py already does), so an editor-only rule
   protects nothing, and an editor rule that DISAGREES with the compiler is
   worse than none -- it would either block a legal graph or bless an
   illegal one. Whatever this rejects, compiling rejects too, by
   construction rather than by convention.

   `to-role` is :condition for a branch's test input (where the language's
   rule is truthiness, not :boolean -- see condition-type?) and :value
   everywhere else."
  [from-type to-type to-role]
  ;; A condition has no declared input type to compare against -- the rule
  ;; is entirely about the SOURCE -- so it only needs from-type.
  (when (if (= :condition to-role) from-type (and from-type to-type))
    (if (= :condition to-role)
      (when-not (types/condition-type? from-type)
        {:code :incompatible-pin-types
         :message (str "a " (pr-str from-type)
                       " cannot be a condition: it can never be nil, so the branch"
                       " would always be taken")})
      (when-not (types/assignable? from-type to-type)
        {:code :incompatible-pin-types
         :message (str "cannot connect " (pr-str from-type)
                       " to an input declared " (pr-str to-type))}))))

;; vfx! field validation used to live here as `unknown-vfx-fields` +
;; `vfx-field-names`, above a comment arguing that compile.clj
;; "deliberately does NOT" check a vfx! call's field names and that only
;; the editor could, because only the editor had the content-level effect
;; catalog. That stopped being true: callers now pass the catalog into
;; compile as :effect-inputs (cn.li.ac.ability.skills-catalog-v4/
;; effect-input-specs, and the editor's own opts), and cn.li.node.types
;; reports :unknown-vfx-field / :missing-vfx-input / :nil-vfx-input /
;; :vfx-payload-shape from it at compile time.
;;
;; Both functions are therefore deleted rather than kept in sync. A rule
;; with two implementations is worse than the rule having one home: V4
;; graphs can be produced without ever opening the editor, so an
;; editor-only check protects nothing, and a drifting copy reports
;; differently from the compiler for the same graph.
