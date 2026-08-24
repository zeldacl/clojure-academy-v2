(ns cn.li.combat.final-engine
  "Authoritative graph execution over neutral mcmod host ports.")
(require '[cn.li.mcmod.runtime.host :as host]
         '[cn.li.node.contracts :as contracts]
         '[cn.li.combat.final-compiler :as compiler])
(defn create-engine [{:keys [host state-provider commit-state!]}]
  (when-not (map? host) (throw (ex-info "final combat engine requires host" {})))
  (when-not (ifn? state-provider) (throw (ex-info "final combat engine requires state-provider" {})))
  (when-not (ifn? commit-state!) (throw (ex-info "final combat engine requires commit-state!" {})))
  {:host host :state-provider state-provider :commit-state! commit-state! :next-command (atom 0)})
(defn- resolve-value [value context]
  (if (and (map? value) (vector? (:ref value)))
    (let [[scope key & path] (:ref value) root (case scope :frame (:frame context) :local (:locals context) :input (:input (:frame context)) nil)]
      (get-in root (into [key] path)))
    (cond (map? value) (into {} (map (fn [[k v]] [k (resolve-value v context)]) value))
          (vector? value) (mapv #(resolve-value % context) value)
          :else value)))
(defn- command-id [engine path]
  (let [n (swap! (:next-command engine) inc)] [:combat (vec path) n]))
(declare run-node)
(defn- bind-result [context bind value]
  (if (map? bind)
    (reduce-kv (fn [ctx output local]
                 (assoc-in ctx [:locals local]
                           (if (and (map? value) (contains? value output))
                             (get value output)
                             value)))
               context bind)
    (assoc-in context [:locals bind] value)))

(def ^:private capability-aliases
  {:combat/damage :entity/damage
   :combat/impulse :entity/impulse
   :combat/status :entity/status
   :combat/break-budget :block/break-budget
   :combat/area-damage :entity/damage
   :combat/impact-strike :entity/damage
   :combat/charged-area-damage :entity/damage
   :combat/teleport-group :entity/teleport-group
   :world/sound :world/sound
   :world/lightning :world/lightning
   :world/explosion :world/explosion})

(defn- action-args [node context]
  (let [structural #{:component :kind :bind :capability :operation :on-fail :guards
                     :reservations :body :then :else :steps :start :pulse :release :abort}]
    (resolve-value (or (:args node) (apply dissoc node structural)) context)))

(defn- source-value [node context]
  (let [input (:input (:frame context))
        name (:name node)]
    (case (:component node)
      :ability/caster (or (:caster input) (:source input) {})
      :ability/tunable (get-in input [:tunables name])
      :ability/budget (get-in input [:budgets name])
      :ability/progression (get-in input [:progression name])
      :ability/cooldown (get-in input [:cooldowns name])
      :ability/invariant (get-in input [:invariants name])
      :ability/context (get-in input [:context name])
      :session/read (get-in input [:session name])
      :data/bind (resolve-value (:value node) context)
      (resolve-value (:value node) context))))

(defn- run-sequence [engine steps context path]
  (reduce (fn [ctx [index child]] (run-node engine child ctx (conj path index))) context (map-indexed vector steps)))
(defn- run-node [engine node context path]
  (let [component (:component node)]
    (case component
      :flow/sequence (run-sequence engine (:steps node) context path)
      :flow/phases
      (let [phase (or (get-in context [:frame :input :phase])
                      (get-in context [:frame :input :action])
                      :start)
            phase-node (get node phase)]
        (if phase-node
          (run-node engine phase-node context (conj path phase))
          context))
      :flow/finish (assoc-in context [:locals :outcome] (:outcome node))
      :flow/once (if (get-in context [:locals :once-complete?])
                   context
                   (assoc-in (run-node engine (:body node) context (conj path :once))
                             [:locals :once-complete?] true))
      :flow/branch (run-node engine (if (resolve-value (:when node) context) (:then node) (:else node)) context (conj path :branch))
      :flow/foreach
      (let [items (vec (or (resolve-value (:items node) context) []))
            limit (min (count items) (long (or (:limit node) (:max-iteration contracts/budgets))))
            as (:as node)]
        (loop [index 0 ctx context]
          (if (>= index limit)
            ctx
            (recur (inc index)
                   (run-node engine (:body node) (assoc-in ctx [:locals as] (nth items index)) (conj path index))))))
      :flow/after
      (update context :scheduled conj {:tick (+ (long (:tick (:frame context))) (long (or (:delay node) 1))) :node (:body node) :path path})
      :graph/input (bind-result context (:bind node) (get-in (:input (:frame context)) (:path node)))
      :graph/output (contracts/outbox context :feedback (resolve-value (:value node) context))
      :feedback/emit (contracts/outbox context :feedback (resolve-value (:event node) context))
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
      (let [kind (compiler/node-kind node)]
        (case kind
          :query
          (let [request (resolve-value (dissoc node :component :kind :bind) context)
            result (host/query! (:host engine) (:capability node) request)]
            (bind-result context (:bind node) result))
          :source
          (bind-result context (:bind node) (source-value node context))
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
          (update context :commands conj
                  (contracts/host-command {:id (command-id engine path)
                                           :capability (or (:capability node)
                                                           (when (contains? (:actions (:host engine)) component)
                                                             component)
                                                           (get capability-aliases component)
                                                           component)
                                           :owner (:owner (:frame context))
                                           :world-id (:world (:frame context))
                                           :args (action-args node context)}))
          :vfx (contracts/outbox context :vfx (resolve-value (dissoc node :component :kind) context))
          :feedback (contracts/outbox context :feedback (resolve-value (:event node) context))
          context)))))
(defn execute! [engine compiled frame]
  (let [frame (contracts/entry-frame frame)
        owner (:owner frame)
        supplied ((:state-provider engine) owner)
        owner-record (if (contains? supplied :state) supplied {:revision 0 :state supplied})
        txn (contracts/state-txn-set {owner owner-record})
        initial {:frame frame :locals {} :txn txn :commands [] :outbox (contracts/outbox) :scheduled []}
        result (run-node engine (:program compiled) initial [:program])
        host-result (host/execute! (:host engine) (:commands result) {:frame frame})]
    (if-not (:ok? host-result)
      {:status :rejected :reason (:reason (:error host-result)) :host host-result}
      (do
        ((:commit-state! engine) (contracts/txn-entries (:txn result)))
        {:status :accepted :txn (contracts/txn-entries (:txn result)) :outbox (:outbox result)
         :scheduled (:scheduled result) :host host-result}))))
