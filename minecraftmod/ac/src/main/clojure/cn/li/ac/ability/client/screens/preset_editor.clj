(ns cn.li.ac.ability.client.screens.preset-editor
  "Preset editor screen logic (AC layer - no Minecraft imports)."
  (:require [clojure.string :as str]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.util.log :as log]))


;; Editor state
(def ^:private default-editor-state
  {:selected-preset 0
   :player-uuid nil})

(def screen-id :preset-editor)

(defn editor-owner-key
  [owner]
  (read-model/owner-key owner :preset-editor))

(defn- with-editor-player-state-owner
  [owner f]
  (read-model/with-player-state-owner (editor-owner-key owner) f))

(defn- get-editor-player-state
  [owner]
  (read-model/get-player-state (editor-owner-key owner)))

(defn editor-state-snapshot
  ([owner]
   (managed-screens/screen-state screen-id (editor-owner-key owner) default-editor-state)))

(defn- swap-editor-state!
  [owner f & args]
  (let [owner-key (editor-owner-key owner)]
    (apply managed-screens/update-screen-state! screen-id owner-key default-editor-state f args)))

(defn reset-editor-states-for-test!
  []
  (managed-screens/reset-managed-screen-state-for-test!)
  nil)

;; ============================================================================
;; Render Data Builders
;; ============================================================================

(defn- spec-skill-id
  [skill-spec]
  (or (:skill-id skill-spec) (:id skill-spec)))

(defn- as-keyword
  "NBT/network sync may rehydrate keywords as strings; normalize for set lookups."
  [x]
  (cond
    (keyword? x) x
    (string? x) (keyword x)
    (symbol? x) (keyword (name x))
    :else x))

