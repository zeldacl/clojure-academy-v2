(ns cn.li.combat.final-engine
  "Authoritative graph execution over neutral mcmod host ports.")
(require '[cn.li.mcmod.runtime.host :as host]
         '[cn.li.node.contracts :as contracts]
         '[cn.li.node.expr :as expr]
         '[cn.li.node.kernel :as kernel]
         '[cn.li.combat.final-compiler :as compiler])

(kernel/defresolver resolve-value context
  {:scopes {:frame (:frame context)
            :input (get-in (:frame context) [:input])
            :local (:locals context)
            :state (:ability-state context)}
   :local :local
   :seed (let [seed* (:seed* context)]
           (if seed* (swap! seed* expr/next-seed) (long (:seed (:frame context)))))
   :extras (get-in context [:frame :extra-ops])
   :coll #{:map :vector :set}})

(defn create-engine [{:keys [host state-provider commit-state! ability-state-provider commit-ability-state!]}]
  (when-not (map? host) (throw (ex-info "final combat engine requires host" {})))
  (when-not (ifn? state-provider) (throw (ex-info "final combat engine requires state-provider" {})))
  (when-not (ifn? commit-state!) (throw (ex-info "final combat engine requires commit-state!" {})))
  {:host host :state-provider state-provider :commit-state! commit-state!
   :ability-state-provider (or ability-state-provider (fn [_] {}))
   :commit-ability-state! (or commit-ability-state! (fn [_ _] nil))
   :next-command (atom 0)})

(defn- command-id [engine path]
  (let [n (swap! (:next-command engine) inc)] [:combat (vec path) n]))
(declare run-node merge-action-results normalize-vfx-signal bind-command-results)
(defn- flush-command-prefix [engine context]
  (if (seq (:commands context))
    (let [result (host/execute! (:host engine) (:commands context)
                                {:frame (:frame context) :barrier? true})]
      (if (:ok? result)
        (-> context
            (assoc :commands [])
            (update :barriers (fnil conj []) result)
            (merge-action-results result)
            (bind-command-results result))
        (assoc context :host-error result)))
    context))
(defn- merge-action-results
  "Fold neutral action return values into the graph outbox.

   Action handlers are allowed to return domain observations (for example an
   AC mark action can return a VFX signal).  The host owns invocation, while
   Combat Core owns the explicit outbox projection; no Minecraft value crosses
   this boundary."
  [context host-result]
  (reduce (fn [ctx {:keys [id capability value] :as action-result}]
            (let [ctx (update ctx :action-results (fnil conj [])
                              (assoc action-result :id id :capability capability))]
              (if-not (map? value)
                ctx
                (cond-> ctx
                  (seq (:vfx-signals value))
                  (update-in [:outbox :vfx] into
                             (mapv #(normalize-vfx-signal % context
                                                           [:action id])
                                   (:vfx-signals value)))
                  (seq (:feedback value))
                  (update-in [:outbox :feedback] into
                             (if (vector? (:feedback value)) (:feedback value) [(:feedback value)]))
                  (seq (:events value))
                  (update-in [:outbox :events] into
                             (if (vector? (:events value)) (:events value) [(:events value)]))))))
          context (:results host-result)))
(defn- bind-result [context bind value]
  (if (map? bind)
    (reduce-kv (fn [ctx output local]
                 (assoc-in ctx [:locals local]
                           (if (and (map? value) (contains? value output))
                             (get value output)
                             value)))
               context bind)
    (assoc-in context [:locals bind] value)))

(defn- bind-command-results
  "Bind outputs returned by already-applied action commands.

   Action nodes are normally batched for one host transaction. A node may
   opt into :barrier? when a following graph step needs its result (for
   example the UUID returned by :entity/spawn); the command id keeps this
   binding exact without querying by owner/type or leaking platform objects."
  [context host-result]
  (reduce (fn [ctx {:keys [id value]}]
            (if-let [bind (get-in ctx [:command-binds id])]
              (-> (bind-result ctx bind value)
                  (update :command-binds dissoc id))
              ctx))
          context
          (:results host-result)))
(defn- node-bind [node]
  ;; Final EDN uses :bind for explicit port maps and :result for the compact
  ;; query form. Both are final ABI, and neither may disappear at runtime.
  (or (:bind node) (:result node)))

(def ^:private capability-routes
  {:combat/damage :entity/damage
   :combat/status :entity/status
   :combat/impulse :entity/impulse
   :world/sound :world/sound
   :world/lightning :world/lightning
   :world/explosion :world/explosion
   :kernel/trace-beam :kernel/trace-beam})

(def ^:private query-capabilities
  {:target/raycast :raycast
   :target/raycast-fan :raycast
   :target/entities :entity/select
   :target/blocks :block/select
   :target/entity-snapshot :entity/snapshot
   :target/item-held :item/held
   :target/saved-location :saved-location
   :target/resolve-destination :raycast
   :target/block-placement :raycast
   :target/directional-destination-query :raycast
   :owner/snapshot :owner/snapshot
   :energy/target :energy/target

   :kernel/terrain-wave-plan :kernel/terrain-wave-plan})

(def ^:private query-kinds
  {:target/raycast-fan :raycast-fan
   :target/resolve-destination :resolve-destination
   :target/block-placement :block-placement
   :target/directional-destination-query :directional-destination
   :target/raycast :raycast
   :kernel/trace-beam :kernel/trace-beam})

(defn capability-matrix
  "Static execution ABI used by audits and editor tooling.

   Keys are final graph component ids; values are the neutral host capability
   requested from the explicit descriptor route. AC-owned ports (for example :energy/target) are
   intentionally visible here even though their handler is installed by AC at
   the composition root rather than by combat-core."
  []
  {:queries query-capabilities
   :actions capability-routes})

(defn- action-args [node context]
  (let [structural #{:component :kind :bind :capability :operation :on-fail :guards
                     :reservations :barrier? :body :then :else :steps :start :pulse :release :abort}]
    (resolve-value (or (:args node) (apply dissoc node structural)) context)))

(defn- caster-capability-values
  "Expose the neutral capability table through the short names used by the
   :ability/caster source node.  Final input deliberately stores only
   namespaced ports (:caster/eye, :world/id, :charge/ticks, ...); returning
   that map directly makes every legacy-looking caster binding resolve to nil.
   The aliases are closed and deterministic, while the namespaced keys remain
   available for expressions that use the explicit capability ABI."
  [input]
  (let [capabilities (or (:capabilities input) {})
        aliases {:eye (:caster/eye capabilities)
                 :aim (:caster/aim capabilities)
                 :body (:caster/body capabilities)
                 :id (:caster/id capabilities)
                 :creative? (:caster/creative? capabilities)
                 :world-id (:world/id capabilities)
                 :charge-ticks (:charge/ticks capabilities)
                 :mastery (:progression/mastery capabilities)
                 :level (:progression/level capabilities)
                 :seed (:rng/seed capabilities)}]
    (merge capabilities aliases)))
(defn- source-value [node context]
  (let [input (:input (:frame context))
        name (:name node)]
    (case (:component node)
      :ability/caster (caster-capability-values input)
      :ability/tunable (get-in input [:tunables name])
      :ability/budget (get-in input [:budgets name])
      :ability/progression (get-in input [:progression name])
      :ability/cooldown (get-in input [:cooldowns name])
      :ability/invariant (get-in input [:invariants name])
      :ability/context (get-in input [:context name])
      :state/read (get-in (:ability-state context) [(:key node)])
      :data/bind (resolve-value (:value node) context)
      (resolve-value (:value node) context))))

(defn- bind-state! [context node]
  (let [key (:key node)
        value (resolve-value (:value node) context)
        ability-state (assoc-in (:ability-state context) [key] value)
        patch {:path [key] :mode :assign :value value}]
    (-> context
        (assoc :ability-state ability-state)
        (update :ability-state-patches (fnil conj []) patch))))

(defn- contains-in?
  [m path]
  (loop [value m ks (seq path)]
    (if-let [k (first ks)]
      (when (and (map? value) (contains? value k))
        (recur (get value k) (next ks)))
      true)))

(defn- run-once
  [engine node context path]
  (let [storage-path (vec (or (:storage-path node) [:once-complete?]))
        strategy (or (:strategy node) (when (:key node) :last-key) :boolean)
        key (when (contains? node :key) (resolve-value (:key node) context))
        present? (contains-in? (:ability-state context) storage-path)
        previous (get-in (:ability-state context) storage-path)
        first? (case strategy
                 :last-key (or (not present?) (not= previous key))
                 :set (not (contains? (if (set? previous) previous #{}) key))
                 :boolean (not present?)
                 (throw (ex-info "unsupported flow/once strategy"
                                 {:strategy strategy :path path})))
        next-storage (case strategy
                       :last-key key
                       :set (conj (if (set? previous) previous #{}) key)
                       true)
        context (-> context
                    (assoc-in [:ability-state] (assoc-in (:ability-state context) storage-path next-storage))
                    (update :ability-state-patches (fnil conj [])
                            {:path storage-path :mode :assign :value next-storage}))
        child (if first? (or (:on-first node) (:body node)) (:body node))]
    (if child
      (run-node engine child context (conj path (if first? :on-first :body)))
      context)))

(defn- spend-budget [engine context node path]
  "Apply a neutral resource budget with the complete final ABI semantics.

   `:scale` is evaluated once for the whole budget, `:partial?` allows each
   resource to spend only what is available (used by non-blocking defensive
   policies), and `:on-insufficient` is an explicit flow arm.  The old
   implementation silently ignored all three fields, which made a graph
   compile while taking a different branch at runtime."
  (let [owner (or (:owner node) (:owner (:frame context)))
        budget (resolve-value (:budget node) context)
        resources (or (:resources budget) budget {})
        scale (double (or (resolve-value (:scale node) context) 1.0))
        scale (max 0.0 (if (Double/isFinite scale) scale 0.0))
        required (into {}
                       (map (fn [[resource amount]]
                              [resource (* scale (double (or (resolve-value amount context) 0.0)))])
                            resources))
        state (contracts/owner-state (:txn context) owner)
        available (into {}
                       (map (fn [[resource amount]]
                              [resource (max 0.0 (double (or (get-in state [:resources resource]) 0.0)))])
                            required))
        sufficient? (every? (fn [[resource amount]]
                             (>= (get available resource 0.0) amount))
                           required)
        partial? (true? (:partial? node))
        spend (if sufficient?
                required
                (if partial?
                  (into {} (map (fn [[resource amount]]
                                  [resource (min amount (get available resource 0.0))])
                                required))
                  {}))
        insufficient? (not sufficient?)
        txn (reduce (fn [txn [resource amount]]
                      (if (pos? amount)
                        (contracts/update-owner-in txn owner [:resources resource]
                                                   #(- (double (or % 0.0)) amount))
                        txn))
                    (:txn context) spend)
        context (bind-result (assoc context :txn txn) (:bind node) insufficient?)]
    (if (and insufficient? (not partial?) (:on-insufficient node))
      (run-node engine (:on-insufficient node) context (conj path :on-insufficient))
      context)))

(defn- normalize-vfx-signal [value context path]
  (let [frame (:frame context)
        input (:input frame)
        op (or (:op value) (:operation value) :spawn)
        activation-seq (or (:intent-id input) (:server-tick input)
                           (:tick frame) 0)
        generated-key [(:ability-id frame) (vec path) activation-seq]
        event-seq (long (or (:event-seq value)
                            (+ (* 1000000 (long (or (:tick frame) 0)))
                               (swap! (:vfx-order* context) inc))))]
    (assoc value
           :op op
           :effect-id (or (:effect-id value) (:effect value))
           :owner (or (:owner value) (:owner frame))
           :world-id (or (:world-id value) (:world frame))
           :instance-key (or (:instance-key value) generated-key)
           :event-seq event-seq
           :params (or (:params value) (:payload value) {}))))

(defn- normalize-vfx [node context path]
  "Lower a graph VFX node to the closed wire ABI.

   Content may omit keys for transient one-shots, but the network contract
   cannot: the client uses the key to keep concurrent instances separate and
   the sequence to reject replayed/out-of-order updates.  Generate both from
   the immutable graph path and activation/tick identity at this one boundary;
   authored session effects may still provide their own stable key."
  (normalize-vfx-signal (resolve-value (dissoc node :component :kind) context)
                        context path))

(defn- emit [context kind value]
  (update context :outbox contracts/outbox kind value))

(defn- run-sequence [engine steps context path]
  (loop [remaining (seq (map-indexed vector steps))
         ctx context]
    ;; `flow/finish` and `flow/control` are control-flow nodes, not merely
    ;; annotations. Once either node has raised a signal, do not execute
    ;; later siblings in this sequence. This makes an insufficient-resource
    ;; or invalid-target arm authoritative instead of falling through to a
    ;; damage or teleport action later in the same graph.
    (if (or (nil? (seq remaining))
            (:halt? ctx)
            (:control-signal ctx))
      ctx
      (let [[index child] (first remaining)]
        (recur (next remaining)
               (run-node engine child ctx (conj path index)))))))
(defn- run-node [engine node context path]
  (let [component (:component node)]
    (case component
      :flow/sequence (run-sequence engine (:steps node) context path)
      :flow/phases
      (let [input (:input (:frame context))
            event (:event input)
            phase (or (:phase input) (:action input) (when event :event) :start)
            phase-node (or (when event (get-in node [:events event]))
                           (get node phase))]
        (if phase-node
          (run-node engine phase-node context (conj path phase))
          context))
      :flow/finish (cond-> (-> context
                               (assoc-in [:locals :outcome] (:outcome node))
                               (assoc :halt? true))
                     (:next-phase node) (assoc :next-phase (:next-phase node))
                     (:finish-ability? node) (assoc :finish-ability? true))
      :finalize (cond-> (-> context
                            (assoc-in [:locals :outcome] (:outcome node))
                            (assoc :halt? true))
                     (:next-phase node) (assoc :next-phase (:next-phase node))
                     (:finish-ability? node) (assoc :finish-ability? true))
      :flow/once (run-once engine node context path)
      :flow/branch (if-let [selected (if (resolve-value (:when node) context) (:then node) (:else node))]
                      (run-node engine selected context (conj path :branch))
                      context)
      :flow/foreach
      (let [items (vec (or (resolve-value (:items node) context) []))
            limit (min (count items) (long (or (:limit node) (:max-iteration contracts/budgets))))
            as (:as node)
            index-as (:index-as node)]
        (loop [index 0 ctx context]
          (if (>= index limit)
            ctx
            (let [ctx* (assoc-in ctx [:locals as] (nth items index))
                  ctx* (if index-as (assoc-in ctx* [:locals index-as] index) ctx*)
                  body-result (run-node engine (:body node) ctx* (conj path index))]
              (if (:halt? body-result)
                body-result
                (recur (inc index)
                       (if (= :skip-item (:control-signal body-result))
                         (dissoc body-result :control-signal)
                         body-result)))))))
      :flow/after
      (update context :scheduled conj {:tick (+ (long (:tick (:frame context))) (long (or (:delay node) 1))) :node (:body node) :path path})
      :graph/input (bind-result context (node-bind node) (get-in (:input (:frame context)) (:path node)))
      :graph/output (emit context :feedback (resolve-value (:value node) context))
      :feedback/emit (emit context :feedback (resolve-value (:event node) context))
      :domain/event
      (emit context :events
            {:type (:event-type node)
             :owner (or (:owner node) (:owner (:frame context)))
             :ability-id (:ability-id (:frame context))
             :payload (resolve-value (:payload node) context)})
      :resource/try-spend
      (let [owner (or (:owner node) (:owner (:frame context)))
            amount (double (resolve-value (:amount node) context))
            resource (:resource node)
            current (double (or (get-in (contracts/owner-state (:txn context) owner) [:resources resource]) 0.0))]
        (if (>= current amount)
          (-> context
              (assoc :txn (contracts/update-owner-in (:txn context) owner [:resources resource] #(- % amount)))
              (assoc-in [:locals (:bind node)] true))
          (assoc-in context [:locals (:bind node)] false)))
      :data/bind (bind-result context (:to node) (resolve-value (:value node) context))
      :state/write (bind-state! context node)
      :cost/spend (spend-budget engine context node path)
      :cooldown/start
      (let [owner (or (:owner node) (:owner (:frame context)))
            ability-id (:ability-id (:frame context))
            name (:name node)
            value (resolve-value (or (:cooldown node) (:value node) {}) context)
            ticks (long (or (:ticks value) value 0))]
        (when-not ability-id
          (throw (ex-info "cooldown/start requires ability-id in execution frame"
                          {:owner owner :name name})))
        (assoc-in context [:txn]
                  (contracts/assoc-owner-in (:txn context) owner
                                            [:cooldowns [ability-id name]]
                                            {:ticks ticks :max ticks})))
      :progression/mark
      (let [owner (or (:owner node) (:owner (:frame context)))
            progression (resolve-value (or (:progression node) (:value node) {}) context)]
        (emit context :events {:type :progression/mark :owner owner
                               :ability-id (:ability-id (:frame context))
                               :progression progression}))
      :score/mark
      (let [owner (or (:owner node) (:owner (:frame context)))
            score (resolve-value (dissoc node :component :kind) context)]
        (emit context :events (assoc score :type :score/mark :owner owner
                                     :ability-id (:ability-id (:frame context)))))
      :resource/enforce-floor
      (update context :commands conj
              (contracts/host-command
               {:id (command-id engine path)
                :capability :resource/enforce-floor
                :owner (:owner (:frame context))
                :world-id (:world (:frame context))
                :args {:resource (:resource node)
                       :minimum (double (resolve-value (:minimum node) context))}}))
      :flow/control (if-let [child (or (:body node) (:then node))]
                      (assoc (run-node engine child context (conj path :control))
                             :control-signal (:signal node))
                      (assoc context :control-signal (:signal node)))
      :end (assoc-in context [:locals :outcome] (or (:outcome node) :ended))
      :stop-vfx (emit context :vfx (assoc (normalize-vfx node context path) :op :destroy))
      :effect/vfx (emit context :vfx (normalize-vfx node context path))
      (let [kind (compiler/node-kind nil node)]
        (case kind
          :query
          (let [context (flush-command-prefix engine context)]
            (if (:host-error context)
              context
              (let [request* (resolve-value (dissoc node :component :kind :bind) context)
                    component (:component node)
                    policy-type (get-in request* [:policy :type])
                    query-kind (or (get query-kinds component)
                                   (when (= :penetration policy-type) :penetration))
                    request (cond-> (assoc (or request* {})
                                           :owner (:owner (:frame context))
                                           :world-id (or (:world-id request*)
                                                         (:world (:frame context))))
                              query-kind (assoc :query-kind query-kind))
                    capability (or (:capability node)
                                   (get query-capabilities component)
                                   component)
                    result (host/query! (:host engine) capability request
                                        {:frame (:frame context)})]
                (bind-result context (node-bind node) result))))
          :source
          (bind-result context (node-bind node) (source-value node context))
          :policy
          (let [owner (or (:owner node) (:owner (:frame context)))
                path* (:path node)
                value (resolve-value (:value node) context)
                txn (case (:op node)
                      :add (contracts/update-owner-in (:txn context) owner path* #(+ (double (or % 0.0)) (double value)))
                      :sub (contracts/update-owner-in (:txn context) owner path* #(- (double (or % 0.0)) (double value)))
                      (contracts/assoc-owner-in (:txn context) owner path* value))]
            (assoc context :txn txn))
          :action
          (let [id (command-id engine path)
                ;; :ability-id rides along on the command the same way :owner
                ;; and :world-id already do -- frame-derived infrastructure,
                ;; not an EDN-authored input. host-command's :keys destructure
                ;; only requires id/capability/owner/world-id/args to be
                ;; present; it never strips extra keys (returns (assoc command
                ;; ...) on the full :as command map), so this survives through
                ;; to whatever applies the command unmodified. This is what
                ;; lets a settled continuation (e.g. :projectile/schedule-beam)
                ;; know which ability -- and therefore which content module --
                ;; it belongs to, without combat-core knowing tenancy exists.
                command (contracts/host-command {:id id
                                                 :capability (or (:capability node)
                                                                 (when (contains? (:actions (:host engine)) component)
                                                                   component)
                                                                 (get capability-routes component)
                                                                 component)
                                                 :owner (:owner (:frame context))
                                                 :world-id (:world (:frame context))
                                                 :ability-id (:ability-id (:frame context))
                                                 :args (action-args node context)})
                context (update context :commands conj command)
                context (if-let [bind (node-bind node)]
                          (assoc-in context [:command-binds id] bind)
                          context)]
            (if (:barrier? node)
              (flush-command-prefix engine context)
              context))
          :vfx (emit context :vfx (normalize-vfx node context path))
          :feedback (emit context :feedback (resolve-value (:event node) context))
          context)))))
(defn execute! [engine compiled frame]
  (let [frame (contracts/entry-frame frame)
        owner (:owner frame)
        supplied ((:state-provider engine) owner)
        owner-record (if (contains? supplied :state) supplied {:revision 0 :state supplied})
        txn (contracts/state-txn-set {owner owner-record})
        ability-state-record ((:ability-state-provider engine) owner)
        ;; Session metadata (notably :ability-id) and mutable :state are both
        ;; part of the neutral session contract. Keep metadata visible to
        ;; event graphs while flattening state for :state/read/write.
        ability-state (merge (dissoc (or ability-state-record {}) :state)
                       (or (:state ability-state-record) {}))
        initial {:frame frame :locals {} :ability-state ability-state
                 :ability-state-patches [] :seed* (atom (:seed frame))
                 :txn txn :commands [] :barriers [] :command-binds {}
                 :outbox (contracts/outbox) :scheduled []
                 :vfx-order* (atom 0)}
        result (run-node engine (:program compiled) initial [:program])
        host-result (or (:host-error result)
                        (host/execute! (:host engine) (:commands result) {:frame frame}))
        result (if (:ok? host-result)
                  (-> result
                      (merge-action-results host-result)
                      (bind-command-results host-result))
                  result)]
    (if-not (:ok? host-result)
      {:status :rejected :reason (:reason (:error host-result)) :host host-result
       :partial-apply? (boolean (or (seq (:barriers result))
                                    (seq (get-in host-result [:error :applied-ids]))))
       :barriers (:barriers result)}
      (do
        ((:commit-state! engine)
         (mapv #(assoc % :frame frame) (contracts/txn-entries (:txn result))))
        ((:commit-ability-state! engine) owner (:ability-state-patches result))
        {:status :accepted :txn (contracts/txn-entries (:txn result))
         :ability-state-patches (:ability-state-patches result)
         :locals (:locals result)
         :outcome (get-in result [:locals :outcome])
         :next-phase (:next-phase result)
         :finish-ability? (boolean (:finish-ability? result))
         :outbox (:outbox result)
         :vfx-signals (:vfx (:outbox result))
         :feedback (:feedback (:outbox result))
         :events (:events (:outbox result))
         :scheduled (:scheduled result) :barriers (:barriers result) :host host-result}))))











