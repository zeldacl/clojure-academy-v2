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
(def ^:private max-player-glyphs 32)

;; One descriptor table drives the client palette, pure analysis and server
;; validation. UI controls are advisory; player-spell packets are not trusted.
(def ^:private glyph-specs
  {:form/self {:kind :form :params {}
               :i18n "glyph.academy.form.self"}
   :form/touch {:kind :form :params
                {:range {:type :float :default 16.0 :min 1.0 :max 128.0}}
                :i18n "glyph.academy.form.touch"}
   :effect/damage {:kind :effect :params
                   {:amount {:type :float :default 2.0 :min 0.0 :max 20.0}}
                   :i18n "glyph.academy.effect.damage"}
   :effect/push {:kind :effect :params
                 {:strength {:type :float :default 1.0 :min 0.0 :max 8.0}}
                 :i18n "glyph.academy.effect.push"}
   :augment/amplify {:kind :augment :params {}
                     :i18n "glyph.academy.augment.amplify"}})
(def player-spell-complexity-cap
  "Conservative shared cap used by the client composer and server admission."
  20)

(defn player-glyph-specs
  "Return the descriptor table used by both the player UI and validation."
  []
  (into {} (map (fn [[glyph spec]] [glyph (assoc spec :glyph glyph)])) glyph-specs))

(defn- finite-number? [value]
  (and (number? value) (Double/isFinite (double value))))

(defn- normalize-param [glyph param-key value {:keys [type min max]}]
  (when-not (case type
             :int (integer? value)
             :float (finite-number? value)
             :string (string? value)
             :keyword (keyword? value)
             :any true
             false)
    (throw (ex-info "invalid player spell parameter"
                    {:code :invalid-param-type :glyph glyph :param param-key
                     :expected type :value value})))
  (when (and (number? value) min (< (double value) (double min)))
    (throw (ex-info "player spell parameter is below its minimum"
                    {:code :param-too-small :glyph glyph :param param-key :min min :value value})))
  (when (and (number? value) max (> (double value) (double max)))
    (throw (ex-info "player spell parameter exceeds its maximum"
                    {:code :param-too-large :glyph glyph :param param-key :max max :value value})))
  value)

