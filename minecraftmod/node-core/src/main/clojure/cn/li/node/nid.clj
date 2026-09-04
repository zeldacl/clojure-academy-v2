(ns cn.li.node.nid
  "Assigns stable, editor-visible node identities (:nid metadata) to
   surface-DSL statement/expression forms -- the forms cn.li.node.compile's
   nid-for! reads :nid off of when present (see that function's own
   docstring for the exact rule: node/fn calls, sigil reads, field
   access, pure-op calls, map/vector literals in expression position, and
   control-flow/effect statements).

   Walks lists and maps unconditionally and tags each with a fresh :nid
   if it does not already carry one. This deliberately over-stamps
   relative to nid-for!'s actual read set: a node call's arg-map and an
   event!/vfx!'s fields map are themselves stamped too, even though
   compile.clj never calls nid-for! on those inner maps (only on the
   ENCLOSING call/statement form) -- harmless unused metadata, not a
   correctness issue, and far simpler/more robust than trying to
   shadow-replicate compile.clj's exact per-construct dispatch here (a
   second copy of that dispatch is a real drift risk as the language
   grows; over-stamping is not).

   Vectors are walked for recursion but never themselves stamped: the
   overwhelming majority appearing in a statement/expression tree are
   STRUCTURAL bodies (if's then/else arms, each's [item index] binding),
   not :vec-lit values. This is a real, accepted first-pass gap, not
   silently claimed complete -- an author-typed [...] vector LITERAL
   VALUE (e.g. :add-tags [\"x\"]) gets a fresh nid on every compile
   instead of a stable one, same as any other unstamped form; extending
   this to tell the two apart is deferred until the editor (Phase 2 of
   the node-editor plan) actually needs vector-literal identity to be
   stable. The same 'ship the common case, document the gap rather than
   guess' precedent cn.li.node.pretty's unparse-each docstring already
   sets for this codebase.

   Bare sigil symbols ($x/?x/%x) are NOT stamped by this first pass
   either, for the same reason: nid-for! already falls back to a fresh
   per-compile nid when metadata is absent, so an unstamped sigil read
   compiles correctly today, it just is not yet stable across edits --
   a smaller, independently addressable gap than the vector-literal one
   above, left for the same later pass.")

(defn- fresh-nid! [counter*] (str "n" (swap! counter* inc)))

(defn collect
  "form -> the set of every :nid already present via metadata anywhere
   in its list/map subtree. Used to seed stamp's counter above the
   highest existing n<N> so re-stamping a partially-edited doc never
   collides with a nid already in play (hand-written or from an earlier
   stamp pass)."
  [form]
  (cond
    (seq? form)
    (into (if-let [n (:nid (meta form))] #{n} #{}) (mapcat collect) form)

    (vector? form)
    (into #{} (mapcat collect) form)

    (map? form)
    (into (if-let [n (:nid (meta form))] #{n} #{}) (mapcat collect) (vals form))

    :else #{}))

(defn- max-existing-n [nids]
  (reduce (fn [acc s]
            (if-let [[_ digits] (re-matches #"n(\d+)" s)]
              (max acc (Long/parseLong digits))
              acc))
          0 nids))

(defn- stamp-form [counter* form]
  (cond
    (seq? form)
    (let [children (map #(stamp-form counter* %) form)
          rebuilt (with-meta (apply list children) (meta form))]
      (cond-> rebuilt (not (:nid (meta rebuilt))) (vary-meta assoc :nid (fresh-nid! counter*))))

    (vector? form)
    (with-meta (mapv #(stamp-form counter* %) form) (meta form))

    (map? form)
    (let [stamped (with-meta (into {} (map (fn [[k v]] [k (stamp-form counter* v)])) form) (meta form))]
      (cond-> stamped (not (:nid (meta stamped))) (vary-meta assoc :nid (fresh-nid! counter*))))

    :else form))

(defn- stamp-stmts [counter* stmts] (mapv #(stamp-form counter* %) stmts))

(defn stamp
  "raw-doc (cn.li.node.surface/read-doc's output, BEFORE normalize --
   same shape, :do or :phases or :defn+:body) -> the same doc with every
   un-nid'd list/map form in its statement trees given a fresh,
   collision-free :nid. Existing :nid metadata is preserved untouched and
   never renumbered -- idempotent: re-stamping an already-fully-stamped
   doc returns the same nids unchanged, and inserting a brand-new
   statement anywhere does not shift any OTHER statement's existing nid,
   which a bare per-compile counter (cn.li.node.compile's nid!) cannot
   guarantee on its own."
  [raw-doc]
  (let [counter* (atom (max-existing-n (collect raw-doc)))]
    (cond-> raw-doc
      (contains? raw-doc :do) (update :do #(stamp-stmts counter* %))
      (contains? raw-doc :phases) (update :phases (fn [phases] (into {} (map (fn [[k v]] [k (stamp-stmts counter* v)])) phases)))
      (contains? raw-doc :body) (update :body #(stamp-stmts counter* %)))))
