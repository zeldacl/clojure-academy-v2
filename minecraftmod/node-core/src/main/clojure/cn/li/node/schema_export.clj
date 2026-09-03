(ns cn.li.node.schema-export
  "Export only the author-facing portion of the node ABI.

   Runtime injection, capabilities, kernel descriptors and host transaction
   details are intentionally excluded from the visual editor schema.

   export-descriptor/export-environment below serve the OLD
   descriptor/environment system (cn.li.node.descriptor + environment),
   still the live production path via ac's final_catalog_service.clj until
   combat-core/vfx-core are rewritten off it. export-vocab/export-ops
   further down are the NEW surface-DSL vocabulary's export path
   (cn.li.node.compile's :vocab shape + cn.li.node.ops's pure-op table) --
   additive, not a replacement, until that migration retires the functions
   above.")

(defn- export-fields [fields]
  (reduce-kv (fn [acc k spec]
               (if (= false (:editor-visible? spec))
                 acc
                 (assoc acc k (select-keys spec [:type :min :max :default :doc :scope]))))
             {} fields))

(defn export-descriptor [d]
  (when (= :author (:visibility d))
    {:id (:id d)
     :revision (:revision d)
     :layer (:layer d)
     :doc (:doc d)
     :category (:category d)
     :inputs (export-fields (:inputs d))
     :outputs (export-fields (:outputs d))
     :children (reduce-kv (fn [acc k spec]
                            (assoc acc k (select-keys spec [:kind :flow :required?])))
                          {} (:children d))
     :effects (:effects d)}))

(defn export-environment
  "Export one immutable author-facing node catalog in stable id order."
  [environment]
  (->> (vals (:descriptors environment))
       (sort-by :id)
       (keep export-descriptor)
       vec))

;; --- new surface-DSL vocabulary export --------------------------------------
;;
;; vocab shape (cn.li.node.compile's :vocab opt / cn.li.combat.vocabulary /
;; cn.li.vfx.vocabulary once those are rewritten):
;;   {node-id {:params {k {:type t :default v?}} :returns t-or-nil
;;             :effects #{...} :capability kw :barrier? bool :cost n
;;             :category kw :i18n s :doc s :pure? bool}}
;;
;; This drives an editor palette directly: pin colors from :params/:returns
;; types (cn.li.node.types), live complexity/mana readouts from :cost
;; (cn.li.node.cost sums these), and which nodes a player-facing editor may
;; even show from :effects (cn.li.combat.player's server-side admission
;; check consumes the identical :effects set -- see export-player-effects,
;; which exists specifically so the editor's grey-out list and the server's
;; actual admission rule can never drift apart).

(defn- export-param [{:keys [type default]}]
  (cond-> {:type type} (some? default) (assoc :default default)))

(defn export-vocab-node [id spec]
  {:id id
   :params (into {} (map (fn [[k v]] [k (export-param v)])) (:params spec))
   :returns (:returns spec)
   :effects (or (:effects spec) #{})
   :pure? (boolean (:pure? spec))
   :cost (long (or (:cost spec) 0))
   :category (or (:category spec) :uncategorized)
   :i18n (:i18n spec)
   :doc (:doc spec)})

(defn export-vocab
  "vocab -> sorted vector of export-vocab-node results, stable id order."
  [vocab]
  (->> vocab (map (fn [[id spec]] (export-vocab-node id spec))) (sort-by :id) vec))

(defn export-ops
  "cn.li.node.ops/table -> the same export shape as export-vocab, so an
   editor palette can merge domain vocab nodes and pure operators into one
   list without a separate code path. Pure ops are always :pure? true,
   :effects #{}, :cost 0 (see cn.li.node.cost's docstring)."
  [ops-table]
  (->> ops-table
       (map (fn [[id {:keys [params returns]}]]
              {:id id
               :params (into {} (map-indexed (fn [i t] [(keyword (str "arg" i)) {:type t}])) params)
               :returns returns
               :effects #{}
               :pure? true
               :cost 0
               :category :operator
               :i18n nil
               :doc nil}))
       (sort-by :id)
       vec))

(defn export-player-effects
  "The :effects union across every node in `vocab` whose id is present in
   `allowed`. Exists so a player-facing editor's grey-out list is DERIVED
   from the same allowlist cn.li.combat.player/admit enforces server-side,
   instead of maintaining a second copy that can silently drift."
  [vocab allowed]
  (into #{} (mapcat (fn [id] (:effects (get vocab id)))) allowed))


