(ns cn.li.ac.ability.datagen.editor-vocab-translations
  "Auto-derived English labels for the node editor's palette entries (see
   the node-editor plan's Phase 0). Every :category/:i18n key the combat
   and vfx surface-DSL vocabularies, the pure-op table, and the combat fn
   library export needs a resolvable English label; this generates ALL of
   them from the id itself via title-casing -- the same technique
   registry.clj's own category-name-entries/skill-name-entries already
   use for auto-derived ability/category names -- rather than
   hand-authoring ~140 label strings that would need to be kept in sync
   by hand as nodes are added.

   title-case-id is duplicated from registry.clj's own private helper,
   not shared: requiring registry.clj from here to reuse it would create
   a require cycle (registry.clj is the one that merges THIS namespace's
   translation-map into ability-translation-map). Both copies are the
   same trivial kebab-case->Title Case transform with no domain logic, so
   duplication cannot drift into a real bug the way sharing state or
   business logic would."
  (:require [clojure.string :as str]
            [cn.li.node.schema-export :as schema-export]
            [cn.li.node.ops :as ops]
            [cn.li.combat.api :as combat-api]
            [cn.li.vfx.api :as vfx-api]))

(defn- title-case-id
  [id]
  (let [text (-> (name id) (str/replace #"[-_/]" " "))]
    (->> (str/split text #"\s+")
         (remove str/blank?)
         (map str/capitalize)
         (str/join " "))))

(defn- all-editor-entries
  "Every palette entry the node editor's three vocab/op/fn sources
   export, pooled into one flat seq -- this is intentionally the SAME
   pool cn.li.ability.editor.palette (Phase 2) will build its palette
   from, so the translation coverage this namespace guarantees actually
   matches what the editor shows, not just what existed at the time this
   file was written."
  []
  (concat
   (schema-export/export-vocab combat-api/skill-vocab)
   (schema-export/export-vocab vfx-api/scene-vocab)
   (schema-export/export-ops ops/table)
   (schema-export/export-fns combat-api/skill-lib-fns combat-api/skill-vocab-category-for)))

(defn translation-map
  "Map of locale -> {i18n-key -> label}. Only :en_us: every other locale
   already falls back to :en_us in registry.clj's own merge chain for any
   key without an explicit translation, matching how skill names/
   categories without hand-authored translations already behave -- see
   skill-translations.clj's own docstring for the precedent."
  []
  {:en_us (into {} (map (fn [{:keys [id i18n]}] [i18n (title-case-id id)])) (all-editor-entries))})
