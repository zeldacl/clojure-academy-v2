(ns cn.li.combat.policy-primitives
  "v3 registration for combat-core's policy/emit primitives:
   :owner/patch, :session/patch (raw patch-action emitters -- kept per the
   mossy-wren plan's R2 audit as the plumbing :cost/spend/:score/mark/
   :cooldown/start build on, not for direct content authoring once R5
   migrates every ability off the old raw-path convention), :effect/vfx,
   :domain/event (raw emitters), :cost/spend/:score/mark/:cooldown/start
   (explicit-input versions -- NODE_LANGUAGE.md section 2.5: they used to
   read a document-level table implicitly via component context; now they
   receive that same already-resolved data as an ordinary :map input,
   supplied by the :ability/budget/:ability/progression/:ability/cooldown
   source nodes), and two pure helpers, :guard/value-in and
   :data/random-item.

   ctx contract these :impl fns rely on (extends host_primitives.clj's):
     {:ability-id  keyword
      :seed        long                          ; for :data/random-item
      :resources*  atom                          ; {resource-key -> amount}, mutable
      :emit-action! (fn [action-map] -> nil)
      :emit-vfx!    (fn [vfx-signal-map] -> nil)
      :emit-event!  (fn [event-map] -> nil)}

   :cost/spend no longer takes an :on-insufficient child -- that was the
   only :children port a 'primitive' had left, and it made :cost/spend a
   disguised control-flow node. It now just reports whether it could
   afford the spend via its own :insufficient? output; the caller composes
   the branch explicitly with :flow/branch, consistent with primitives
   never hiding control flow inside themselves.

   ADDITIVE ONLY at this revision -- see host_primitives.clj's docstring."
  (:require [cn.li.node.descriptor :as node]
            [cn.li.node.expr :as expr]))

(defn- patch-impl [patch-type]
  (fn [{:keys [entries]} ctx]
    ((:emit-action! ctx) {:type patch-type :entries entries})
    {}))

(defn- vfx-impl [{:keys [effect-id operation payload instance-key audience]} ctx]
  ((:emit-vfx! ctx) {:effect-id effect-id :operation (or operation :spawn) :payload payload
                     :instance-key instance-key :audience audience})
  {})

(defn- domain-event-impl [{:keys [event-type payload]} ctx]
  ((:emit-event! ctx) (merge {:type event-type :event-type event-type} payload {:payload payload}))
  {})

(defn- cost-spend-impl [{:keys [budget scale partial?]} ctx]
  (let [scale (double (or scale 1.0))
        amounts (into {} (map (fn [[k v]] [k (* (double v) scale)])) (:resources budget))
        available (or (some-> (:resources* ctx) deref) {})
        affordable? (every? (fn [[k amount]] (>= (double (or (get available k) 0.0)) (double amount))) amounts)
        spend (cond
                affordable? amounts
                partial? (into {} (map (fn [[k amount]]
                                         [k (min (double (or (get available k) 0.0)) (double amount))]))
                               amounts)
                :else {})]
    (when (:resources* ctx)
      (swap! (:resources* ctx)
             (fn [resources] (reduce-kv (fn [acc k amount] (update acc k (fnil - 0.0) amount)) resources spend))))
    (when (seq spend)
      ((:emit-action! ctx)
       {:type :owner-patch
        :entries (mapv (fn [[k amount]] {:path [:resources k] :mode :increment :value (- (double amount))}) spend)}))
    ;; :insufficient? tracks whether the FULL amount was paid, independent
    ;; of :partial? -- a partial payment still leaves the caller unable to
    ;; proceed as if the ability fully activated.
    {:insufficient? (not affordable?)}))

(defn- score-mark-impl [{:keys [progression weight]} ctx]
  (let [per-mark (double (or (:per-mark progression) 0.0))
        amount (* per-mark (double (or weight 1.0)))]
    ((:emit-action! ctx)
     {:type :owner-patch
      :entries [{:path [:ability-data :skill-exps (:ability-id ctx)] :mode :increment :value amount}]})
    {}))

(defn- cooldown-start-impl [{:keys [name cooldown]} ctx]
  (let [ticks (double (or (:ticks cooldown) 0))]
    ((:emit-action! ctx)
     {:type :owner-patch
      :entries [{:path [:cooldown-data (:ability-id ctx) name] :mode :assign :value ticks}]})
    {}))

(defn- guard-value-in-impl [{:keys [value one-of]} _ctx]
  {:result (boolean (contains? (set one-of) value))})

(defn- random-item-impl [{:keys [items]} ctx]
  (let [items (vec items)]
    {:item (when (seq items)
             (nth items (expr/bounded-int (long (or (:seed ctx) 0)) 0 (dec (count items)))))}))

(defn install!
  "Register every policy/emit/pure-helper primitive. Call once per registry
   lifetime, before node/freeze!."
  []
  (node/register-primitive!
   {:id :owner/patch :revision 1 :category :policy
    :doc "Emit a raw owner-state patch action. Not for direct content authoring -- only :cost/spend/:score/mark/:cooldown/start should reach for this."
    :inputs {:entries {:type [:list-of :map]}} :outputs {} :effects #{:mutate}
    :impl (patch-impl :owner-patch)})
  (node/register-primitive!
   {:id :session/patch :revision 1 :category :policy
    :doc "Emit a raw session-state patch action. Not for direct content authoring -- only :session/write should reach for this."
    :inputs {:entries {:type [:list-of :map]}} :outputs {} :effects #{:mutate}
    :impl (patch-impl :session-patch)})
  (node/register-primitive!
   {:id :effect/vfx :revision 1 :category :policy
    :doc "Emit a VFX signal."
    :inputs {:effect-id {:type :keyword} :operation {:type :keyword}
             :payload {:type :map} :instance-key {:type [:list-of :any] :default nil}
             :audience {:type :map :default nil}}
    :outputs {} :effects #{:emit}
    :impl vfx-impl})
  (node/register-primitive!
   {:id :domain/event :revision 1 :category :policy
    :doc "Emit a domain event."
    :inputs {:event-type {:type :keyword} :payload {:type :map}} :outputs {} :effects #{:emit}
    :impl domain-event-impl})
  (node/register-primitive!
   {:id :cost/spend :revision 1 :category :policy
    :doc "Spend a resource budget (from :ability/budget); reports whether the spend could not be fully afforded instead of taking an :on-insufficient child -- callers branch on :insufficient? explicitly."
    :inputs {:budget {:type :map} :scale {:type :double :default 1.0} :partial? {:type :boolean :default false}}
    :outputs {:insufficient? {:type :boolean}} :effects #{:mutate}
    :impl cost-spend-impl})
  (node/register-primitive!
   {:id :score/mark :revision 1 :category :policy
    :doc "Award skill-exp for one progression entry (from :ability/progression)."
    :inputs {:progression {:type :map} :weight {:type :double :default 1.0}} :outputs {} :effects #{:mutate}
    :impl score-mark-impl})
  (node/register-primitive!
   {:id :cooldown/start :revision 1 :category :policy
    :doc "Start a named cooldown (spec from :ability/cooldown)."
    :inputs {:name {:type :keyword} :cooldown {:type :map}} :outputs {} :effects #{:mutate}
    :impl cooldown-start-impl})
  (node/register-primitive!
   {:id :guard/value-in :revision 1 :category :guard
    :doc "Pure membership test: does :value equal one of :one-of."
    :inputs {:value {:type :any} :one-of {:type [:list-of :any]}}
    :outputs {:result {:type :boolean}} :effects #{:pure}
    :impl guard-value-in-impl})
  (node/register-primitive!
   {:id :data/random-item :revision 1 :category :flow
    :doc "Pick one deterministic random item from a list."
    :inputs {:items {:type [:list-of :any]}}
    :outputs {:item {:type :any}} :effects #{:pure}
    :impl random-item-impl}))
