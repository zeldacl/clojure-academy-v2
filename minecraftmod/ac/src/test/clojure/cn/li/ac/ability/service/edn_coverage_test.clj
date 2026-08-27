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
            [cn.li.mcmod.runtime.safe-edn :as safe-edn]
            [cn.li.ac.ability.final-catalog :as final-catalog]
            [cn.li.ac.ability.final-catalog-service :as final-catalog-service]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.ability.final-vocabulary :as final-vocabulary]
            [cn.li.node.descriptor :as node-descriptors]
            [cn.li.ac.client.effect-controller :as effect-controller]
            [cn.li.ac.ability.registry.skill :as skill-registry]))

;; Exact public registration baseline extracted from main's Clojure registry:
;; 38 concrete combat skills plus the 12 category-scoped course aliases. A
;; count-only assertion would allow a replacement to silently drop one id and
;; add a duplicate while still reporting "50".
(def ^:private main-registration-ids
  #{:arc-gen :blood-retrograde :body-intensify :current-charging
    :dim-folding-theorem :directed-blastwave :directed-shock :electron-bomb
    :electron-missile :flashing :flesh-ripping :groundshock :jet-engine
    :light-shield :location-teleport :mag-manip :mag-movement :mark-teleport :meltdowner
    :mine-detect :mine-ray-basic :mine-ray-expert :mine-ray-luck
    :penetrate-teleport :plasma-cannon :rad-intensify :railgun :ray-barrage
    :scatter-bomb :shift-teleport :space-fluct :storm-wing :threatening-teleport
    :thunder-bolt :thunder-clap :vec-accel :vec-deviation :vec-reflection
    :electromaster/brain-course :meltdowner/brain-course
    :teleporter/brain-course :vecmanip/brain-course
    :electromaster/brain-course-advanced :meltdowner/brain-course-advanced
    :teleporter/brain-course-advanced :vecmanip/brain-course-advanced
    :electromaster/mind-course :meltdowner/mind-course
    :teleporter/mind-course :vecmanip/mind-course})

(def ^:private main-concrete-ability-ids
  (disj main-registration-ids
        :electromaster/brain-course :meltdowner/brain-course
        :teleporter/brain-course :vecmanip/brain-course
        :electromaster/brain-course-advanced :meltdowner/brain-course-advanced
        :teleporter/brain-course-advanced :vecmanip/brain-course-advanced
        :electromaster/mind-course :meltdowner/mind-course
        :teleporter/mind-course :vecmanip/mind-course))
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

