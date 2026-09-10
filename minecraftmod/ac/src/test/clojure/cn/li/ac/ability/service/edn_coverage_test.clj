(ns cn.li.ac.ability.service.edn-coverage-test
  "Direct structural coverage checks for the current V4 skill/VFX resources.

   This intentionally reads the shipped EDN instead of relying on a status
   document or a regex. The checks cover the migration invariants that can
   otherwise regress silently: every skill resource assembles, every
   authored :effect-id is present in the V4 VFX catalog, every graph
   :component resolves, and every compiled IR capability is host-
   dispatchable (unknown-query / nil-convert dispatch failures)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.skills-catalog-v4 :as skills-catalog]
            [cn.li.ac.vfx.fx-catalog-v4 :as fx-catalog]
            [cn.li.combat.api :as combat-api]))

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
(defn- resource-files [root]
  (let [url (or (io/resource root)
                (throw (ex-info "V4 resource root not found" {:root root})))]
    (->> (.listFiles (io/file url))
         (filter #(and (.isFile ^java.io.File %)
                       (.endsWith (.getName ^java.io.File %) ".edn")))
         vec)))

(defn- read-edn-file [^java.io.File file]
  (binding [*read-eval* false]
    (edn/read-string (slurp file))))

(defn- collect-values-for-key [k form]
  (cond
    (map? form) (concat (when (contains? form k) [(get form k)])
                        (mapcat (partial collect-values-for-key k) (vals form)))
    (sequential? form) (mapcat (partial collect-values-for-key k) form)
    :else nil))

(defn- collect-vfx-nodes [form]
  (cond
    (map? form) (concat (when (= :effect/vfx (:component form)) [form])
                        (mapcat collect-vfx-nodes (vals form)))
    (sequential? form) (mapcat collect-vfx-nodes form)
    :else nil))

(deftest all-v4-skill-resources-assemble-test
  (let [assembled (skills-catalog/assemble)]
    (is (= 50 (:resource-count assembled)))
    (is (= 50 (:registration-count assembled)))
    (is (= 50 (count (:skills assembled))))
    (is (= 50 (count (set (map :id (:skills assembled)))))
        "skill ids must be unique after V4 assembly")
    (is (= main-registration-ids (set (map :id (:skills assembled))))
        "V4 registration ids must match the main-branch public skill set")
    (is (every? #(map? (:ir %)) (:skills assembled))
        "every shipped skill must have compiled V4 IR")))

(deftest assembled-skill-ir-capabilities-are-host-dispatchable-test
  (let [assembled (skills-catalog/assemble)
        gaps (mapcat (fn [{:keys [id ir]}]
                       (map #(assoc % :skill id)
                            (combat-api/skill-ir-capability-gaps ir)))
                     (:skills assembled))]
    (is (empty? gaps)
        (str "compiled skill IR references capabilities the host cannot dispatch: "
             (vec gaps)))))

(deftest v4-skill-graph-components-resolve-test
  (let [failures (mapcat
                  (fn [file]
                    (map #(assoc % :skill (.getName ^java.io.File file))
                         (combat-api/skill-unresolvable-components (read-edn-file file))))
                  (resource-files "ac/skills-v4"))]
    (is (empty? failures)
        (str "V4 skill graph :component keywords that cannot resolve: "
             (vec failures)))))

(deftest every-skill-effect-id-is-registered-v4-test
  (let [skill-files (resource-files "ac/skills-v4")
        effect-ids (into #{}
                         (mapcat #(collect-values-for-key :effect-id
                                                           (read-edn-file %)))
                         skill-files)
        effects (:by-id (fx-catalog/assemble))
        missing (set/difference effect-ids (set (keys effects)))]
    (is (= 50 (count skill-files)))
    (is (= 36 (count effects)))
    (is (empty? missing)
        (str "skill V4 graph references unregistered VFX effect(s): "
             (sort missing)))))

(deftest spawned-vfx-payloads-cover-required-inputs-test
  (let [effects (:by-id (fx-catalog/assemble))
        failures (mapcat
                  (fn [file]
                    (keep (fn [node]
                            (when (= :spawn (:operation (get-in node [:inputs])))
                              (let [effect (get effects (get-in node [:inputs :effect-id]))
                                    payload (or (get-in node [:inputs :payload]) {})
                                    required (keep (fn [[k spec]]
                                                     (when (and (map? spec)
                                                                (not (contains? spec :default)))
                                                       k))
                                                   (:inputs effect))
                                    missing (remove #(contains? payload %) required)]
                                (when (seq missing)
                                  {:skill (.getName ^java.io.File file)
                                   :effect-id (get-in node [:inputs :effect-id])
                                   :missing (vec missing)}))))
                          (collect-vfx-nodes (read-edn-file file))))
                  (resource-files "ac/skills-v4"))]
    (is (empty? failures)
        (str "spawned VFX payload(s) omit required effect inputs: " failures))))

(deftest spawned-vfx-payloads-honor-map-keys-test
  "Effect `:inputs` may declare `:map-keys` (e.g. beam-arc-fade :ring-radius).
   Literal skill payloads must be maps with those keys — skill catalog compile
   also enforces this; this is a structural EDN safety net."
  (let [effects (:by-id (fx-catalog/assemble))
        failures (mapcat
                  (fn [file]
                    (keep (fn [node]
                            (when (and (= :effect/vfx (:component node))
                                       (= :spawn (get-in node [:inputs :operation])))
                              (let [effect-id (get-in node [:inputs :effect-id])
                                    payload (or (get-in node [:inputs :payload]) {})
                                    input-specs (or (get-in effects [effect-id :document :inputs]) {})
                                    bad (keep (fn [[k spec]]
                                                (when-let [mk (:map-keys spec)]
                                                  (let [v (get payload k)]
                                                    (when (and (contains? payload k)
                                                               (or (not (map? v))
                                                                   (not (every? #(contains? v %) (keys mk)))))
                                                      {:key k :expected mk :actual v}))))
                                              input-specs)]
                                (when (seq bad)
                                  {:skill (.getName ^java.io.File file)
                                   :effect-id effect-id
                                   :bad (vec bad)}))))
                          (collect-vfx-nodes (read-edn-file file))))
                  (resource-files "ac/skills-v4"))]
    (is (empty? failures)
        (str "spawned VFX payload(s) disagree with effect :map-keys: " failures))))

(deftest v4-vfx-resources-have-unique-ids-and-render-graphs-test
  (let [files (resource-files "ac/vfx-v4")
        docs (mapv read-edn-file files)
        ids (map :id docs)]
    (is (= 36 (count files)))
    (is (= 36 (count (set ids))))
    (is (every? #(= :ac/vfx-v4 (:schema %)) docs))
    (is (every? #(contains? (:graphs %) :render) docs)
        "every V4 VFX document must declare a render graph, including explicit side-channels")))