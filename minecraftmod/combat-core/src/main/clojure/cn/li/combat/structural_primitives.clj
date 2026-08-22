(ns cn.li.combat.structural-primitives
  "v3 registration for combat-core's own structural control-flow
   primitives -- the ones genuinely specific to ability programs, on top of
   node-core's shared :flow/sequence/:flow/branch/:flow/foreach/:data/bind/
   :flow/finish (cn.li.node.flow). These follow the identical pattern node-
   core's flow.clj establishes: :impl has signature (fn [node ctx] ctx'),
   not the (fn [inputs ctx] outputs) invoke-primitive! contract -- these
   are never called through invoke-primitive! (their children live in
   :children, not :inputs, so invoke-primitive!'s input-filtering would
   drop them entirely). A future combat execute! dispatches to these
   directly, the same way node-core/flow.clj's execute! dispatches to its
   own 5 builtins, falling through to cn.li.node.runtime/invoke-primitive!
   only for genuine leaf primitives. Registering them here now (with real,
   tested execution semantics) is what a future combat execute! will call
   into -- not speculative.

   :flow/control (the loop-scoped :skip-item/:break-loop signal) is
   deliberately NOT registered yet: it requires cn.li.node.flow's shared
   :flow/foreach to understand a control signal, which it does not today
   (see flow.clj's docstring -- the R1 builtin set was kept minimal on
   purpose). Extending it belongs with R4, when a real composite first
   needs skip/break semantics, not speculatively here.

   :flow/once's v3 :inputs intentionally declare only :key -- the old v2
   schema also required :scope/:storage-path, but vm.clj's execution only
   ever read :key (a real schema/implementation drift the R2 audit flagged
   for fixing here, see the mossy-wren plan's R2 section).

   ADDITIVE ONLY at this revision -- see host_primitives.clj's docstring."
  (:require [cn.li.node.descriptor :as node]
            [cn.li.node.value :as value]
            [cn.li.node.runtime :as runtime]
            [cn.li.node.flow :as node-flow]
            [cn.li.node.composite :as composite]))

(defn- resolve-field [node k ctx]
  (value/resolve-value (get node k) (:locals ctx) (:seed ctx)))

(defn- run-child
  "ctx-first argument order so this can be used directly as a `reduce`
   callback (`(fn [acc item] ...)`) over a :seq of children, not just as a
   plain two-arg helper -- a swapped order here previously passed the ctx
   map to node-flow/execute! as if it were a node, and the reservation node
   as if it were ctx, silently NPEing on the (:dispatch ctx) fallback the
   moment any real dispatch happened (caught by
   txn-atomic-runs-body-and-on-success-when-guards-pass-test)."
  [ctx child]
  (if (map? child) (node-flow/execute! child ctx) ctx))

;; --- :flow/phases -----------------------------------------------------

(defn- run-phases [node ctx]
  (let [phase (or (:phase ctx) :start)
        child (if (= phase :events) (get-in node [:events (:event ctx)]) (get node phase))]
    (run-child ctx child)))

;; --- :flow/window -------------------------------------------------------

(defn- run-window [node ctx]
  (let [v (double (resolve-field node :value ctx))
        lo (double (resolve-field node :min-exclusive ctx))
        hi (double (resolve-field node :max-inclusive ctx))
        pass? (and (> v lo) (<= v hi))]
    (run-child ctx (if pass? (:on-pass node) (:on-fail node)))))

;; --- :flow/once -----------------------------------------------------------

(defn- run-once [node ctx]
  (let [key (resolve-field node :key ctx)
        latches* (:latches* ctx)
        seen? (and latches* (contains? @latches* key))]
    (if seen?
      (run-child ctx (:on-duplicate node))
      (do (when latches* (swap! latches* conj key))
          (run-child ctx (:on-first node))))))

;; --- :txn/atomic ------------------------------------------------------

(defn- eval-guard
  "Guards are always leaf primitives (e.g. :guard/value-in) evaluated for
   their immediate :result output, never bound to a named local -- a guard
   check is a pass/fail gate, not a value the rest of the program reads by
   name."
  [guard ctx]
  (let [resolved (reduce-kv (fn [acc k v] (assoc acc k (value/resolve-value v (:locals ctx) (:seed ctx))))
                            {} (dissoc guard :component))]
    (boolean (:result (runtime/invoke-primitive! (:component guard) resolved ctx)))))

