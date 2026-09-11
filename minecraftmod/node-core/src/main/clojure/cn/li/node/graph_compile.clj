(ns cn.li.node.graph-compile
  "V4 graph lowering.  The persisted graph is authoritative; this small
   adapter emits neutral surface forms with stable node metadata and delegates
   register allocation/type checking to cn.li.node.compile."
  (:require [cn.li.node.ops :as ops]
            [cn.li.node.types :as types]))

(defn- fail [m d] (throw (ex-info m d)))
(defn- stamp [f nid] (if (seq? f) (with-meta f {:nid (str (namespace nid) "/" (name nid))}) f))
(defn- head [k] (symbol (if-let [n (namespace k)] (str n "/" (name k)) (name k))))
(defn- ref-form [n]
  (let [k (:key n) p (case (:type n) :parameter-ref "$" :state-ref "%" :context-ref "?" "")
        b (if (= :local-get (:type n)) (name k) (str p (when (namespace k) (str (namespace k) "/")) (name k)))]
    (reduce (fn [x f] (list (keyword (name f)) x)) (symbol b) (:path n))))
(defn- data-link [links nid port]
  (some #(when (and (= :data (:kind %)) (= nid (first (:to %))) (= port (second (:to %)))) %) links))
(defn- exec-link [links nid port]
  (some #(when (and (= :exec (:kind %)) (= nid (first (:from %))) (= port (second (:from %)))) %) links))
(defn- target [links nid port] (some-> (exec-link links nid port) :to first))

(defn- args-vector->arg-ports
  "Migrated V4 graphs often store positional operator/composite args as a
   top-level `:args` vector (sibling of `:component`) rather than editor
   ports `:arg0`/`:arg1`/…. Expand that vector so component-call and
   inline-expr share one wiring shape."
  [args]
  (when (vector? args)
    (into {} (map-indexed (fn [i v] [(keyword (str "arg" i)) v]) args))))

(defn- component-inputs
  "Merge `:inputs` with optional migrated `:args` vector into one port map."
  [value]
  (merge (or (args-vector->arg-ports (:args value)) {})
         (or (:inputs value) {})))

(defn- component-input-ports
  "Return every component input slot declared inline or addressed by a data wire.
   Migrated V4 graphs may omit an :inputs map for pure calls and rely on the
   destination port carried by the link, so compilation must not discard those
   values. Also include `:args` vector slots expanded to `:argN`."
  [ctx nid]
  (let [n (get-in ctx [:nodes nid])
        inline (keys (component-inputs n))
        linked (keep (fn [l]
                       (when (and (= :data (:kind l))
                                  (= nid (first (:to l))))
                         (second (:to l))))
                     (:links ctx))]
    (vec (distinct (concat inline linked)))))

(defn- component-call
  "Build the surface call for a component node.  Vocabulary nodes consume a
   single keyword map, while node-core pure operators use positional args
   (their signatures are ordered).  Keeping that distinction here prevents
   migrated graph nodes such as :value/eq from being compiled as a one-arg map
   call and preserves the editor's named input slots at the graph boundary.

   Combat lib :defn composites (beam-strike, apply-break-budget, …) declare
   named `:params`, but V4 skill graphs wire them as `:arg0`/`:arg1`/… —
   accept both so release graphs do not pass nil into :double params
   (convert-to-double NPE at pulse→release)."
  [component ins fns]
  (cond
    ;; A value/map node is a data constructor, not a callable vocabulary
    ;; operation.  Returning the assembled map lets compile-form emit the
    ;; normal :map-lit instruction while still resolving linked input slots.
    (= :value/map component) ins
    (= :value/field component)
    ;; A field access lowers to (:the-field value), so :field must be a
    ;; literal keyword. Left unchecked it produced (nil value), and the
    ;; compiler rejected that with "malformed DSL call: head must be a
    ;; symbol or keyword" -- true, but it names neither the node nor the
    ;; input the author has to go fix.
    (let [f (get ins :field)]
      (when-not (keyword? f)
        (fail "V4 :value/field requires a literal keyword :field input"
              {:code :invalid-value-field :field f}))
      (list f (get ins :value)))
    (= :effect/vfx component) (list 'vfx! ins)
    (= :event/emit component) (list 'event! ins)
    (= :state/set component) (list 'state! (get ins :key) (get ins :value))
    (ops/known-op? component)
    ;; Ops signatures name argument TYPES (:double, :vec3, …), but V4 graphs
    ;; wire inputs to editor ports :arg0, :arg1, … — never use the type tag as
    ;; a lookup key (that silently compiled (math/add nil nil) for arc-gen).
    (let [param-types (:params (ops/signature component))
          args (mapv (fn [i] (get ins (keyword (str "arg" i))))
                     (range (count param-types)))]
      (apply list (head component) args))
    ;; Composite functions are supplied by the caller's compile options and
    ;; use positional arguments declared by their :params vector.
    (contains? fns component)
    (let [params (:params (get fns component))
          args (mapv (fn [i param]
                       (let [pname (:name param)
                             ;; :defn :params use SYMBOL names; V4 graphs may
                             ;; also expose the same slot as a keyword port.
                             named? (or (contains? ins pname)
                                        (contains? ins (keyword pname)))
                             by-name (or (get ins pname) (get ins (keyword pname)))
                             by-arg (get ins (keyword (str "arg" i)))]
                         (if named? by-name by-arg)))
                     (range (count params))
                     params)]
      (apply list (head component) args))
    :else (list (head component) ins)))

(defn- sigil-symbol
  [prefix k]
  (symbol (str prefix (when (namespace k) (str (namespace k) "/")) (name k))))

(defn- inline-ref-form
  "Lower a persisted `{ :ref [root key & path] }` fragment to a surface
   sigil.  Migrated V4 graphs embed these inside map/vector literals
   (event payloads, VFX params) instead of wiring a graph node."
  [ref]
  (when-not (and (vector? ref) (seq ref) (keyword? (first ref)))
    (fail "V4 inline :ref must be a vector starting with a keyword root" {:ref ref}))
  (let [root (first ref)
        segs (rest ref)]
    (when (empty? segs)
      (fail "V4 inline :ref is missing a key" {:ref ref}))
    (let [k (first segs)
          path (next segs)
          base (case root
                 :local (symbol (if (namespace k)
                                  (str (namespace k) "/" (name k))
                                  (name k)))
                 :parameter (sigil-symbol "$" k)
                 :context (sigil-symbol "?" k)
                 :state (sigil-symbol "%" k)
                 (fail "unsupported V4 inline :ref root" {:ref ref :root root}))]
      (reduce (fn [x f] (list (keyword (name f)) x)) base path))))

(defn- inline-expr
  "Recursively lower inline `:ref` / `:component` fragments that migrated
   graphs store inside `:inputs` maps.  Ordinary literals pass through.

   Migrated skills also nest `{ :component :math/select, :args [...] }`
   (no `:inputs` map) — expand `:args` to `:argN` ports before lowering."
  [ctx value]
  (cond
    (and (map? value) (vector? (:ref value)))
    {:pre [] :form (inline-ref-form (:ref value))}

    (and (map? value) (keyword? (:component value)))
    (let [parts (map (fn [[k v]] [k (inline-expr ctx v)]) (component-inputs value))
          ins (into {} (map (fn [[k x]] [k (:form x)]) parts))
          pre (vec (mapcat (comp :pre second) parts))
          call (component-call (:component value) ins (:fns ctx))]
      {:pre pre :form (stamp call (:nid value))})

    (map? value)
    (let [parts (map (fn [[k v]] [k (inline-expr ctx v)]) value)
          form (into {} (map (fn [[k x]] [k (:form x)]) parts))
          pre (vec (mapcat (comp :pre second) parts))]
      {:pre pre :form form})

    (vector? value)
    (let [xs (mapv #(inline-expr ctx %) value)]
      {:pre (vec (mapcat :pre xs))
       :form (mapv :form xs)})

    :else {:pre [] :form value}))

(declare expr)
(defn expr [ctx nid]
  (let [{:keys [nodes links cache]} ctx]
    (if-let [v (get @cache nid)] v
      (let [n (get nodes nid)
            v (cond
                (= :literal (:type n)) {:pre [] :form (:value n)}
                (contains? #{:context-ref :parameter-ref :state-ref :local-get} (:type n))
                {:pre [] :form (ref-form n)}
                (= :component (:type n))
                (let [parts (for [p (component-input-ports ctx nid)
                                  :let [l (data-link links nid p)
                                        v (get (component-inputs n) p)
                                        x (if l (expr ctx (first (:from l))) (inline-expr ctx v))]]
                              [p x])
                      ins (into {} (map (fn [[p x]] [p (:form x)]) parts))
                      pre (vec (mapcat (comp :pre second) parts))
                      s (symbol (str "__v4_" (name nid)))
                      call (component-call (:component n) ins (:fns ctx))]
                  {:pre (conj pre (stamp (list 'let s call) nid)) :form s})
                :else (fail "unsupported V4 expression node" {:nid nid :type (:type n)}))]
        (swap! cache assoc nid v) v))))

(defn port-expr [ctx nid port]
  (if-let [l (data-link (:links ctx) nid port)]
    (expr ctx (first (:from l)))
    ;; V4 permits an input slot to carry an inline literal, including nested
    ;; `{ :ref ... }` / `{ :component ... }` fragments from migrated graphs.
    ;; Also honor migrated top-level `:args` vectors (expanded to `:argN`).
    (inline-expr ctx (get (component-inputs (get-in ctx [:nodes nid])) port))))

;; VFX spawn-payload validation used to live here and THREW during lowering,
;; which meant the editor's :collect pass blew up on the first bad payload
;; instead of listing every problem, and the check could never report
;; anything lowering does not see. It now runs in cn.li.node.compile/
;; check-vfx-payload! as ordinary diagnostics -- same :effect-inputs opt,
;; same declarations, but it honours :throw/:collect like every other check.

(defn- assert-vfx-field-map-keys!
  "If a VFX graph does `(:from ctx-key)` via `:value/field` on a context-ref,
   that input must declare `:map-keys` including the field. Forces the contract
   skills validate against at spawn compile time."
  [document]
  (let [input-specs (or (:inputs document) {})]
    (doseq [[gname g] (or (:graphs document) {})]
      (let [nodes (:nodes g)
            links (:links g)]
        (doseq [[nid n] nodes
                :when (and (= :component (:type n))
                           (= :value/field (:component n)))]
          (let [field (get (component-inputs n) :field)
                value-link (data-link links nid :value)
                src-id (when value-link (first (:from value-link)))
                src (when src-id (get nodes src-id))]
            (when (and (keyword? field)
                       src
                       (= :context-ref (:type src)))
              (let [k (:key src)
                    spec (get input-specs k)
                    mk (:map-keys spec)]
                (when-not (and (map? mk) (contains? mk field))
                  (fail "V4 value/field on a context input requires :map-keys on that input"
                        {:code :vfx-field-map-keys
                         :graph gname
                         :nid nid
                         :input k
                         :field field
                         :inputs-spec spec}))))))))))

(defn statement [ctx nid]
  (let [n (get (:nodes ctx) nid)]
    (case (:type n)
      :component
      (let [            parts (for [p (component-input-ports ctx nid)
                        :let [x (port-expr ctx nid p)]]
                    [p x])
            ins (into {} (map (fn [[p x]] [p (:form x)]) parts))
            pre (vec (mapcat (comp :pre second) parts))
            ;; V4 persists visual effects as a fixed :effect/vfx component
            ;; node so the editor can expose its parameter slots.  The core
            ;; surface compiler represents the same operation with the
            ;; dedicated vfx! statement (rather than a vocabulary call),
            ;; which appends a signal to the frame outbox and requires a
            ;; literal :effect-id.  Lower this one component explicitly;
            ;; all other component nodes remain ordinary DSL calls.
            call (component-call (:component n) ins (:fns ctx))]
        {:pre pre :form (stamp call nid)})
      :local-set
      (let [{:keys [pre form]} (port-expr ctx nid :value)]
        {:pre pre :form (list (if (= :define (:operation n)) 'let 'set!)
                              (symbol (name (:key n))) form)})
      ;; V4 editors persist end-node fields in the visible :inputs map,
      ;; while a hand-authored document may also provide :result.  Merge
      ;; both so the lowered finish retains outcomes such as :started and
      ;; :insufficient-resource instead of silently compiling an empty map.
      :end {:pre [] :form (stamp (list 'finish (merge (or (:inputs n) {})
                                                      (or (:result n) {}))) nid)}
      (fail "unsupported V4 statement node" {:nid nid :type (:type n)}))))

(defn collect [ctx start stops]
  (loop [nid start out [] seen #{}]
    (cond
      (nil? nid) {:forms out :next nil}
      (contains? stops nid) {:forms out :next nid}
      (contains? seen nid) (fail "ordinary cycle in V4 graph" {:nid nid})
      :else
      (let [n (get (:nodes ctx) nid) t (:type n)]
        (cond
          (= :start t) (recur (target (:links ctx) nid :out) out (conj seen nid))
          (= :merge t) {:forms out :next nid}
          (= :branch t)
          (let [c (port-expr ctx nid :condition)
                tr (collect ctx (target (:links ctx) nid :true) stops)
                fr (collect ctx (target (:links ctx) nid :false) stops)
                tn (:next tr)
                false-next (:next fr)
                ;; An arm may terminate at :end while the other continues;
                ;; this is not a fan-in and therefore does not need a merge.
                ;; If both arms continue, they must meet at the same explicit
                ;; :merge node so the persisted graph never hides a join.
                join (cond
                       (= tn false-next) tn
                       (nil? tn) false-next
                       (nil? false-next) tn
                       :else (fail "V4 branch arms must both terminate or converge at the same merge node"
                                   {:nid nid :true-next tn :false-next false-next}))
                ;; compile-if consumes two explicit body vectors.  Do not use
                ;; list* here: splicing the false-arm vector turns a single
                ;; statement into bare symbols (for example `event!`) and
                ;; makes the lowered program malformed.
                f (list 'if (:form c) (:forms tr) (:forms fr))]
            (recur (when join (target (:links ctx) join :out))
                   (into out (concat (:pre c) [f]))
                   (conj seen nid)))
          (contains? #{:foreach :repeat} t)
           (let [c (port-expr ctx nid (if (= :foreach t) :collection :count))
                 body (collect ctx (target (:links ctx) nid :body) (conj stops nid))
                 item-sym (symbol (name (or (:as n) :item)))
                 binding (if-let [idx (:index-as n)]
                             [item-sym (symbol (name idx))]
                             item-sym)
                 coll (if (= :foreach t) (:form c) (list 'range (:form c)))
                 f (list* 'each binding coll (:forms body))]
            (recur (target (:links ctx) nid :completed) (into out (concat (:pre c) [f])) (conj seen nid)))
          (= :loop-end t)
          ;; A loop-end is a structural terminator for the current each body;
          ;; its continue edge is represented by the enclosing foreach/repeat
          ;; form and must never become a normal statement or an ordinary
          ;; graph cycle in the lowered surface program.
          {:forms out :next ::loop-end}
          :else
          (let [{:keys [pre form]} (statement ctx nid)
                nx (target (:links ctx) nid :out)]
            (if (= :end t) {:forms (into out (concat pre [form])) :next nil}
              (recur nx (into out (concat pre [form])) (conj seen nid)))))))))

(defn graph-entry
  ([g] (graph-entry g {}))
  ([g opts]
   (let [ctx {:nodes (:nodes g) :links (:links g)
              :fns (or (:fns opts) {})
              :effect-inputs (or (:effect-inputs opts) {})
              :cache (atom {})}
         start (some (fn [[id n]] (when (= :start (:type n)) id)) (:nodes g))]
     (:forms (collect ctx start #{})))))

(defn skill->core
  ([document] (skill->core document {}))
  ([document opts]
  ((requiring-resolve 'cn.li.node.graph-document/validate-document!) document)
  (when-not (= :ac/skill-v4 (:schema document)) (fail "expected :ac/skill-v4" {:schema (:schema document)}))
  {:kind :ability :id (:id document) :activation (get-in document [:activation :mode])
   :tunables (into {} (map (fn [[k v]] [k {:type (:type v)}]) (:parameters document)))
   :state (into {} (map (fn [[k v]] [k (select-keys v [:type :default])]) (:state document)))
   :entry-triggers (into {} (map (fn [[k v]] [k (:on v)]) (:graphs document)))
   :entries (into {} (map (fn [[k v]] [k (graph-entry v opts)]) (:graphs document)))}))

(defn vfx->core
  ([document] (vfx->core document {}))
  ([document opts]
   ((requiring-resolve 'cn.li.node.graph-document/validate-document!) document)
   (when-not (= :ac/vfx-v4 (:schema document)) (fail "expected :ac/vfx-v4" {:schema (:schema document)}))
   (assert-vfx-field-map-keys! document)
   {:kind :ability :id (:id document) :activation :instant
    :tunables (into {} (map (fn [[k v]] [k {:type (:type v)}]) (or (:inputs document) (:parameters document))))
    :state {} :entry-triggers {:render :vfx/render}
    :entries {:render (graph-entry (or (get-in document [:graphs :render]) (val (first (:graphs document)))) opts)}}))

(defn- compile-lowered
  "Lower `document` with `lower`, then compile.

   Lowering runs before there is a compile env to report into, so every
   `fail` in this namespace is a plain throw. In :throw mode that is what
   we want (startup should die loudly). In :collect mode it was NOT: one
   malformed node aborted the whole diagnostics pass, so the editor showed
   an exception instead of a list of problems -- and the author lost every
   OTHER diagnostic in the graph along with it. Lowering failures now become
   ordinary diagnostics in :collect mode, carrying whatever :code/:nid the
   thrower attached so the editor can still jump to the offending node."
  [lower document opts mode]
  (let [compile-program (requiring-resolve 'cn.li.node.compile/compile-program)]
    (if (= :collect mode)
      (try
        (compile-program (lower document opts) opts mode)
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            {:ir nil
             :diagnostics [{:severity :error
                            :code (or (:code d) :graph-lowering)
                            :message (ex-message e)
                            :nid (some-> (:nid d) name)}]})))
      (compile-program (lower document opts) opts mode))))

(defn compile-skill! [document opts mode]
  (compile-lowered skill->core document opts mode))

(defn compile-vfx! [document opts mode]
  (compile-lowered vfx->core document opts mode))