(defn- skill-id-aliases
  "skills-v3 renamed ids (arc_gen → arc-gen). Accept both spellings, plus
   qualified/unqualified forms, so learned sets and slot ctrl-ids still match."
  [skill-id]
  (when-let [sid (as-keyword skill-id)]
    (let [n (name sid)
          ns (namespace sid)
          flipped (str/replace n #"[_-]" (fn [ch] (if (= (str ch) "_") "-" "_")))
          stems (cond-> #{n}
                  (not= n flipped) (conj flipped))
          with-ns (fn [stem]
                    (cond-> #{(keyword stem)}
                      ns (conj (keyword ns stem))))]
      (into #{sid} (mapcat with-ns) stems))))

(defn- learned-skill-ids
  [ability-data]
  (into #{} (mapcat skill-id-aliases) (:learned-skills ability-data #{})))

(defn- skill-learned?
  [learned-ids skill-spec]
  (boolean (some learned-ids (skill-id-aliases (spec-skill-id skill-spec)))))

(defn- skill-preset-selectable?
  "Whether a skill may appear in the preset picker.

  Uses structural canControl (skill-definitions / raw registry), NOT the live
  Forge config overlay on get-skill. A blanket config `controllable=false` /
  `enabled=false` would otherwise yield L>0 A=0 after learn_all while combat
  still works from already-bound slots."
  [skill-spec]
  (let [sid (spec-skill-id skill-spec)
        unqualified (when sid
                      (if (namespace sid) (keyword (name sid)) sid))
        defn (or (get skill-config/skill-definitions-by-id sid)
                 (get skill-config/skill-definitions-by-id unqualified))
        raw (or (skill-registry/raw-skill sid)
                (when unqualified (skill-registry/raw-skill unqualified)))
        controllable? (cond
                        defn (not (false? (:controllable? defn)))
                        (false? (:controllable? raw)) false
                        ;; skill-specs may write nil over normalize defaults;
                        ;; nil means default-true upstream Skill.canControl.
                        :else true)]
    controllable?))

(defn- slot-pair
  [pair]
  (preset-data/normalize-controllable pair))

(defn- normalize-slots-data
  [slots]
  (preset-data/normalize-slots slots))

(defn- slot-info
  "Build slot info map for a controllable pair. Always returns a row when the
   pair is present — never drop a bound skill from the carousel paint path."
  [pair]
  (when-let [[cat-kw ctrl-kw] (slot-pair pair)]
    (let [skill-obj (or (skill-query/get-skill-by-controllable cat-kw ctrl-kw)
                        (some (fn [sid]
                                (when (skill-registry/raw-skill sid) sid))
                              (skill-id-aliases ctrl-kw))
                        ctrl-kw)
          spec (or (skill-registry/raw-skill skill-obj)
                   (skill-registry/get-skill skill-obj))
          name-key (:name-key spec)
          skill-name (if name-key
                       (or (i18n/translate name-key) (name skill-obj))
                       (or (:name spec) (name skill-obj)))]
      {:skill-id skill-obj
       :skill-name skill-name
       :skill-icon (skill-query/get-skill-icon-path skill-obj)
       :cat-id cat-kw
       :ctrl-id ctrl-kw})))

(defn- presets-all-slots
  "Build slot info for all 4 presets. Returns map {preset-idx [slot-0 slot-1 slot-2 slot-3]}."
  [slots-data]
  (into {}
    (for [preset-idx (range 4)]
      [preset-idx
       (mapv (fn [key-idx]
               (slot-info (get slots-data [preset-idx key-idx])))
             (range 4))])))

(defn- assigned-ctrl-ids
  "Set of controllable ids already assigned in the given preset (with aliases)."
  [slots-data preset-idx]
  (into #{}
        (mapcat (fn [key-idx]
                  (when-let [pair (get slots-data [preset-idx key-idx])]
                    (skill-id-aliases (second pair))))
                (range 4))))

(defn- skill-assigned?
  [assigned-ids skill-spec]
  (boolean (some assigned-ids
                 (skill-id-aliases (or (:ctrl-id skill-spec)
                                       (spec-skill-id skill-spec))))))

(defn- distinct-specs-by-id
  [specs]
  (vec (vals (reduce (fn [m s]
                       (let [sid (spec-skill-id s)]
                         (cond-> m
                           (and sid (not (contains? m sid))) (assoc sid s))))
                     {}
                     specs))))

(defn- synthesize-spec-from-definitions
  "Last-resort picker row when the live skill registry has no entry for a
   learned id (client Framework lag / partial init). Uses skill-definitions."
  [skill-id category-id]
  (let [sid (as-keyword skill-id)
        unqualified (when sid (if (namespace sid) (keyword (name sid)) sid))
        defn (or (get skill-config/skill-definitions-by-id sid)
                 (get skill-config/skill-definitions-by-id unqualified))]
    (when (and defn
               (or (nil? category-id)
                   (= (as-keyword (:category-id defn)) category-id)))
      {:id (:id defn)
       :skill-id (:id defn)
       :category-id (:category-id defn)
       :ctrl-id (:id defn)
       :controllable? (:controllable? defn)
       :level (:level defn)
       :name (name (:id defn))})))

(defn- resolve-learned-skill-specs
  "Resolve learned ids through the skill registry (list + direct lookup).

  Prefer list-skills ∩ learned over category ∩ learned: category indexes can
  drift (:migrated EDN) while learn_all still wrote real skill ids. Fall back
  to per-id get-skill/raw-skill so namespaced ids still resolve. If the
  registry is empty on the client, synthesize from skill-definitions."
  [learned-ids category-id]
  (let [learned-set (set learned-ids)
        learned? (fn [spec]
                   (boolean (some learned-set (skill-id-aliases (spec-skill-id spec)))))
        from-list (filterv learned? (skill-query/list-skills))
        from-direct (keep (fn [sid]
                            (or (skill-registry/raw-skill sid)
                                (skill-registry/get-skill sid)
                                (when-let [n (some-> sid as-keyword name)]
                                  (or (skill-registry/raw-skill (keyword n))
                                      (skill-registry/get-skill (keyword n))))))
                          learned-ids)
        from-defs (when (and (empty? from-list) (empty? (keep identity from-direct)))
                    (keep #(synthesize-spec-from-definitions % category-id) learned-ids))
        canonical (distinct-specs-by-id (concat from-list from-direct from-defs))
        in-cat (if category-id
                 (filterv #(= (as-keyword (:category-id %)) category-id) canonical)
                 canonical)]
    ;; Category mismatch on every learned skill → still show them rather than
    ;; an empty picker after learn_all.
    (if (seq in-cat) in-cat canonical)))

(defn- available-skill-entry
  [s]
  (let [sid (spec-skill-id s)]
    {:skill-id sid
     :skill-name (let [nk (:name-key s)]
                   (if nk (or (i18n/translate nk) (name sid))
                       (or (:name s) (name sid))))
     :skill-icon (skill-query/get-skill-icon-path sid)
     :cat-id (as-keyword (:category-id s))
     :ctrl-id (as-keyword (or (:ctrl-id s) sid))}))

(defn- learned-count
  [ability-data]
  (count (:learned-skills ability-data #{})))

(defn- peer-ability-with-learned
  "On integrated singleplayer the server and client share one JVM but use
   different runtime-store session keys. /aim learn_all writes the server
   partition; the picker reads the client partition. When ability sync has
   not landed yet, borrow the richer peer ability-data for the same uuid."
  [session-id player-uuid]
  (->> (store/list-sessions)
       (remove #{session-id})
       (keep (fn [sid]
               (when-let [st (store/get-player-state sid player-uuid)]
                 (:ability-data st))))
       (filter (fn [ad] (pos? (learned-count ad))))
       (sort-by learned-count >)
       first))

(defn heal-client-ability-projection!
  "If this client's :learned-skills is empty but another store session for the
   same player already has learned skills (typical SP after /aim learn_all),
   hydrate that ability-data into the client partition so the picker can see it."
  [owner]
  (let [owner-key (editor-owner-key owner)
        [session-id _screen player-uuid] owner-key]
    (read-model/ensure-player-state! owner-key)
    (let [local (store/get-player-state session-id player-uuid)
          local-n (learned-count (:ability-data local))]
      (when (zero? local-n)
        (when-let [peer-ad (peer-ability-with-learned session-id player-uuid)]
          (log/warn "preset-editor: client learned-skills empty; mirroring peer session"
                    {:player-uuid player-uuid
                     :client-session session-id
                     :peer-learned (learned-count peer-ad)})
          (command-rt/run-command-in-session!
           session-id player-uuid
           {:command :hydrate-player-state
            :ability-data peer-ad}
           {:mark-dirty? false})))))
  nil)

(defn build-preset-editor-render-data
  "Build complete preset editor render data.
   Returns nil if player state is unavailable."
  [owner]
  (heal-client-ability-projection! owner)
  (let [state (editor-state-snapshot owner)
        owner-key (editor-owner-key owner)]
    (when-let [_player-uuid (:player-uuid state)]
      (when-let [player-state (and owner-key (get-editor-player-state owner))]
        (let [ability-data (:ability-data player-state)
              preset-data (:preset-data player-state)
              category-id (as-keyword (:category-id ability-data))
              slots-data (normalize-slots-data (:slots preset-data {}))
              current-preset (:selected-preset state)
              active-preset (:active-preset preset-data 0)
              learned-ids (learned-skill-ids ability-data)
              assigned-ids (assigned-ctrl-ids slots-data current-preset)
              resolved (resolve-learned-skill-specs learned-ids category-id)
              learned-bindable (filterv skill-preset-selectable? resolved)
              available-for-preset (vec (remove #(skill-assigned? assigned-ids %)
                                                learned-bindable))]
          {:presets (range 4)
           :selected-preset current-preset
           :active-preset active-preset
           :all-preset-slots (presets-all-slots slots-data)
           :slots (mapv (fn [idx] (slot-info (get slots-data [current-preset idx]))) (range 4))
           :available-skills (mapv available-skill-entry available-for-preset)
           :debug {:learned-count (count learned-ids)
                   :resolved-count (count resolved)
                   :bindable-count (count learned-bindable)
                   :assigned-count (count assigned-ids)
                   :available-count (count available-for-preset)
                   :category-id category-id}})))))

(defn selector-debug-snapshot
  "Return a compact diagnostic map for selector filtering.
   Used only for runtime troubleshooting of missing skills in preset editor."
  [owner]
  (heal-client-ability-projection! owner)
  (let [state (editor-state-snapshot owner)
        owner-key (editor-owner-key owner)]
    (when-let [_player-uuid (:player-uuid state)]
      (when-let [player-state (and owner-key (get-editor-player-state owner))]
        (let [ability-data (:ability-data player-state)
              preset-data (:preset-data player-state)
              category-id (as-keyword (:category-id ability-data))
              learned (learned-skill-ids ability-data)
              slots-data (normalize-slots-data (:slots preset-data {}))
              current-preset (:selected-preset state)
              assigned-ids (assigned-ctrl-ids slots-data current-preset)
              resolved (resolve-learned-skill-specs learned category-id)
              selectable (filterv skill-preset-selectable? resolved)
              available-for-preset (vec (remove #(skill-assigned? assigned-ids %)
                                                selectable))]
          {:category-id category-id
           :selected-preset current-preset
           :active-preset (:active-preset preset-data 0)
           :learned-raw-count (learned-count ability-data)
           :learned-ids (vec (sort (map name learned)))
           :slots (into {}
                        (for [idx (range 4)]
                          [idx (get slots-data [current-preset idx])]))
           :assigned-ctrl-ids (vec (sort (map name assigned-ids)))
           :resolved-ids (vec (sort (map (comp name spec-skill-id) resolved)))
           :selectable-ids (vec (sort (map (comp name spec-skill-id) selectable)))
           :available-skill-ids (vec (sort (map (comp name spec-skill-id) available-for-preset)))})))))

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defn on-preset-tab-click
  "Handle preset tab click."
  [owner preset-idx]
  (swap-editor-state! owner assoc :selected-preset preset-idx))

(defn open-screen!
  "Open preset editor screen."
  [owner]
  (let [owner-key (editor-owner-key owner)
        player-uuid (nth owner-key 2)]
    ;; Same hydrate path as skill-tree: avoid a nil player-state projection
    ;; that collapses render-data (and the selector) to empty.
    (read-model/ensure-player-state! owner-key)
    (heal-client-ability-projection! owner)
    (managed-screens/set-active-owner! screen-id owner-key)
    (swap-editor-state! owner merge default-editor-state {:player-uuid player-uuid}))
  {:command :open-screen
   :screen-type :preset-editor})

(defn close-screen!
  "Close preset editor screen."
  ([owner]
   (managed-screens/clear-screen-state! screen-id (editor-owner-key owner))))
