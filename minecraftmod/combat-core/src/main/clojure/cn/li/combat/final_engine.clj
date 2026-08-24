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
(defn- run-sequence [engine steps context path]
  (reduce (fn [ctx [index child]] (run-node engine child ctx (conj path index))) context (map-indexed vector steps)))
(defn- run-node [engine node context path]
  (let [component (:component node)]
    (case component
      :flow/sequence (run-sequence engine (:steps node) context path)
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
      :graph/input (assoc-in context [:locals (:bind node)] (get-in (:input (:frame context)) (:path node)))
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
            (assoc-in context [:locals (:bind node)] result))
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
                                           :capability (or (:capability node) component)
                                           :owner (:owner (:frame context))
                                           :world-id (:world (:frame context))
                                           :args (resolve-value (:args node) context)}))
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
