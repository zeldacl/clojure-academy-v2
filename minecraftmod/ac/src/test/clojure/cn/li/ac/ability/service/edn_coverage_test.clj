(ns cn.li.ac.ability.service.edn-coverage-test
  "Real-EDN-parsing replacement for build.gradle's verifyCombatSkillCoverage
   and verifyAbilityVfxRegistryCoverage Gradle tasks, which used Groovy
   regexes over raw ability/manifest text to check content coverage --
   the mossy-wren plan flagged both as needing replacement with real EDN
   parsing before an editor-produced format change could silently break
   them (a regex over text has no idea whether a match is inside a
   comment, a string, or the field it's actually looking for). These two
   tests read the SAME files (ac/src/main/resources/ac/combat/abilities,
   ac/combat/manifest.edn, ac/vfx/manifest.edn) with safe-edn's real
   reader instead, and walk the parsed structure for the fields the old
   regexes were pattern-matching on."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [cn.li.mcmod.runtime.safe-edn :as safe-edn]))

(defn- read-file!
  "Plain clojure.edn/read, deliberately NOT safe-edn's stricter reader --
   this only needs to walk real EDN structure for a handful of known
   fields (:status/:id/:effect-id), not enforce the keyword-only-map-keys
   content-style rule safe-edn's own valid-value? applies. groundshock.edn
   has a string-keyed map (a pre-existing, already-documented content
   defect the mossy-wren plan flags for R5 cleanup, not something this
   test should choke on while it is merely checking coverage)."
  [^java.io.File file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (edn/read reader)))

(defn- edn-files [dir-path]
  (->> (io/file dir-path)
       file-seq
       (filter #(and (.isFile ^java.io.File %) (.endsWith (.getName ^java.io.File %) ".edn")))))

(defn- collect-values-for-key
  "Every value found under key `k` anywhere in `form` -- a structural
   analogue of the old regexes' blanket text scan, immune to comments/
   string literals/field reordering since it walks real parsed data."
  [k form]
  (cond
    (map? form) (concat (when (contains? form k) [(get form k)])
                         (mapcat (partial collect-values-for-key k) (vals form)))
    (sequential? form) (mapcat (partial collect-values-for-key k) form)
    :else nil))

(deftest every-migrated-ability-file-is-in-the-combat-manifest-test
  ;; Real-parsing replacement for verifyCombatSkillCoverage. Migration
  ;; status is each ability document's own :status field
  ;; (combat_catalog.clj/initialize! reads it the same way) -- there is no
  ;; separate migration_status.edn to drift out of sync with it.
  (let [migrated-ids (into #{}
                           (keep (fn [file]
                                   (let [doc (read-file! file)]
                                     (when (= :migrated (:status doc)) (:id doc)))))
                           (edn-files "src/main/resources/ac/combat/abilities"))
        manifest (safe-edn/read-resource! "ac/combat/manifest.edn")
        ;; A shared EDN document may be exposed under several public
        ;; ability ids; :source-id is the manifest's explicit coverage
        ;; declaration for that shared migrated document, so both public
        ;; aliases and their source id count as covered.
        manifest-ids (into #{} (mapcat (fn [{:keys [id source-id]}] (cond-> [id] source-id (conj source-id))))
                           (:documents manifest))
        missing (set/difference migrated-ids manifest-ids)]
    (is (empty? missing)
        (str "Migrated abilities missing from the EDN manifest: " (sort missing)))))

(deftest every-effect-id-referenced-by-an-ability-is-in-the-vfx-manifest-test
  ;; Real-parsing replacement for verifyAbilityVfxRegistryCoverage. Scans
  ;; every ability file on disk (not only migrated/successfully-compiled
  ;; ones, matching the old gate's blanket file-tree scan) so a content
  ;; mistake is caught before the ability is even wired into the manifest.
  (let [emitted (into #{}
                      (mapcat (fn [file] (collect-values-for-key :effect-id (read-file! file))))
                      (edn-files "src/main/resources/ac/combat/abilities"))
        vfx-manifest (safe-edn/read-resource! "ac/vfx/manifest.edn")
        registered (into #{} (map :id) (:documents vfx-manifest))
        missing (set/difference emitted registered)]
    (is (empty? missing)
        (str "Ability :effect-id(s) absent from the VFX manifest: " (sort missing)))))

(deftest coverage-counts-are-non-trivial-test
  ;; Guards against both deftests above passing vacuously (empty sets are
  ;; trivially subsets of anything) -- pins the real content's current
  ;; scale, not exact numbers that would need updating on every new
  ;; ability/effect.
  (let [migrated-ids (into #{}
                           (keep (fn [file]
                                   (let [doc (read-file! file)]
                                     (when (= :migrated (:status doc)) (:id doc)))))
                           (edn-files "src/main/resources/ac/combat/abilities"))
        emitted (into #{}
                      (mapcat (fn [file] (collect-values-for-key :effect-id (read-file! file))))
                      (edn-files "src/main/resources/ac/combat/abilities"))]
    (is (>= (count migrated-ids) 30))
    (is (>= (count emitted) 20))))