(defn- compatibility-residue [form path]
  (cond
    (map? form)
    (let [legacy-from (when (and (contains? form :from)
                                  (keyword? (:from form)))
                         {:path path :reason :legacy-from})
          legacy-binding (when (or (contains? form :tunable)
                                   (contains? form :invariant))
                           {:path path :reason :legacy-binding})
          ref (:ref form)
          legacy-ref (when (and (vector? ref)
                                (contains? #{:slot :context :request :param} (first ref)))
                       {:path path :reason :legacy-ref})
          component (:component form)
          legacy-component (when (contains? #{:session/patch :txn/atomic :guard/resource} component)
                             {:path path :reason :legacy-component :component component})]
      (or legacy-from legacy-binding legacy-ref legacy-component
          (some identity (map (fn [[k v]] (compatibility-residue v (conj path k))) form))))
    (sequential? form)
    (some identity (map-indexed (fn [i v] (compatibility-residue v (conj path i))) form))
    :else nil))

(deftest physical-final-edn-format-gate-test
  (let [files (edn-files "src/main/resources/ac/combat")
        docs (map (juxt identity read-file!) files)
        manifest (read-file! (io/file "src/main/resources/ac/combat/manifest.edn"))
        residues (keep (fn [[file doc]]
                         (when-let [hit (compatibility-residue doc [])]
                           {:file (.getPath ^java.io.File file) :hit hit})) docs)
        abilities (filter #(= :ability (:kind (second %))) docs)]
    (is (= 39 (count (filter #(= :ability (:kind %))
                             (map read-file! (edn-files "src/main/resources/ac/combat/abilities")))))
        "all combat ability documents must be physically migrated")
    (is (empty? residues) (str "legacy final-graph syntax remains: " residues))
    (is (every? #(= :final (:engine (second %))) abilities)
        "every ability document must declare the final engine")
    (is (every? #(contains? % :bindings) (:documents manifest))
        "every registration must use explicit bindings")
    (is (not-any? #(contains? % :overrides) (:documents manifest))
        "legacy deep overrides must be absent from the manifest")))

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

(deftest main-registration-set-is-exactly-covered-test
  (let [manifest (safe-edn/read-resource! "ac/combat/manifest.edn")
        ids (mapv :id (:documents manifest))]
    (is (= (count main-registration-ids) (count ids))
        "main registration baseline must not gain or lose entries")
    (is (= main-registration-ids (set ids))
        (str "Registration ids differ from main baseline; missing="
             (sort (set/difference main-registration-ids (set ids)))
             " extra=" (sort (set/difference (set ids) main-registration-ids))))
    (is (= (count ids) (count (set ids)))
        "registration ids must be unique")))

(deftest main-item-trigger-is-unconditional-test
  ;; main's ItemCoin handler runs for every coin use, even when the player is
  ;; not currently holding the railgun activation.  The external trigger must
  ;; therefore not add an :ability-mode? gate; otherwise the platform still
  ;; consumes/spawns the item but the final event graph is never entered.
  (combat-catalog/initialize!)
  (is (= {:ability :railgun :event :coin-thrown}
         (combat-catalog/resolve-trigger
          :item/use {:item-id "ac:coin" :ability-mode? false})))
  (is (= {:ability :railgun :event :coin-thrown}
         (combat-catalog/resolve-trigger
          :item/use {:item-id "academy:coin" :ability-mode? true}))))
(deftest ray-barrage-owned-silbarn-is-owner-scoped-test
  "Silbarn is a player-owned scripted entity.  RayBarrage may detonate the
   caster's in-flight marker, but must not consume or trigger another
   player's marker in a multiplayer world."
  (let [doc (read-file! (io/file "src/main/resources/ac/combat/abilities/ray_barrage.edn"))
        nodes (fn nodes [value]
                (cond
                  (map? value)
                  (into (cond-> [] (:component value) (conj value))
                        (mapcat nodes (vals value)))
                  (sequential? value) (mapcat nodes value)
                  :else []))
        caster (some #(when (= :ability/caster (:component %)) %) (nodes doc))
        silbarn-query (some #(when (and (= :target/entities (:component %))
                                        (some #{"academy:entity_silbarn"}
                                              (get-in % [:filter :entity-types]))) %)
                            (nodes doc))]
    (is (= :caster-id (get-in caster [:bind :id])))
    (is (= {:ref [:local :caster-id]}
           (get-in silbarn-query [:filter :owner-id])))))
(deftest every-concrete-main-ability-has-a-vfx-declaration-test
  (let [files (edn-files "src/main/resources/ac/combat/abilities")
        docs (into {} (map (fn [file] (let [doc (read-file! file)] [(:id doc) doc])) files))
        manifest (safe-edn/read-resource! "ac/combat/manifest.edn")
        registrations (into {} (map (juxt :id identity) (:documents manifest)))
        missing (keep (fn [ability-id]
                        (let [registration (get registrations ability-id)
                              source-id (or (:source-id registration) ability-id)
                              doc (get docs source-id)
                              effects (set (collect-values-for-key :effect-id doc))]
                          (when (or (nil? registration) (nil? doc) (empty? effects))
                            ability-id)))
                      main-concrete-ability-ids)]
    (is (empty? missing)
        (str "Concrete main abilities without any declared VFX effect-id: "
             (sort missing)))))

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

(defn- collect-vfx-nodes
  "Collect authored effect/vfx nodes without depending on field ordering."
  [form]
  (cond
    (map? form)
    (concat (when (= :effect/vfx (:component form)) [form])
            (mapcat collect-vfx-nodes (vals form)))
    (sequential? form) (mapcat collect-vfx-nodes form)
    :else nil))

(deftest every-ability-vfx-spawn-satisfies-effect-input-schema-test
  "A successful compile only proves that an effect id exists.  The VFX
   runtime still rejects/loses a signal when a spawn omits a required input,
   so validate each authored ability payload against the effect's declared
   :inputs/:spawn contract. Destroy/update operations intentionally carry
   partial payloads and are excluded; update merges into an existing session."
  (let [effects (into {}
                      (map (fn [file]
                             (let [effect (read-file! file)] [(:id effect) effect])))
                      (edn-files "src/main/resources/ac/vfx/effects"))
        failures (mapcat
                  (fn [file]
                    (let [ability (read-file! file)]
                      (keep (fn [node]
                              (when (= :spawn (or (:operation node) (:op node) :spawn))
                                (let [effect-id (:effect-id node)
                                      effect (get effects effect-id)
                                      declared (get-in effect [:inputs :spawn] {})
                                      payload (or (:payload node) {})
                                      required (keep (fn [[key spec]]
                                                       (when (or (keyword? spec)
                                                                 (and (map? spec)
                                                                      (not (contains? spec :default))))
                                                         key))
                                                     declared)
                                      missing (remove #(contains? payload %) required)]
                                  (when (seq missing)
                                    {:ability (:id ability) :effect-id effect-id
                                     :missing (vec missing)}))))
                            (collect-vfx-nodes ability))))
                  (edn-files "src/main/resources/ac/combat/abilities"))]
    (is (empty? failures)
        (str "Ability VFX spawn payloads missing required inputs: " failures))))

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

(defn- collect-ref-vectors
  "Collect every :ref vector in a graph/document."
  [form]
  (cond
    (map? form)
    (concat (when (vector? (:ref form)) [(:ref form)])
            (mapcat collect-ref-vectors (vals form)))
    (sequential? form) (mapcat collect-ref-vectors form)
    :else nil))

(deftest every-ability-reference-has-a-declared-final-input-test
  "Catch typos where a graph reads a tunable/policy slot that its source
   document never declares. The final compiler intentionally permits neutral
   refs, so this is a semantic content check rather than a syntax check."
  (let [policy-keys (fn [doc key] (set (keys (or (get doc key) {}))))
        failures (mapcat
                  (fn [file]
                    (let [doc (read-file! file)
                          declared {:tunables (policy-keys doc :tunables)
                                    :budgets (policy-keys doc :costs)
                                    :cooldowns (policy-keys doc :cooldown)
                                    :progression (policy-keys doc :progression)
                                    :invariants (policy-keys doc :invariants)}]
                      (keep (fn [reference]
                              (let [[scope slot key] reference]
                                (when (and (= :input scope)
                                           (contains? declared slot)
                                           (keyword? key)
                                           (not (contains? (get declared slot) key)))
                                  {:ability (:id doc) :scope slot :key key
                                   :ref reference})))
                            (collect-ref-vectors doc))))
                  (edn-files "src/main/resources/ac/combat/abilities"))]
    (is (empty? failures)
        (str "Final ability refs read undeclared input slots: " failures))))

(deftest final-catalog-is-strictly-lowered-test
  (let [assembled (final-catalog/assemble)
        result (final-catalog-service/initialize!)]
    (is (= 39 (get-in assembled [:counts :combat-sources])))
    (is (= 50 (get-in assembled [:counts :combat-registrations])))
    (is (= 36 (get-in assembled [:counts :vfx-effects])))
    (is (not-any? #(= :singleton (:lifecycle %))
                  (vals (get-in assembled [:vfx :effects])))
        "final VFX catalog must not contain the removed singleton lifecycle")
    (is (= 50 (:ready-count result)))
    (is (pos? (get-in result [:node-schema :descriptor-count])))
    (is (every? #(= :ready (:status %))
                (get-in result [:combat :registrations])))))

(deftest final-catalog-has-no-unexpanded-composites-test
  (let [assembled (final-catalog/assemble)
        composite-ids (set (concat (keys (get-in assembled [:combat :composites]))
                                   (keys (get-in assembled [:vfx :composites]))))
        components (fn components [value]
                     (cond
                       (map? value)
                       (into (cond-> #{}
                               (:component value) (conj (:component value)))
                             (mapcat components (vals value)))
                       (sequential? value) (into #{} (mapcat components value))
                       :else #{}))
        graphs (concat (map :graph (vals (get-in assembled [:combat :sources])))
                       (map :control-graph (vals (get-in assembled [:vfx :effects]))))]
    (is (empty? (set/intersection composite-ids
                                  (apply set/union #{} (map components graphs)))))))

(deftest final-catalog-projected-skill-metadata-is-registerable-test
  ;; Startup must be able to populate the player-facing progression index
  ;; from final registrations. This exercises the metadata projection that
  ;; is separate from graph execution and catches missing specialization
  ;; category/prerequisite bindings before a real server boot.
  (combat-catalog/initialize!)
  (let [specs (combat-catalog/migrated-skill-specs)]
    (is (= 50 (count specs)))
    (is (= :electromaster
           (:category-id (some #(when (= :electromaster/brain-course (:id %)) %) specs))))
    (skill-registry/reset-skill-registry-for-test!)
    (doseq [spec specs]
      (skill-registry/register-skill! spec))
    (is (= 50 (count (skill-registry/raw-skills))))
    (skill-registry/reset-skill-registry-for-test!)))

(deftest final-vfx-signal-reaches-ac-draw-batch-test
  ;; The AC composition root must retain the final source graph; registering
  ;; only an empty legacy state slot would make a server-confirmed VFX signal
  ;; silently produce no render operation.
  (effect-controller/reset-for-test!)
  (let [catalog (:vfx (combat-catalog/initialize!))]
    (effect-controller/register-catalog! catalog)
    (effect-controller/dispatch-signal!
     {:op :spawn :effect-id :beam-session :owner "p1" :world-id "w"
      :instance-key [:probe "p1"] :event-seq 1 :params {:start [0.0 0.0 0.0]
                            :end [1.0 0.0 0.0]
                            :life-ticks 20 :grow-ticks 2
                            :style {}}})
    ((:tick! (effect-controller/vfx-host-api))
     {:tick-id 1 :delta-seconds 0.05})
    (let [frame ((:sample-frame! (effect-controller/vfx-host-api))
                 {:frame-id 1 :partial-tick 0.0})
          ops (mapcat val (:stages frame))]
      (is (some #(= :draw-batch (:operation %)) ops))))
  (effect-controller/reset-for-test!))

(deftest final-vocabulary-descriptor-abi-test
  (let [descriptors (filter #(= :final (:category %))
(final-vocabulary/descriptor-specs))]
    (is (pos? (count descriptors)))
    (is (every? #(every? (fn [k] (contains? % k))
                         [:id :revision :layer :category :doc :inputs :outputs :children])
                descriptors))
    (is (every? (fn [descriptor]
                  (every? #(contains? % :default)
                          (concat (vals (:inputs descriptor))
                                  (vals (:outputs descriptor)))))
                descriptors))
    (is (every? #(contains? #{:primitive :mid :source} (:layer %)) descriptors))))