(defn- normalize-glyph [glyph]
  (when-not (map? glyph)
    (throw (ex-info "player spell glyph must be a map" {:code :invalid-glyph :glyph glyph})))
  (let [glyph-id (:glyph glyph)
        spec (get glyph-specs glyph-id)
        declared (or (:params glyph) {})
        params-spec (:params spec)]
    (when-not spec
      (throw (ex-info "unknown player spell glyph" {:code :unknown-glyph :glyph glyph-id})))
    (when-not (map? declared)
      (throw (ex-info "player spell glyph params must be a map"
                      {:code :invalid-params :glyph glyph-id})))
    (when-let [unknown (seq (remove #(contains? params-spec %) (keys declared)))]
      (throw (ex-info "unknown player spell parameter"
                      {:code :unknown-param :glyph glyph-id :params (vec unknown)})))
    (assoc glyph :params
           (into {}
                 (keep (fn [[param-key param-spec]]
                         (when (or (contains? declared param-key)
                                   (contains? param-spec :default))
                           [param-key
                            (normalize-param glyph-id param-key
                                             (if (contains? declared param-key)
                                               (get declared param-key)
                                               (:default param-spec))
                                             param-spec)])))
                 params-spec))))

(defn- normalize-glyphs [glyphs]
  (when-not (vector? glyphs)
    (throw (ex-info "player spell glyphs must be a vector" {:code :invalid-glyph-vector})))
  (when (empty? glyphs)
    (throw (ex-info "player spell has no glyphs" {:code :empty-spell})))
  (when (> (count glyphs) max-player-glyphs)
    (throw (ex-info "player spell has too many glyphs"
                    {:code :too-many-glyphs :max max-player-glyphs :count (count glyphs)})))
  (let [normalized (mapv normalize-glyph glyphs)
        [form & tail] normalized]
    (when-not (= :form (:kind (get glyph-specs (:glyph form))))
      (throw (ex-info "player spell must start with a :form/* glyph"
                      {:code :form-required :glyph (:glyph form)})))
    (let [effect-count (count (filter #(= :effect (:kind (get glyph-specs (:glyph %)))) tail))
          augment-run (loop [remaining tail current 0 runs []]
                        (if-let [glyph (first remaining)]
                          (if (= :effect (:kind (get glyph-specs (:glyph glyph))))
                            (recur (next remaining) 0 runs)
                            (recur (next remaining) (inc current) (conj runs (inc current))))
                          runs))]
      (when (zero? effect-count)
        (throw (ex-info "player spell has no :effect/* glyph" {:code :effect-required})))
      (when (some #(> % 8) augment-run)
        (throw (ex-info "player spell has too many augments for one effect"
                        {:code :too-many-augments :max 8}))))
    normalized))

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

  (let [[form & tail] (normalize-glyphs glyphs)]

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

      :else {:ok true
              :complexity complexity
              :effects effects
              :host-commands host-commands
              :max-iterations max-iterations})))

(defn compile-and-admit
  "glyphs, complexity-cap -> the only path server-side code should ever
   use to turn a player's raw glyph submission into something
   dispatchable. {:ok true :complexity n :ir ir} on success; {:ok false
   :reject reason ...} otherwise -- callers must check :ok before
   touching :ir at all."
  [glyphs complexity-cap]
  (try
    (let [normalized (normalize-glyphs glyphs)
        text (desugar normalized)
        ir (run/compile-doc! text)
        verdict (admit ir complexity-cap)]
      (if (:ok verdict) (assoc verdict :ir ir :glyphs normalized) verdict))
    (catch clojure.lang.ExceptionInfo error
      {:ok false :reject :invalid-glyph :detail (ex-data error)})
    (catch Throwable error
      {:ok false :reject :invalid-glyph
       :detail {:code :compile-failure :message (.getMessage error)}})))

(defn analyze-player-spell
  "Validate and statically analyze a raw player spell without exposing IR."
  [glyphs complexity-cap]
  (try
    (dissoc (compile-and-admit glyphs complexity-cap) :ir)
    (catch clojure.lang.ExceptionInfo error
      {:ok false :reject :invalid-glyph :detail (ex-data error)})
    (catch Throwable error
      {:ok false :reject :invalid-glyph
       :detail {:code :compile-failure :message (.getMessage error)}})))

;; --- glyph-catalog: the composer's palette, derived not hand-maintained --
;;
;; A player editor's grey-out list must never drift from what admit
;; actually accepts. Rather than a second hand-authored {glyph -> effects}
;; table (which COULD silently diverge from desugar/effect-stmts' real
;; output), every entry here is measured by actually compiling a minimal
;; real spell through the identical desugar -> run/compile-doc! ->
;; cost/analyze path admit itself uses, then isolating one glyph's own
;; marginal contribution:
;;   - :form/self contributes {:effects #{} :cost 0} by construction --
;;     its own DSL ((let target ?caster/id)) is a single sigil read, and
;;     cost/analyze only ever charges :query/:action instructions (see
;;     that function's own docstring), so it is the natural zero-cost
;;     baseline every OTHER form's marginal cost is measured against:
;;     cost([form, :effect/damage]) - cost([:form/self, :effect/damage]).
;;   - an :effect/* glyph's own contribution is exactly cost([:form/self,
;;     effect]), :form/self having already been shown to add nothing.
;;   - an :augment/* glyph never adds a new instruction (amplify-
;;     multiplier bakes its scaling straight into the DECORATED effect's
;;     own numeric literal -- see that function's docstring), so its
;;     marginal contribution is {:effects #{} :cost 0} by the same
;;     "no new call, no new cost" reasoning, not measured by compiling
;;     (there is nothing a compile could isolate that direct reasoning
;;     does not already establish).

(defn- glyph-ids-of-kind [kind]
  (->> glyph-specs
       (filter (fn [[_ spec]] (= kind (:kind spec))))
       (map first)
       sort
       vec))

(defn- spell-cost [glyphs]
  (cost/analyze (run/compile-doc! (desugar glyphs)) vocab/nodes))

(defn- catalog-entry [kind glyph-kw {:keys [effects complexity]}]
  (merge (get glyph-specs glyph-kw)
         {:glyph glyph-kw
          :kind kind
          :effects effects
          :cost complexity
          :admissible? (set/subset? effects allowed-player-effects)}))

(defn glyph-catalog
  "-> a vector of {:glyph :kind (:form/:effect/:augment) :effects :cost
   :admissible?} for every glyph desugar/effect-stmts/form-stmts knows
   about. The editor's palette grey-out list should filter on
   :admissible?, reading the SAME allowed-player-effects admit itself
   checks against (see catalog-entry) -- there is no second copy of that
   allowlist to keep in sync."
  []
  (let [baseline (spell-cost [{:glyph :form/self} {:glyph :effect/damage}])
        form-entries
        (mapv (fn [glyph-kw]
                (if (= :form/self glyph-kw)
                  (catalog-entry :form glyph-kw {:effects #{} :complexity 0})
                  (let [total (spell-cost [{:glyph glyph-kw} {:glyph :effect/damage}])]
                    (catalog-entry :form glyph-kw
                                   {:effects (set/difference (:effects total) (:effects baseline))
                                    :complexity (- (:complexity total) (:complexity baseline))}))))
              (glyph-ids-of-kind :form))
        effect-entries
        (mapv (fn [glyph-kw] (catalog-entry :effect glyph-kw (spell-cost [{:glyph :form/self} {:glyph glyph-kw}])))
              (glyph-ids-of-kind :effect))
        augment-entries
        (mapv (fn [glyph-kw] (catalog-entry :augment glyph-kw {:effects #{} :complexity 0}))
              (glyph-ids-of-kind :augment))]
    (vec (concat form-entries effect-entries augment-entries))))
