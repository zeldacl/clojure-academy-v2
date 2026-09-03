(ns cn.li.combat.player
  "S7: player-composed spells. A player's spell is data stored on an
   item (an Ars Nouveau-style linear [form effect augment* ...] glyph
   vector), never a client-compiled program -- the client submits only
   the raw glyph vector (cn.li.mcmod.runtime.fixed-channel/encode-
   player-spell-submit), and the SERVER is the only place that ever
   turns it into something executable, via this namespace's `desugar`
   (glyphs -> surface DSL text, the exact same text shape every hand-
   authored ability already uses) -> cn.li.combat.run/compile-doc!
   (the exact same compiler) -> `admit` (a static cost/effect/budget
   gate, cn.li.node.cost/analyze) -> only then cn.li.combat.run/
   compile-program + dispatch!.

   No new VM, no eval, no client-supplied IR ever trusted: `desugar`
   only ever emits ordinary surface-DSL text strings via clojure.core/
   str, ~the same class of construction cn.li.node.surface/parse's own
   docstring already establishes has no eval surface at all~ (plain
   clojure.edn/read on the far side, in compile-doc!). A malicious/buggy
   glyph vector can make `desugar` throw or make `admit` reject; it can
   never make the server execute attacker-chosen host commands, since
   `admit` runs BEFORE compile-program/dispatch! ever sees the IR."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [cn.li.node.cost :as cost]
            [cn.li.combat.run :as run]
            [cn.li.combat.dsl-vocabulary :as vocab]))