(defn- run-txn-atomic [node ctx]
  (if (every? #(eval-guard % ctx) (:guards node))
    (let [after-reservations (reduce run-child ctx (:reservations node))
          body-ctx (run-child after-reservations (:body node))]
      (if (:on-success node)
        (run-child body-ctx (:on-success node))
        body-ctx))
    (run-child ctx (:on-fail node))))

(def ^:private structural-impls
  {:flow/phases run-phases :flow/window run-window :flow/once run-once :txn/atomic run-txn-atomic})

(defn dispatch
  "A ctx :dispatch function combining combat's structural primitives,
   :layer :mid composite expansion, and leaf-primitive execution via
   invoke-primitive!. Suitable as (:dispatch ctx) for
   cn.li.node.flow/execute! -- this is the first piece of what a real
   combat execute! becomes in R4/R5 (a future version also knows about the
   :ability/* source nodes, once those are wired into execution rather
   than only registered as descriptors).

   Composite expansion needs no vfx-style reconciliation here: combat's
   runtime value language (cn.li.node.value) only ever recognizes :local/
   :expr, the exact same forms cn.li.node.composite/expand already
   produces (:bind/{:ref [:local ...]}), so an expanded composite's body
   resolves correctly against this dispatcher with no extra translation --
   this is the case node-core's composite mechanism was designed for
   directly (see the vfx-core equivalent, cn.li.vfx.vm's :default
   fallback, for the domain where that assumption does NOT hold and had
   to be fixed instead)."
  [node ctx]
  (if-let [impl (get structural-impls (:component node))]
    (impl node ctx)
    (let [d (node/descriptor (:component node))]
      (if (and d (= :mid (:layer d)))
        (node-flow/execute! (composite/expand node) ctx)
        (let [resolved (reduce-kv (fn [acc k v] (assoc acc k (value/resolve-value v (:locals ctx) (:seed ctx))))
                                  {} (dissoc node :component :bind))
              outputs (runtime/invoke-primitive! (:component node) resolved ctx)]
          (if-let [binds (:bind node)]
            (reduce-kv (fn [c port local] (update c :locals assoc local (get outputs port))) ctx binds)
            ctx))))))

(defn install!
  "Register combat's own structural primitives. Call once per registry
   lifetime, before node/freeze!. Assumes cn.li.node.flow/install! has
   already run (these compose with node-core's shared 5 via run-child ->
   node-flow/execute!)."
  []
  (node/register-primitive!
   {:id :flow/phases :revision 1 :category :flow
    :doc "Dispatch to :start/:pulse/:release/:abort by activation phase, or into :events by event name when phase is :events."
    :children {:start {:kind :single :flow :branch} :pulse {:kind :single :flow :branch}
               :release {:kind :single :flow :branch} :abort {:kind :single :flow :branch}
               :events {:kind :case-map :flow :branch}}
    :effects #{:mutate}
    :impl run-phases})
  (node/register-primitive!
   {:id :flow/window :revision 1 :category :flow
    :doc "Run :on-pass when :value is in (:min-exclusive, :max-inclusive], otherwise :on-fail."
    :inputs {:value {:type :double} :min-exclusive {:type :double} :max-inclusive {:type :double}}
    :children {:on-pass {:kind :single :flow :branch} :on-fail {:kind :single :flow :branch}}
    :effects #{:mutate}
    :impl run-window})
  (node/register-primitive!
   {:id :flow/once :revision 1 :category :flow
    :doc "Run :on-first the first time :key is seen this activation, :on-duplicate on every later occurrence."
    :inputs {:key {:type :any}}
    :children {:on-first {:kind :single :flow :branch} :on-duplicate {:kind :single :flow :branch}}
    :effects #{:mutate}
    :impl run-once})
  (node/register-primitive!
   {:id :txn/atomic :revision 1 :category :flow
    :doc "Run :body (then optional :on-success) only when every :guards check passes, after running :reservations; otherwise run :on-fail."
    :children {:guards {:kind :seq :flow :closed} :reservations {:kind :seq :flow :closed}
               :body {:kind :single :flow :sequential}
               :on-success {:kind :single :flow :closed} :on-fail {:kind :single :flow :closed}}
    :effects #{:mutate}
    :impl run-txn-atomic}))
