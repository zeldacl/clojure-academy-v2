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
   found it in 31 of 39 ac/skills-v4/*.edn files, so it is implemented, not
   documented as a gap -- see ac's editor_corpus_test.clj for the
   round-trip assertion over the full real corpus, which is what actually
   proves this namespace's coverage claims rather than a hand-picked
   fixture set."
  (:require [clojure.string :as str]
            [cn.li.ability.editor.label :as label]))

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
  (let [node (get nodes nid)]
    (if (:stmt node)
      (pr-str (node->stmt-form nodes nid))
      (pr-str (dissoc node :nid)))))

(defn- short-sym
  "keyword/symbol/string -> a short display token (name segment only)."
  [x]
  (cond
    (keyword? x) (name x)
    (symbol? x) (name x)
    (string? x) x
    (nil? x) "?"
    :else (let [s (pr-str x)]
            (if (> (count s) 16) (str (subs s 0 13) "...") s))))

(defn- call-label
  "op symbol/keyword -> short verb-ish label from the last path segment
   (combat/damage -> \"damage\", cooldown/start -> \"start\")."
  [op]
  (let [raw (if (or (keyword? op) (symbol? op)) (str op) (str op))
        ;; strip leading ':' if keyword was stringified oddly
        raw (if (.startsWith ^String raw ":") (subs raw 1) raw)
        bare (last (.split ^String raw "/"))]
    (-> (str bare)
        (.replace \- \space)
        (.replace \_ \space))))

;; Pixel-width budget, not a character count (P6): a fixed character count
;; has no fixed pixel width under a proportional font -- a run of "i"/"l"
;; and a run of "M"/"W" at the same count are nowhere near the same width.
;; 134.0 keeps this at roughly its old 28-char footprint (~4.8px/char, the
;; same deterministic fallback label/ellipsize itself falls back to when
;; no font metric is installed) while actually measuring real labels.
(def ^:private label-max-width 134.0)

(defn- clip-label
  [^String s]
  (label/ellipsize s label-max-width))

(defn stmt-label
  "nodes, nid -> a SHORT plain-language canvas label (not raw DSL).
   Full form text stays available via stmt-text for inspectors. Kept in
   this namespace (not render) so tests can assert labeling without a
   paint dependency."
  [nodes nid]
  (let [node (get nodes nid)
        stmt (:stmt node)
        raw (case stmt
              :let (str "bind " (short-sym (:bind node)))
              :call (call-label (:op node))
              :when "when"
              :if "if"
              :each (str "each " (short-sym (:binding node)))
              :finish "finish"
              :state! (str "state " (short-sym (:key node)))
              :set! (str "set " (short-sym (:sym node)))
              :event! (str "event " (short-sym (:type node)))
              :vfx! (str "vfx " (short-sym (:effect-id node)))
              (or (some-> stmt name) "?"))]
    (clip-label raw)))

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

;; --- structural graph editing --------------------------------------------

(defn new-node
  "Create a minimal node record suitable for the editor palette."
  [nid kind spec]
  (merge {:nid nid :kind kind} spec))

(defn add-node
  "Append a node to the graph's top-level execution order. Existing ids are
   rejected so a stale palette click cannot overwrite user data."
  [{:keys [nodes order] :as graph} node]
  (let [nid (:nid node)]
    (when (or (nil? nid) (contains? nodes nid))
      (throw (ex-info "node id already exists or is missing" {:code :duplicate-node :nid nid})))
    (assoc graph :nodes (assoc nodes nid node) :order (conj (vec order) nid))))

(defn- default-literal [descriptor]
  (or (:default descriptor)
      (case (:type descriptor)
        (:float :double) 0.0
        (:int :long) 0
        (:bool :boolean) false
        :vec3 [0.0 0.0 0.0]
        :resource-id :minecraft/air
        nil)))

(defn insert-palette-node
  "Append a palette entry as an executable call plus literal data nodes for
   each declared parameter. Returns {:graph graph :nid call-nid}. The
   generated call is intentionally conservative: diagnostics remain visible
   for required inputs that need author adjustment, but the graph is always
   structurally round-trippable and never contains an exec-as-expression ref."
  [{:keys [nodes order] :as graph} {:keys [id params]} nid-prefix]
  (when-not (and id (map? params))
    (throw (ex-info "palette entry lacks an id or parameter map" {:code :invalid-palette-entry})))
  (let [call-nid (str nid-prefix "-call")
        param-ids (into {} (map-indexed (fn [i [key _]] [key (str nid-prefix "-arg" i)])) params)
        data (into {}
                   (map (fn [[key descriptor]]
                          (let [data-nid (get param-ids key)]
                            [data-nid {:nid data-nid :kind :data :expr :literal
                                       :value (default-literal descriptor)}]))
                   params))
        args param-ids
        with-data (assoc graph :nodes (merge nodes data))
        op (if (keyword? id) (symbol (namespace id) (name id)) id)
        call {:nid call-nid :kind :exec :stmt :call :op op :arg-shape :map :args args}]
    {:graph (add-node with-data call) :nid call-nid}))

(defn- remove-id [xs nid]
  (vec (remove #(= nid %) xs)))

(defn- remove-ref [node nid]
  (cond-> node
    (and (contains? node :rhs) (= nid (:rhs node))) (dissoc :rhs)
    (and (contains? node :cond) (= nid (:cond node))) (dissoc :cond)
    (and (contains? node :coll) (= nid (:coll node))) (dissoc :coll)
    (and (contains? node :value) (= nid (:value node))) (dissoc :value)
    (and (contains? node :src) (= nid (:src node))) (dissoc :src)
    (map? (:args node)) (update :args #(into {} (remove (fn [[_ v]] (= nid v)) %)))
    (vector? (:args node)) (update :args (fn [args] (vec (remove (fn [value] (= nid value)) args))))
    (vector? (:body-order node)) (update :body-order remove-id nid)
    (vector? (:then-order node)) (update :then-order remove-id nid)
    (vector? (:else-order node)) (update :else-order remove-id nid)))

(defn remove-node
  "Remove a node and all references to it. This deliberately leaves the
   graph structurally editable; check/diagnostics decides whether the
   resulting form is executable."
  [{:keys [nodes order] :as graph} nid]
  (when-not (contains? nodes nid)
    (throw (ex-info "cannot remove unknown node" {:code :unknown-node :nid nid})))
  (assoc graph
         :nodes (into {} (keep (fn [[id node]]
                                 (when (not= id nid) [id (remove-ref node nid)]))
                               nodes))
         :order (remove-id order nid)))

(defn- assoc-input-pin [node key from-nid]
  (cond
    (and (map? (:args node)) (contains? (:args node) key))
    (assoc-in node [:args key] from-nid)
    (and (vector? (:args node)) (integer? key) (< -1 key) (< key (count (:args node))))
    (assoc-in node [:args key] from-nid)
    (contains? node key) (assoc node key from-nid)
    :else nil))

(defn connect-wire
  "Connect an output pin to an input pin. Pin keys are semantic keys from
   the node record (:rhs/:cond/:args key, etc.). Returns a new graph or
   throws a descriptive error for an impossible connection."
  [{:keys [nodes] :as graph} {:keys [from-nid from-pin to-nid to-pin to-key]}]
  (let [from (get nodes from-nid)
        to (get nodes to-nid)]
    (when-not (and from to (= :data (:kind from)) (= :exec (:kind to))
                 (= :out from-pin) (= :in to-pin))
      (throw (ex-info "wire endpoints are invalid"
                      {:code :invalid-wire :from-nid from-nid :to-nid to-nid})))
    (let [updated (assoc-input-pin to to-key from-nid)]
      (when-not updated
        (throw (ex-info "target input pin does not exist"
                        {:code :unknown-input-pin :nid to-nid :key to-key})))
      (assoc-in graph [:nodes to-nid] updated))))

(defn disconnect-wire
  "Clear an input pin when it points at the given source."
  [{:keys [nodes] :as graph} {:keys [from-nid to-nid to-key]}]
  (if-let [to (get nodes to-nid)]
    (let [value (or (get to to-key) (get-in to [:args to-key]))]
      (if (= value from-nid)
        (assoc-in graph [:nodes to-nid]
                  (if (and (map? (:args to)) (contains? (:args to) to-key))
                    (update to :args dissoc to-key)
                    (dissoc to to-key)))
        graph))
    graph))

(defn validate-graph
  "Return a vector of structural errors. No compiler dependency: callers can
   use it while a wire is being dragged, before a form can be rebuilt."
  [{:keys [nodes order]}]
  (let [ids (set (keys nodes))
        refs (fn [node]
               (keep identity
                     (concat (when (map? (:args node)) (vals (:args node)))
                             (when (vector? (:args node)) (:args node))
                             (for [k [:rhs :cond :coll :src] :when (contains? node k)] (get node k))
                             (when (#{:state! :set!} (:stmt node)) [(:value node)])
                             (:body-order node) (:then-order node) (:else-order node))))]
    (vec (concat
          (when (not= (count order) (count (distinct order)))
            [{:code :duplicate-order-entry}])
          (for [nid order :when (not (contains? ids nid))]
            {:code :order-refers-to-missing-node :nid nid})
          (for [[nid node] nodes ref (refs node) :when (not (contains? ids ref))]
            {:code :dangling-reference :nid nid :ref ref})))))


