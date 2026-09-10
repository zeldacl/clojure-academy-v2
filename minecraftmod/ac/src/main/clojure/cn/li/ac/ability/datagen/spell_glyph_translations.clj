(ns cn.li.ac.ability.datagen.spell-glyph-translations
  "Auto-derived English labels for the player spell composer's glyph
   palette (node-editor/spell-composer UI refactor plan, P3/C1-C2).
   combat-core's glyph-specs table (cn.li.combat.player) already declares
   an :i18n key per glyph (\"glyph.academy.form.touch\" etc.) -- it has
   from the start, this namespace is the missing OTHER half: nothing ever
   wrote an actual translation for those keys, so resolving one through
   cn.li.mcmod.i18n/translate returned the raw key string verbatim (worse
   than the composer's old raw \"form/touch\" keyword display, not better).
   Generates all 5 labels via the same title-casing
   cn.li.ac.ability.datagen.editor-vocab-translations already uses for the
   node editor's ~140 palette entries, rather than hand-authoring a table
   this small but easy to forget to extend when a 6th glyph ships.

   title-case-id is duplicated from editor-vocab-translations (which is
   itself already a deliberate duplicate of registry.clj's private
   helper) for the same reason given there: a require cycle, and the
   transform has no domain logic to drift out of sync."
  (:require [clojure.string :as str]
            [cn.li.combat.api :as combat-api]))

(defn- title-case-id
  [id]
  (let [text (-> (name id) (str/replace #"[-_/]" " "))]
    (->> (str/split text #"\s+")
         (remove str/blank?)
         (map str/capitalize)
         (str/join " "))))

(defn translation-map
  "Map of locale -> {i18n-key -> label}. Only :en_us -- every other locale
   falls back to :en_us in registry.clj's own merge chain for any key
   without an explicit translation, same as editor-vocab-translations."
  []
  {:en_us (into {}
                (map (fn [[glyph spec]] [(:i18n spec) (title-case-id glyph)]))
                (combat-api/player-glyph-specs))})
