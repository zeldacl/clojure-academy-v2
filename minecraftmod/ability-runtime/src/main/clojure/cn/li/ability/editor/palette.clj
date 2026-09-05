(ns cn.li.ability.editor.palette
  "The node editor's palette: one merged, sorted, category-grouped list
   pooling a mode's vocab nodes + node-core's shared pure ops + (skill
   mode only) the combat fn library, via cn.li.node.schema-export's
   export-vocab/export-ops/export-fns. This namespace does no exporting
   of its own -- it only merges what schema-export already produces and
   groups it, so there is exactly one place (schema-export) that knows
   how a vocab/ops/fns entry gets flattened into the palette shape.

   Callers (ac's screen controllers) inject the actual vocab/ops/fns/
   category-for -- this namespace and the rest of cn.li.ability.editor
   carry no content knowledge of their own (see verifyCoreNoSkillKnowledge,
   which scans ability-runtime/src/main for skill-specific identifiers;
   a hardcoded ability id here would trip it)."
  (:require [clojure.set :as set]
            [cn.li.node.schema-export :as schema-export]))

(defn build
  "{:vocab {...} :ops {...} :fns {...} :category-for (fn [fn-id] cat)} ->
   a single sorted vector of palette entries, each tagged :source
   (:node/:op/:fn) so the editor can tell a vocab-node call apart from a
   pure-op call or an inlined :defn without re-deriving it from shape.
   :fns/:category-for default to {}/a constant :uncategorized -- the vfx
   scene mode has no fn library yet (cn.li.vfx.dsl-vocabulary/dsl_
   vocabulary.clj docstring: no real effect needs one today)."
  [{:keys [vocab ops fns category-for]
    :or {fns {} category-for (constantly :uncategorized)}}]
  (->> (concat
        (map #(assoc % :source :node) (schema-export/export-vocab vocab))
        (map #(assoc % :source :op) (schema-export/export-ops ops))
        (map #(assoc % :source :fn) (schema-export/export-fns fns category-for)))
       (sort-by (juxt :category :id))
       vec))

(defn group-by-category
  "palette (build's output) -> {category [entry ...]}, each group already
   sorted by :id since build sorted the whole list before grouping."
  [palette]
  (group-by :category palette))

(defn filter-by-effects
  "palette, allowed-effects -> only entries whose full :effects set is a
   subset of allowed-effects. The player-facing glyph editor's own
   grey-out list is a SEPARATE mechanism (cn.li.combat.player/glyph-
   catalog, glyph-level not node-level) -- this is the generic node-level
   version, usable by any future mode that needs to restrict the palette
   to a capability subset."
  [palette allowed-effects]
  (filterv #(set/subset? (:effects %) allowed-effects) palette))

(defn find-by-id
  "palette, id -> the single entry with that :id, or nil. O(n); the
   palette is small (~150 entries today) and this is not a hot path
   (editor UI lookups only), so no index is built for it."
  [palette id]
  (some #(when (= id (:id %)) %) palette))
