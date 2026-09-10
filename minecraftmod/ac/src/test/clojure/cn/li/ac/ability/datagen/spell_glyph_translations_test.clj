(ns cn.li.ac.ability.datagen.spell-glyph-translations-test
  "Coverage check for the spell composer's glyph i18n keys, same shape as
   editor-vocab-translations-test: assert against the REAL glyph-specs
   corpus (combat-api/player-glyph-specs), not a synthetic fixture."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.datagen.spell-glyph-translations :as spell-glyph]
            [cn.li.ac.ability.datagen.registry :as registry]
            [cn.li.combat.api :as combat-api]))

(defn- all-i18n-keys []
  (into #{} (map (comp :i18n val)) (combat-api/player-glyph-specs)))

(deftest every-glyph-i18n-key-resolves-in-en-us-test
  (let [en (:en_us (spell-glyph/translation-map))
        keys (all-i18n-keys)
        missing (remove #(contains? en %) keys)]
    (is (seq keys) "sanity: the corpus itself must be non-empty")
    (is (= [] missing) (str "glyph i18n keys with no resolvable en_us label: " missing))))

(deftest labels-are-non-blank-test
  (let [en (:en_us (spell-glyph/translation-map))]
    (is (every? #(and (string? %) (seq %)) (vals en)))))

;; Wiring regression: a translation-map existing is not enough -- registry.clj
;; must actually require this namespace and merge its :en_us into the final
;; chain (three separate edit points; see that ns's own docstring). This is
;; the test that goes red if any of the three is missed or reverted.
(deftest glyph-i18n-keys-resolve-through-the-full-registry-merge-test
  (let [merged (#'registry/ability-translation-map)
        keys (all-i18n-keys)]
    (doseq [k keys]
      (is (contains? (:en_us merged) k) (str "missing from registry en_us chain: " k)))))
