(ns cn.li.node.flow
  "A minimal, genuinely reusable set of structural primitives -- sequence,
   branch, foreach, one local bind, and a terminal marker -- with real
   execution semantics, implemented once so combat-core and vfx-core don't
   each reimplement identical control flow. Domain-specific structural
   primitives (combat's :txn/atomic, vfx's :vfx/repeat) stay owned by their
   own vm.clj; this namespace only covers what is genuinely domain-agnostic
   plumbing.

   These are :layer :primitive because they are implemented as functions,
   not because they touch Minecraft -- nothing in the three-layer rule
   (NODE_LANGUAGE.md section 1) requires a primitive to call mcmod, only
   that ONLY primitives may have :impl at all."
  (:require [cn.li.node.descriptor :as registry]
            [cn.li.node.value :as value]
            [cn.li.node.expr :as expr]))

(def builtin-ids #{:flow/sequence :flow/branch :flow/foreach :data/bind :flow/finish})

(declare execute!)

(defn- resolve-field [node k ctx]
  (value/resolve-value (get node k) (:locals ctx) (:seed ctx)))

(defn- run-child [child ctx]
  (execute! child ctx))

(defn- run-sequence [node ctx]
  (reduce (fn [c step] (if (:finished? c) c (run-child step c)))
          ctx (filterv map? (:steps node))))

(defn- run-branch [node ctx]
  (let [taken (if (boolean (resolve-field node :when ctx)) (:then node) (:else node))]
    (if (map? taken) (run-child taken ctx) ctx)))

(defn- run-foreach [node ctx]
  ;; :closed scope, per cn.li.node.scope: the body's own bindings (its :as/
  ;; :index-as, and anything it :bind's) must never escape to what follows
  ;; the loop. :seed/:finished?/:host DO thread across iterations (RNG
  ;; determinism, early termination, and any domain side-effect state must
  ;; still advance), but :locals resets back to the pre-loop snapshot once
  ;; the loop is done -- returning the last iteration's locals here would
  ;; leak the loop variable.
  (let [items (vec (or (resolve-field node :items ctx) []))
        as (:as node)
        index-as (:index-as node)
        limit (long (or (resolve-field node :limit ctx) (count items)))
        n (min (count items) (max 0 limit))]
    (loop [i 0 c ctx]
      (if (or (>= i n) (:finished? c))
        (assoc c :locals (:locals ctx))
        (let [item-locals (cond-> (assoc (:locals c) as (nth items i))
                             index-as (assoc index-as i))
              result (run-child (:body node) (assoc c :locals item-locals))]
          (recur (inc i) result))))))

(defn- run-bind [node ctx]
  (update ctx :locals assoc (:to node) (resolve-field node :value ctx)))

(defn- run-finish [node ctx]
  (assoc ctx :finished? true :outcome (:outcome node)
         :finish-session? (boolean (resolve-field node :finish-session? ctx))))

(defn execute!
  "Execute `node` against `ctx`:

     {:locals   {}                    ; local name -> value
      :seed     long                  ; RNG seed, auto-advanced per node
      :dispatch (fn [node ctx] ctx')  ; handles every non-builtin component
      :host     domain-opaque}        ; passed through untouched, e.g. the
                                       ; domain VM's own effect-collector

   Returns the updated ctx. Handles the 5 built-in structural ids directly;
   anything else is handed to (:dispatch ctx), which is how a domain VM
   plugs in its own leaf primitives (via cn.li.node.runtime/invoke-primitive!)
   and its own domain-specific structural primitives."
  [node ctx]
  (when-not (map? node)
    (throw (ex-info "not a node" {:node node :reason :not-a-node})))
  (let [ctx (update ctx :seed #(expr/next-seed (long (or % 0))))]
    (case (:component node)
      :flow/sequence (run-sequence node ctx)
      :flow/branch (run-branch node ctx)
      :flow/foreach (run-foreach node ctx)
      :data/bind (run-bind node ctx)
      :flow/finish (run-finish node ctx)
      ((:dispatch ctx) node ctx))))

(defn install!
  "Register the 5 builtin descriptors. Idempotent per JVM process is NOT
   guaranteed (the registry rejects duplicate ids) -- call this exactly
   once per registry lifetime, before freeze!. Both combat-core and
   vfx-core call this during their own vocabulary bootstrap."
  []
  (registry/register-primitive!
   {:id :flow/sequence :revision 1
    :doc "Run each step in order; a step that finishes the program short-circuits the rest."
    :category :flow
    :children {:steps {:kind :seq :flow :sequential}}
    :effects #{:mutate}
    :impl run-sequence})
  (registry/register-primitive!
   {:id :flow/branch :revision 1
    :doc "Run :then when :when is true, otherwise :else."
    :category :flow
    :inputs {:when {:type :boolean}}
    :children {:then {:kind :single :flow :branch} :else {:kind :single :flow :branch}}
    :effects #{:mutate}
    :impl run-branch})
  (registry/register-primitive!
   {:id :flow/foreach :revision 1
    :doc "Run :body once per item in :items (up to :limit), binding :as (and optionally :index-as) inside the body's own closed scope."
    :category :flow
    :inputs {:items {:type [:list-of :any]}
             :as {:type :keyword}
             :index-as {:type :keyword :default nil}
             :limit {:type :long :default nil}}
    :children {:body {:kind :single :flow :closed}}
    :effects #{:mutate}
    ;; :as/:index-as bind loop-local names through plain keyword fields,
    ;; not the generic :bind {port -> local} convention -- composite
    ;; expansion's rename-locals needs this declared so it renames these
    ;; fields' values consistently with every {:ref [:local ...]} that
    ;; reads them (see composite.clj's own comment on the bug this fixed).
    :binds-locals #{:as :index-as}
    :impl run-foreach})
  (registry/register-primitive!
   {:id :data/bind :revision 1
    :doc "Bind :value to local name :to."
    :category :flow
    :inputs {:to {:type :keyword} :value {:type :any}}
    :effects #{:mutate}
    ;; :to introduces a new local by a plain keyword field, the same
    ;; pattern :flow/foreach's :as/:index-as use (see that registration's
    ;; own comment on the bug this fixed for foreach) -- without
    ;; :binds-locals here, cn.li.node.composite/rename-locals renames every
    ;; {:ref [:local X ...]} READ of a :data/bind-introduced local (its
    ;; first cond branch renames ANY {:ref [:local ...]} unconditionally)
    ;; but leaves the :data/bind node's own :to WRITE unrenamed, since only
    ;; :binds-locals-declared fields go through the second branch. A write
    ;; to the literal name and reads of the renamed name never meet: caught
    ;; by :combat/break-budget's accumulator-threading composite, which
    ;; wrote :remaining via :data/bind and read it back via
    ;; {:ref [:local :remaining]} one step later and got nil.
    :binds-locals #{:to}
    :impl run-bind})
  (registry/register-primitive!
   {:id :flow/finish :revision 1
    :doc "Mark the program finished with :outcome; nothing after it runs. :finish-session? (default false) is an opaque pass-through flag that a domain engine may read from the final context to end a multi-phase session early; node-core itself never interprets it."
    :category :flow
    :inputs {:outcome {:type :keyword} :finish-session? {:type :boolean :default false}}
    :effects #{:mutate}
    :impl run-finish}))
