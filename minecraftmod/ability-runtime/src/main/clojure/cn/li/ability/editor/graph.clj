(ns cn.li.ability.editor.graph
  "Bidirectional transform between a statement/expression FORM (the
   surface-DSL AST cn.li.ability.editor.document's :form holds --
   cn.li.node.surface/read-doc's shape, before normalize/compile) and a
   node/wire GRAPH the editor's canvas can render and structurally edit
   (add/remove/rewire) without hand-splicing S-expressions.

   Deliberately vocab-agnostic: form->graph/graph->form know nothing
   about which symbol names are pure ops vs vocab node calls vs :defn
   calls -- structurally `(op-or-node-id {...})` is the same shape either
   way (a :call data/exec node). Only the RENDER layer (cn.li.ability.
   editor.render, palette-aware) needs to know the difference, to color a
   pin or label a node -- keeping that distinction out of this namespace
   is what lets it stay a pure structural transform with no dependency on
   cn.li.node.schema-export at all.

   SCOPE (a real, documented limit, not a silent gap -- same 'ship the
   common flat case, throw a clear error on the unsupported shape rather
   than guess' precedent cn.li.node.pretty's unparse-each already sets
   for this exact language): supports `let`/bare-call/`when`/`if`/`each`/
   `finish`/`state!`/`set!`/`event!`/`vfx!` statements (including a
   when/if/each nested inside another when/if/each's body -- bodies
   recurse through the SAME form->graph-stmts, no depth limit), and
   arbitrarily deep pure-expression trees. `if` was initially assumed
   rare and left unsupported in a first draft of this namespace; a grep
   of the real corpus before writing graph_test.clj's round-trip test
   found it in 31 of 39 ac/skills/*.edn files, so it is implemented, not
   documented as a gap -- see ac's editor_corpus_test.clj for the
   round-trip assertion over the full real corpus, which is what actually
   proves this namespace's coverage claims rather than a hand-picked
   fixture set."
  (:require [clojure.string :as str]))

;; --- nid allocation ------------------------------------------------------

(defn- fresh-nid! [counter*] (str "n" (swap! counter* inc)))

(defn- nid-of!
  "form's own :nid metadata if present (stamped, or hand-written), else a
   freshly allocated one -- same fallback cn.li.node.compile/nid-for!
   uses, so a graph built from an unstamped form still gets real
   (if unstable-across-rebuilds) node ids to render and select by."
  [counter* form]
  (or (:nid (meta form)) (fresh-nid! counter*)))

;; --- form -> graph ---------------------------------------------------------

(declare form->expr-node! form->stmt-node!)

(defn- sigil-of [sym]
  (let [s (str sym)]
    (cond
      (str/starts-with? s "$") [:tunable (keyword (subs s 1))]
      (str/starts-with? s "?") [:capability (keyword (subs s 1))]
      (str/starts-with? s "%") [:state (keyword (subs s 1))]
      :else nil)))

(defn- call-args-shape
  "(second form) is either a map ({:k v ...}, node/fn-call arg-map style)
   or the call is positional ((op a b), pure-op style, args = (rest form))."
  [form]
  (if (map? (second form)) :map :positional))

(defn- form->expr-node!
  "Compiles one expression-position form into the nodes* map, returning
   its nid. nodes* is a transient-by-convention atom of {nid node}."
  [nodes* counter* form]
  (cond
    (symbol? form)
    (let [nid (nid-of! counter* form)]
      (if-let [[sigil-kind key] (sigil-of form)]
        (swap! nodes* assoc nid {:nid nid :kind :data :expr :sigil :sigil-kind sigil-kind :key key})
        (swap! nodes* assoc nid {:nid nid :kind :data :expr :local :sym form}))
      nid)

    (seq? form)
    (let [nid (nid-of! counter* form)]
      (cond
        (keyword? (first form))
        (let [src-nid (form->expr-node! nodes* counter* (second form))]
          (swap! nodes* assoc nid {:nid nid :kind :data :expr :field :key (first form) :src src-nid}))

        (symbol? (first form))
        (let [shape (call-args-shape form)]
          (if (= :map shape)
            (let [args (into {} (map (fn [[k v]] [k (form->expr-node! nodes* counter* v)])) (second form))]
              (swap! nodes* assoc nid {:nid nid :kind :data :expr :call :op (first form) :arg-shape :map :args args}))
            (let [args (mapv #(form->expr-node! nodes* counter* %) (rest form))]
              (swap! nodes* assoc nid {:nid nid :kind :data :expr :call :op (first form) :arg-shape :positional :args args}))))

        :else (throw (ex-info "unrecognized expression form" {:code :unsupported-expr :form form})))
      nid)

    (map? form)
    (let [nid (nid-of! counter* form)
          args (into {} (map (fn [[k v]] [k (form->expr-node! nodes* counter* v)])) form)]
      (swap! nodes* assoc nid {:nid nid :kind :data :expr :map-lit :args args})
      nid)

    (vector? form)
    (let [nid (nid-of! counter* form)
          args (mapv #(form->expr-node! nodes* counter* %) form)]
      (swap! nodes* assoc nid {:nid nid :kind :data :expr :vec-lit :args args})
      nid)

    :else
    (let [nid (fresh-nid! counter*)]
      (swap! nodes* assoc nid {:nid nid :kind :data :expr :literal :value form})
      nid)))

(defn- literal-fields
  "finish!'s :outcome/:next-phase/:end-ability? and event!/vfx!'s
   :type/:effect-id must all be literal (cn.li.node.compile enforces this
   at compile time) -- copied through as plain data, no expr node."
  [m ks]
  (select-keys m ks))

(defn- form->stmt-node!
  [nodes* counter* form]
  (when-not (and (seq? form) (symbol? (first form)))
    (throw (ex-info "unrecognized statement form" {:code :unsupported-stmt :form form})))
  (let [nid (nid-of! counter* form)
        head (first form)]
    (case (str head)
      "let"
      (let [[_ bind rhs] form
            rhs-nid (form->expr-node! nodes* counter* rhs)]
        (swap! nodes* assoc nid {:nid nid :kind :exec :stmt :let :bind bind :rhs rhs-nid}))

      "when"
      (let [[_ cond-form & body] form
            cond-nid (form->expr-node! nodes* counter* cond-form)
            body-order (mapv #(form->stmt-node! nodes* counter* %) body)]
        (swap! nodes* assoc nid {:nid nid :kind :exec :stmt :when :cond cond-nid :body-order body-order}))

      "each"
      (let [[_ binding coll-form & body] form
            [item-sym index-sym] (if (vector? binding) binding [binding nil])
            coll-nid (form->expr-node! nodes* counter* coll-form)
            body-order (mapv #(form->stmt-node! nodes* counter* %) body)]
        (swap! nodes* assoc nid {:nid nid :kind :exec :stmt :each :binding item-sym :index-binding index-sym
                                 :coll coll-nid :body-order body-order}))

      "finish"
      (let [[_ fields] form]
        (swap! nodes* assoc nid {:nid nid :kind :exec :stmt :finish
                                 :fields (literal-fields fields [:outcome :next-phase :end-ability?])}))

      "state!"
      (let [[_ key-form value-form] form
            value-nid (form->expr-node! nodes* counter* value-form)]
        (swap! nodes* assoc nid {:nid nid :kind :exec :stmt :state! :key key-form :value value-nid}))

      "set!"
      (let [[_ sym value-form] form
            value-nid (form->expr-node! nodes* counter* value-form)]
        (swap! nodes* assoc nid {:nid nid :kind :exec :stmt :set! :sym sym :value value-nid}))

      "event!"
      (let [[_ fields] form
            type-kw (:type fields)
            args (into {} (map (fn [[k v]] [k (form->expr-node! nodes* counter* v)])) (dissoc fields :type))]
        (swap! nodes* assoc nid {:nid nid :kind :exec :stmt :event! :type type-kw :fields args}))

      "vfx!"
      (let [[_ fields] form
            effect-id (:effect-id fields)
            args (into {} (map (fn [[k v]] [k (form->expr-node! nodes* counter* v)])) (dissoc fields :effect-id))]
        (swap! nodes* assoc nid {:nid nid :kind :exec :stmt :vfx! :effect-id effect-id :fields args}))

      "if"
      (let [[_ cond-form then-stmts else-stmts] form
            _ (when-not (and (vector? then-stmts) (vector? else-stmts))
                (throw (ex-info "if's then/else arms must be vectors, not a trailing body"
                                {:code :malformed-if :form form})))
            cond-nid (form->expr-node! nodes* counter* cond-form)
            then-order (mapv #(form->stmt-node! nodes* counter* %) then-stmts)
            else-order (mapv #(form->stmt-node! nodes* counter* %) else-stmts)]
        (swap! nodes* assoc nid {:nid nid :kind :exec :stmt :if :cond cond-nid
                                 :then-order then-order :else-order else-order}))

      ;; default: a bare call statement (query/action with no result
      ;; bound, or an inlined :defn call for side effect only).
      (let [shape (call-args-shape form)]
        (if (= :map shape)
          (let [args (into {} (map (fn [[k v]] [k (form->expr-node! nodes* counter* v)])) (second form))]
            (swap! nodes* assoc nid {:nid nid :kind :exec :stmt :call :op head :arg-shape :map :args args}))
          (let [args (mapv #(form->expr-node! nodes* counter* %) (rest form))]
            (swap! nodes* assoc nid {:nid nid :kind :exec :stmt :call :op head :arg-shape :positional :args args})))))
    nid))

(defn form->graph
  "One entry's statement vector (a :do value, or one :phases value, or a
   :defn's :body) -> {:nodes {nid node} :order [nid ...]}. Every node's
   :nid metadata (if present) is reused verbatim -- see this namespace's
   own docstring for the fallback when it is absent."
  [stmts]
  (let [nodes* (atom {}) counter* (atom 0)
        order (mapv #(form->stmt-node! nodes* counter* %) stmts)]
    {:nodes @nodes* :order order}))

;; --- graph -> form ---------------------------------------------------------

(declare node->expr-form node->stmt-form)

(defn- with-nid [form nid] (vary-meta form assoc :nid nid))

(defn- node->expr-form [nodes nid]
  (let [{:keys [expr] :as node} (get nodes nid)]
    (case expr
      :sigil (with-nid (symbol (str (case (:sigil-kind node) :tunable "$" :capability "?" :state "%")
                                    (subs (str (:key node)) 1)))
                       nid)
      :local (:sym node)
      :literal (:value node)
      :field (with-nid (list (:key node) (node->expr-form nodes (:src node))) nid)
      :call (with-nid
             (if (= :map (:arg-shape node))
               (list (:op node) (into {} (map (fn [[k v]] [k (node->expr-form nodes v)])) (:args node)))
               (list* (:op node) (mapv #(node->expr-form nodes %) (:args node))))
             nid)
      :map-lit (with-nid (into {} (map (fn [[k v]] [k (node->expr-form nodes v)])) (:args node)) nid)
      :vec-lit (with-nid (mapv #(node->expr-form nodes %) (:args node)) nid)
      (throw (ex-info "unrecognized expr node" {:node node})))))

(defn- node->stmt-form [nodes nid]
  (let [{:keys [stmt] :as node} (get nodes nid)]
    (case stmt
      :let (with-nid (list 'let (:bind node) (node->expr-form nodes (:rhs node))) nid)

      :when (with-nid
             (list* 'when (node->expr-form nodes (:cond node))
                    (mapv #(node->stmt-form nodes %) (:body-order node)))
             nid)

      :each (with-nid
             (list* 'each
                    (if (:index-binding node) [(:binding node) (:index-binding node)] (:binding node))
                    (node->expr-form nodes (:coll node))
                    (mapv #(node->stmt-form nodes %) (:body-order node)))
             nid)

      :if (with-nid
           (list 'if (node->expr-form nodes (:cond node))
                 (mapv #(node->stmt-form nodes %) (:then-order node))
                 (mapv #(node->stmt-form nodes %) (:else-order node)))
           nid)

      :finish (with-nid (list 'finish (:fields node)) nid)

      :state! (with-nid (list 'state! (:key node) (node->expr-form nodes (:value node))) nid)

      :set! (with-nid (list 'set! (:sym node) (node->expr-form nodes (:value node))) nid)

      :event! (with-nid
               (list 'event!
                     (into {:type (:type node)} (map (fn [[k v]] [k (node->expr-form nodes v)])) (:fields node)))
               nid)

      :vfx! (with-nid
             (list 'vfx!
                   (into {:effect-id (:effect-id node)} (map (fn [[k v]] [k (node->expr-form nodes v)])) (:fields node)))
             nid)

      :call (with-nid
             (if (= :map (:arg-shape node))
               (list (:op node) (into {} (map (fn [[k v]] [k (node->expr-form nodes v)])) (:args node)))
               (list* (:op node) (mapv #(node->expr-form nodes %) (:args node))))
             nid)

      (throw (ex-info "unrecognized stmt node" {:node node})))))

(defn graph->form
  "graph (form->graph's output) -> the statement vector it was built
   from. Pure structural reconstruction, no reconstruction ambiguity
   (unlike cn.li.node.pretty/unparse, which rebuilds from IR after
   variable names and literal shapes have already been lost -- graph.clj
   never leaves the surface AST, so nothing here is a guess)."
  [{:keys [nodes order]}]
  (mapv #(node->stmt-form nodes %) order))

;; --- read-only accessors for render.clj -------------------------------

(defn stmt-text
  "nodes, nid -> a one-line, human-readable rendering of the statement at
   nid, reusing node->stmt-form + pr-str rather than a second ad hoc
   text formatter -- this IS the exact form graph->form would print for
   that node, so a canvas label can never show something structurally
   different from what saving would actually write."
  [nodes nid]
  (pr-str (node->stmt-form nodes nid)))

(defn expr-text
  "nodes, nid -> the same idea as stmt-text, for an expression-position
   node (used for e.g. showing a `when`/`if`'s condition as a label)."
  [nodes nid]
  (pr-str (node->expr-form nodes nid)))

(defn exec-flatten
  "graph -> [{:nid :depth} ...] in program order, walking into
   when/each/if bodies (nested one :depth deeper each level). Purely the
   EXEC statement chain, in the same order graph->form would print it --
   the natural default top-to-bottom reading order for a canvas that has
   no stored layout yet (see cn.li.ability.editor.render/resolve-layout,
   which this feeds)."
  [{:keys [nodes order]}]
  (letfn [(walk [nid depth]
            (let [node (get nodes nid)]
              (into [{:nid nid :depth depth}]
                    (case (:stmt node)
                      :when (mapcat #(walk % (inc depth)) (:body-order node))
                      :each (mapcat #(walk % (inc depth)) (:body-order node))
                      :if (concat (mapcat #(walk % (inc depth)) (:then-order node))
                                  (mapcat #(walk % (inc depth)) (:else-order node)))
                      []))))]
    (vec (mapcat #(walk % 0) order))))