;; :inventory-write (item duplication/destruction) and :damage-context-
;; write (the in-flight damage-policy pipeline, an internal mechanism
;; never meant to be reachable from a direct player cast) are the only
;; two effect tags this vocabulary actually has that a player spell must
;; never carry. :world-write and :owner-write are both required for even
;; the most basic spell (dealing damage is :world-write; cost/spend and
;; cooldown/start -- without which a spell could never cost anything or
;; ever go on cooldown -- are :owner-write) -- see cn.li.combat.dsl-
;; vocabulary's own :effects tags for why the taxonomy cannot draw a
;; finer line than this yet (":world-write" alone covers both "damage a
;; mob" and "break a block"; a real per-node distinction would need a
;; richer effects vocabulary than exists today, a follow-up, not a
;; blocker for proving the admit MECHANISM works).
(def ^:private allowed-player-effects #{:world-read :world-write :owner-read :owner-write})
(def ^:private max-player-host-commands 64)
(def ^:private max-player-iterations 256)

(defn- amplify-multiplier
  "Ars Nouveau's own augment math: each stacked :augment/amplify adds a
   flat +50% to whatever numeric field the decorated effect scales by."
  [augments]
  (+ 1.0 (* 0.5 (count (filter #(= :augment/amplify (:glyph %)) augments)))))

;; --- forms: pick the spell's :target local -----------------------------

(defmulti ^:private form-stmts
  "glyph -> {:stmts [dsl-statement-text...] :target local-name-string
   :guard? bool}. :stmts always binds a local literally named \"target\"
   -- :effect-stmts below is written against that fixed name, the same
   way every :defn composite in cn.li.combat.lib is written against its
   own fixed :params names. :guard? is whether `target` can genuinely be
   nil at runtime (a raycast miss) and so needs a (when target ...) wrap
   around the effect statements -- :form/self's ?caster/id is a real
   :entity-ref capability, always present, and node-core's `when` only
   accepts :boolean/:any-typed conditions (a concrete :entity-ref is
   neither), so wrapping it in a needless guard would be a compile
   error, not just redundant."
  :glyph)

(defmethod form-stmts :form/self [_]
  {:stmts ["(let target ?caster/id)"] :target "target" :guard? false})

(defmethod form-stmts :form/touch [{:keys [params]}]
  (let [range (double (or (:range params) 16.0))]
    {:stmts [(format (str "(let hit (target/raycast {:origin ?caster/eye :direction ?caster/aim "
                          ":distance %s :include-entities? true :living-only? true}))")
                     range)
             "(let target (:entity-id hit))"]
     :target "target" :guard? true}))

(defmethod form-stmts :default [glyph]
  (throw (ex-info "unknown player spell form glyph" {:glyph glyph})))

;; --- effects: do something to :target -----------------------------------

(defmulti ^:private effect-stmts
  "(effect-glyph, its own augment glyphs) -> [dsl-statement-text...]."
  (fn [glyph _augments] (:glyph glyph)))

(defmethod effect-stmts :effect/damage [{:keys [params]} augments]
  (let [amount (* (double (or (:amount params) 2.0)) (amplify-multiplier augments))]
    [(format "(combat/damage {:target target :amount %s})" amount)]))

(defmethod effect-stmts :effect/push [{:keys [params]} augments]
  (let [strength (* (double (or (:strength params) 1.0)) (amplify-multiplier augments))]
    [(format "(combat/impulse {:target target :vector (vec3/scale ?caster/aim %s)})" strength)]))

(defmethod effect-stmts :default [glyph _augments]
  (throw (ex-info "unknown player spell effect glyph" {:glyph glyph})))

(def ^:private known-augment-glyphs #{:augment/amplify})

(defn- group-by-effect
  "tail (everything after the form glyph) -> [[effect-glyph [augment-
   glyph...]] ...]. Each augment decorates the NEAREST PRECEDING effect
   -- Ars Nouveau's own semantics, never the form (see this namespace's
   `desugar` docstring)."
  [tail]
  (reduce (fn [groups glyph]
            (let [glyph-ns (namespace (:glyph glyph))]
              (cond
                (= "effect" glyph-ns) (conj groups [glyph []])
                (= "augment" glyph-ns)
                (do (when-not (contains? known-augment-glyphs (:glyph glyph))
                      (throw (ex-info "unknown player spell augment glyph" {:glyph glyph})))
                    (when (empty? groups)
                      (throw (ex-info "augment glyph with no preceding effect" {:glyph glyph})))
                    (update groups (dec (count groups))
                            (fn [[effect augs]] [effect (conj augs glyph)])))
                :else (throw (ex-info "unknown player spell glyph namespace" {:glyph glyph})))))
          [] tail))

(defn desugar
  "glyphs: [{:glyph kw :params {...}} ...] -> surface-DSL text (the same
   shape cn.li.combat.run/compile-doc! already takes for every hand-
   authored ability -- there is no separate player-spell compiler, only
   a different TEXT GENERATOR feeding the identical pipeline). The FIRST
   glyph must be a :form/* (picks the target); every glyph after that is
   either an :effect/* (acts on that target) or an :augment/* (decorates
   the nearest preceding effect)."
  [glyphs]
  (when (empty? glyphs) (throw (ex-info "player spell has no glyphs" {:glyphs glyphs})))
  (let [[form & tail] glyphs]
    (when-not (= "form" (namespace (:glyph form)))
      (throw (ex-info "player spell must start with a :form/* glyph" {:glyphs glyphs})))
    (let [{:keys [stmts target guard?]} (form-stmts form)
          effect-groups (group-by-effect tail)]
      (when (empty? effect-groups)
        (throw (ex-info "player spell has no :effect/* glyph" {:glyphs glyphs})))
      (let [effect-lines (mapcat (fn [[effect augs]] (effect-stmts effect augs)) effect-groups)
            effect-block (if guard?
                          (str "(when " target "\n        "
                               (str/join "\n        " effect-lines) ")")
                          (str/join "\n      " effect-lines))]
        (str "{:ability :player/spell :activation :instant\n"
             " :do [" (str/join "\n      " stmts) "\n"
             "      " effect-block "\n"
             "      (finish {:outcome :performed})]}")))))

;; --- admit: server-authoritative gate, before anything is dispatchable --

(defn admit
  "ir (already compiled by cn.li.combat.run/compile-doc!), complexity-cap
   (caller-supplied, e.g. derived from the submitting player's own
   progression -- this namespace has no player-progression dependency of
   its own) -> {:ok true :complexity n} or {:ok false :reject reason
   ...}. Every player spell MUST pass through here before compile-
   program/dispatch! ever runs against it; nothing here is optional or
   advisory."
  [ir complexity-cap]
  (let [{:keys [complexity effects host-commands max-iterations]} (cost/analyze ir vocab/nodes)
        forbidden (set/difference effects allowed-player-effects)]
    (cond
      (> complexity (long complexity-cap))
      {:ok false :reject :over-complexity :complexity complexity :cap complexity-cap}

      (seq forbidden)
      {:ok false :reject :forbidden-effect :effects forbidden}

      (or (> host-commands max-player-host-commands)
          (nil? max-iterations) (> max-iterations max-player-iterations))
      {:ok false :reject :over-budget :host-commands host-commands :max-iterations max-iterations}

      :else {:ok true :complexity complexity})))

(defn compile-and-admit
  "glyphs, complexity-cap -> the only path server-side code should ever
   use to turn a player's raw glyph submission into something
   dispatchable. {:ok true :complexity n :ir ir} on success; {:ok false
   :reject reason ...} otherwise -- callers must check :ok before
   touching :ir at all."
  [glyphs complexity-cap]
  (let [text (desugar glyphs)
        ir (run/compile-doc! text)
        verdict (admit ir complexity-cap)]
    (if (:ok verdict) (assoc verdict :ir ir) verdict)))
