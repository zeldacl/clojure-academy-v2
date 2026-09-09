(ns cn.li.node.graph-compile
  "V4 graph lowering.  The persisted graph is authoritative; this small
   adapter emits neutral surface forms with stable node metadata and delegates
   register allocation/type checking to cn.li.node.compile."
  (:require [cn.li.node.ops :as ops]))

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
(defn- component-input-ports
  "Return every component input slot declared inline or addressed by a data wire.
   Migrated V4 graphs may omit an :inputs map for pure calls and rely on the
   destination port carried by the link, so compilation must not discard those
   values."
  [ctx nid]
  (let [n (get-in ctx [:nodes nid])
        inline (keys (or (:inputs n) {}))
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
   call and preserves the editor's named input slots at the graph boundary."
  [component ins fns]
  (cond
    ;; A value/map node is a data constructor, not a callable vocabulary
    ;; operation.  Returning the assembled map lets compile-form emit the
    ;; normal :map-lit instruction while still resolving linked input slots.
    (= :value/map component) ins
    (= :value/field component) (list (get ins :field) (get ins :value))
    (= :effect/vfx component) (list 'vfx! ins)
    (= :event/emit component) (list 'event! ins)
    (= :state/set component) (list 'state! (get ins :key) (get ins :value))
    (ops/known-op? component)
    (let [params (:params (ops/signature component))]
      (apply list (head component) (map #(get ins %) params)))
    ;; Composite functions are supplied by the caller's compile options and
    ;; use positional arguments declared by their :params vector.
    (contains? fns component)
    (apply list (head component)
           (map #(get ins (:name %)) (:params (get fns component))))
    :else (list (head component) ins)))

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
                                        v (get-in n [:inputs p])
                                        x (if l (expr ctx (first (:from l))) {:pre [] :form v})]]
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
    ;; V4 permits an input slot to carry an inline literal.  Preserve that
    ;; value when no data wire is present; otherwise every migrated component
    ;; with a constant option would silently receive nil at runtime.
    {:pre [] :form (get-in ctx [:nodes nid :inputs port])}))

(defn statement [ctx nid]
  (let [n (get (:nodes ctx) nid)]
    (case (:type n)
      :component
      (let [parts (for [p (component-input-ports ctx nid)
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
                 b (symbol (name (or (:as n) :item)))
                 coll (if (= :foreach t) (:form c) (list 'range (:form c)))
                 f (list* 'each b coll (:forms body))]
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
              :fns (or (:fns opts) {}) :cache (atom {})}
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
   {:kind :ability :id (:id document) :activation :instant
    :tunables (into {} (map (fn [[k v]] [k {:type (:type v)}]) (or (:inputs document) (:parameters document))))
    :state {} :entry-triggers {:render :vfx/render}
    :entries {:render (graph-entry (or (get-in document [:graphs :render]) (val (first (:graphs document)))) opts)}}))

(defn compile-skill! [document opts mode]
  ((requiring-resolve 'cn.li.node.compile/compile-program) (skill->core document opts) opts mode))

(defn compile-vfx! [document opts mode]
  ((requiring-resolve 'cn.li.node.compile/compile-program) (vfx->core document opts) opts mode))
