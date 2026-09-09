(ns cn.li.ac.ability.client.screens.spell-composer-reactive
  "Presentation Runtime controller for the player spell composer
   editor execution plan. Follows preset-editor-reactive's
   self-contained active-mounts pattern, same as the node editor screen.

   State is shaped so ordinary UI actions cannot create an INVALID glyph sequence
   while the combat layer still validates every packet at the server boundary: the combat player-spell desugar
   step requires the first glyph to be a :form/* and throws otherwise
   (an ex-info that would propagate uncaught through the compile-and-
   admit/dispatch path if it ever reached the server that way -- not
   verified here whether the network layer's handler dispatch catches
   an arbitrary handler exception, so this composer simply never
   constructs a sequence that could throw: :form is a single required
   slot picked before any :effects/:augments can be added, not a flat
   list a player could reorder into something illegal.

   The composed glyphs start with safe DEFAULT params (:amount 2.0, :range
   16.0, etc) and expose bounded numeric fields for the selected effect.
   Draft text is kept separate from committed params; submit validates
   finiteness and descriptor min/max before the spell can be cast.

   Physical glyph items / a spell-storage item's NBT (the plan's other
   P3 storage deliverable) is NOT part of this pass either -- both need a
   texture, a model json, and creative-tab placement this environment
   cannot create or visually verify, and neither is on the critical path
   for the compose-and-cast LOOP to work end to end (this screen can be
   opened directly, e.g. from a command, with no physical item
   involved). Deferred as a real, separate content-integration task."
  (:require [clojure.string :as str]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.combat.api :as combat-api]))

(defonce ^:private active-mounts (atom {}))

(defn- glyph-str
  "A glyph keyword -> its full :ns/name text, e.g. :form/touch ->
   \"form/touch\". NOT (name glyph) -- name silently strips a keyword's
   namespace segment, and every real glyph here IS namespaced (:form/*,
   :effect/*, :augment/*) -- the same class of bug the node language's
   own sigil-classification code warns about for the identical reason."
  [kw]
  (subs (str kw) 1))

(defn- ui-label
  "Keep single-line composer labels inside their fixed layout box.

   Presentation V4 currently clips but does not implement generic text
   ellipsizing.  Only derived display labels are shortened here; glyph data,
   status state and parameter drafts remain lossless."
  [value max-width]
  (let [s (str (or value ""))
        measure (fn [text]
                  (double (or (bridge/font-width-optional text)
                              (* 4.8 (count text)))))]
    (if (or (str/blank? s) (<= (measure s) (double max-width)))
      s
      (let [suffix "..."]
        (loop [n (count s)]
          (let [candidate (str (subs s 0 n) suffix)]
            (cond
              (<= (measure candidate) (double max-width)) candidate
              (zero? n) suffix
              :else (recur (dec n)))))))))

;; NOT routed through the mod's i18n/datagen translation system (checked
;; before writing this: every existing reactive controller's dynamic
;; :status text -- skill_tree.clj, settings_reactive.clj, about_reactive.
;; clj, this namespace's own :status assignments elsewhere -- is a
;; hardcoded English literal; ability-translation-map's datagen path is
;; for STATIC content Minecraft's own resource system needs a pre-
;; registered lang key for at build time (item/skill/achievement names),
;; not for a runtime status string a Clojure atom composes on the fly.
;; Giving reject reasons a second, inconsistent mechanism here would be
;; the actual gap, not the fix -- if per-locale dynamic status text is
;; ever wanted, it needs a mechanism this whole reactive-controller
;; family adopts together, not a one-off in this file.
(def ^:private reject-labels
  {:over-complexity "Spell is too complex for your current mastery."
   :forbidden-effect "Spell uses an effect players are not allowed to cast."
    :over-budget "Spell exceeds the host-command/iteration budget."
    :invalid-glyph "Spell contains invalid glyph data."})
(defn- owner-for [player-uuid]
  (read-model/local-client-owner player-uuid "spell-composer"))

;; --- pure state ------------------------------------------------------------

(defn- initial-state []
  (let [catalog (combat-api/player-glyph-catalog)
        specs (combat-api/player-glyph-specs)]
    {:catalog catalog
     :glyph-specs specs
     :form nil
     :effect-groups []
     :selected-effect nil
     :busy? false
     :status "Pick a form, then add effects and cast."}))

(defn- defaults-for [spec]
  (into {} (map (fn [[k descriptor]] [k (:default descriptor)])
                (:params spec))))

(defn- descriptor-for [state glyph]
  (get (:glyph-specs state) glyph))

(defn- glyph-entry [state glyph]
  (let [spec (descriptor-for state glyph)]
    {:glyph glyph :params (defaults-for spec)}))

(defn- pick-form [state glyph-kw]
  (if (= :form (:kind (descriptor-for state glyph-kw)))
    (assoc state :form (glyph-entry state glyph-kw)
           :status (str "Form: " (glyph-str glyph-kw)))
    (assoc state :status "Choose a form glyph.")))

(defn- add-effect [state glyph-kw]
  (cond
    (not (:form state)) (assoc state :status "Pick a form first.")
    (not= :effect (:kind (descriptor-for state glyph-kw)))
    (assoc state :status "Choose an effect glyph.")
    (>= (count (:effect-groups state)) 8)
    (assoc state :status "Maximum 8 effects per spell.")
    :else
    (let [group (assoc (glyph-entry state glyph-kw) :augments [])]
      (-> state
          (update :effect-groups conj group)
          (assoc :selected-effect (count (:effect-groups state))
                 :status (str "Added " (glyph-str glyph-kw) "."))))))

(defn- add-augment [state glyph-kw]
  (let [idx (:selected-effect state)
        groups (:effect-groups state)
        valid-index? (and (integer? idx) (<= 0 idx) (< idx (count groups)))]
    (cond
      (nil? (:form state)) (assoc state :status "Pick a form first.")
      (not valid-index?) (assoc state :status "Select an effect first.")
      (not= :augment (:kind (descriptor-for state glyph-kw)))
      (assoc state :status "Choose an augment glyph.")
      (>= (count (get-in groups [idx :augments])) 8)
      (assoc state :status "Maximum 8 augments on one effect.")
      :else
      (-> state
          (update-in [:effect-groups idx :augments] conj (glyph-entry state glyph-kw))
          (assoc :status (str "Added " (glyph-str glyph-kw) " to effect " (inc idx) "."))))))

(defn- remap-drafts-after-remove
  "Drop drafts for effect IDX and shift later effect indices left."
  [drafts idx]
  (into {}
        (keep (fn [[[effect-idx key] value]]
                (cond
                  (= effect-idx idx) nil
                  (> effect-idx idx) [[(dec effect-idx) key] value]
                  :else [[effect-idx key] value])))
        (or drafts {})))

(defn- remap-drafts-after-swap
  "Swap draft indices together with two reordered effect groups."
  [drafts idx target]
  (into {}
        (map (fn [[[effect-idx key] value]]
               [(cond
                  (= effect-idx idx) [target key]
                  (= effect-idx target) [idx key]
                  :else [effect-idx key])
                value]))
        (or drafts {})))
(defn- select-effect [state idx]
  (if (and (integer? idx)
           (<= 0 idx)
           (< idx (count (:effect-groups state))))
    (assoc state :selected-effect idx)
    (assoc state :status "Select a valid effect.")))

(defn- remove-effect [state idx]
  (if (and (integer? idx) (< -1 idx) (< idx (count (:effect-groups state))))
    (let [groups (vec (concat (subvec (:effect-groups state) 0 idx)
                               (subvec (:effect-groups state) (inc idx))))]
      (assoc state :effect-groups groups
             :param-drafts (remap-drafts-after-remove (:param-drafts state) idx)
             :selected-effect (when (seq groups) (min idx (dec (count groups))))
             :status "Effect removed."))
    state))

(defn- move-effect [state idx delta]
  (let [groups (:effect-groups state)]
    (if-not (and (integer? idx) (integer? delta))
      state
      (let [target (+ idx delta)]
        (if (and (<= 0 idx) (< idx (count groups))
                 (<= 0 target) (< target (count groups)))
          (let [item (nth groups idx)
                reordered (-> groups vec
                              (assoc idx (nth groups target))
                              (assoc target item))]
            (assoc state :effect-groups reordered
                   :param-drafts (remap-drafts-after-swap (:param-drafts state) idx target)
                   :selected-effect target))
          state)))))

(defn- remove-augment [state effect-idx augment-idx]
  (let [groups (:effect-groups state)
        valid-effect? (and (integer? effect-idx)
                           (<= 0 effect-idx)
                           (< effect-idx (count groups)))
        augments (when valid-effect? (get-in groups [effect-idx :augments]))]
    (if (and (integer? augment-idx)
             (<= 0 augment-idx)
             (< augment-idx (count augments)))
      (update-in state [:effect-groups effect-idx :augments]
                 #(vec (concat (subvec % 0 augment-idx) (subvec % (inc augment-idx)))))
      state)))

(defn- clear-composition [state]
  (assoc state :form nil :effect-groups [] :selected-effect nil :param-drafts {} :busy? false :status "Cleared."))

(defn- composed-glyphs [{:keys [form effect-groups]}]
  (when form
    (into [form]
          (mapcat (fn [group] (cons (dissoc group :augments) (:augments group)))
                  effect-groups))))

(declare payload-index)
(defn- selected-param-fields [{:keys [effect-groups selected-effect param-drafts glyph-specs]}]
  (if-let [group (and (integer? selected-effect) (get effect-groups selected-effect))]
    (let [params (:params (get glyph-specs (:glyph group)))]
      (mapv (fn [[key descriptor]]
              {:effect-index selected-effect
               :param-key key
               :draft-key (keyword (str "composer-param-" selected-effect "-" (name key)))
               :label (ui-label (str (name key) " [" (:min descriptor) ".." (:max descriptor) "]") 126.0)
               :value (str (get param-drafts [selected-effect key]
                                (get-in group [:params key])))})
            params))
    []))

(defn- parse-finite-number [value]
  (try
    (let [n (Double/parseDouble (str/trim (str value)))]
      (when (Double/isFinite n) n))
    (catch Exception _ nil)))

(defn- valid-param-drafts?
  "True when every in-progress parameter draft is finite and within its
   descriptor bounds. Invalid drafts never become part of the spell payload."
  [{:keys [param-drafts effect-groups glyph-specs]}]
  (every? (fn [[[idx key] raw]]
            (let [valid-index? (and (integer? idx)
                                   (<= 0 idx)
                                   (< idx (count effect-groups)))
                  glyph (when valid-index? (get-in effect-groups [idx :glyph]))
                  descriptor (when (and valid-index? (keyword? key))
                               (get-in glyph-specs [glyph :params key]))
                  value (parse-finite-number raw)]
              (and valid-index?
                   descriptor
                   (some? value)
                   (>= value (double (:min descriptor)))
                   (<= value (double (:max descriptor))))))
          (or param-drafts {})))
(defn- coerce-param-value [descriptor n]
  (if (= :int (:type descriptor)) (long (Math/round (double n))) (double n)))

(defn- param-change [state payload]
  (let [item (:item payload)
        idx (payload-index payload :effect-index)
        key (:param-key item)
        groups (:effect-groups state)
        glyph (when (and (integer? idx)
                         (<= 0 idx)
                         (< idx (count groups)))
                (get-in groups [idx :glyph]))
        descriptor (when (and glyph (keyword? key))
                     (get-in (:glyph-specs state) [glyph :params key]))
        value (or (:value payload) (:value item) (:text payload))]
    (if (and descriptor (some? value))
      (assoc-in state [:param-drafts [idx key]] (str value))
      state)))

(defn- param-submit [state payload]
  (let [item (:item payload)
        idx (payload-index payload :effect-index)
        key (:param-key item)
        effect-groups (:effect-groups state)
        valid-index? (and (integer? idx)
                           (<= 0 idx)
                           (< idx (count effect-groups)))
        glyph (when valid-index? (get-in effect-groups [idx :glyph]))
        descriptor (when (and valid-index? (keyword? key))
                     (get-in (:glyph-specs state) [glyph :params key]))
        value (parse-finite-number (or (:value payload) (:value item) (:text payload)))]
    (cond
      (not (and valid-index? (keyword? key) descriptor))
      (assoc state :status "Unknown parameter.")
      (nil? value)
      (assoc state :status (str "Enter a finite number for " (name key) "."))
      (< value (double (:min descriptor)))
      (assoc state :status (str (name key) " is below its minimum."))
      (> value (double (:max descriptor)))
      (assoc state :status (str (name key) " exceeds its maximum."))
      :else
      (-> state
          (assoc-in [:effect-groups idx :params key] (coerce-param-value descriptor value))
          (update :param-drafts dissoc [idx key])
          (assoc :status (str "Updated " (name key) "."))))))
;; --- render-state ------------------------------------------------------

(defn- palette-item [{:keys [glyph kind cost admissible? params]}]
  {:glyph (glyph-str glyph) :kind (name kind)
   :cost (double cost)
   :label (ui-label (str (glyph-str glyph) " (cost " cost ")") 202.0)
   :params params
   :admissible? admissible?})

(defn- render-state [state]
  (let [{:keys [catalog form effect-groups selected-effect status busy?]} state
        selected-params (selected-param-fields state)
        draft-state (into {}
                          (keep (fn [{:keys [draft-key value]}]
                                  (when draft-key [draft-key (str value)])))
                          selected-params)
        forms (filter #(= :form (:kind %)) catalog)
        effects (filter #(= :effect (:kind %)) catalog)
        augments (filter #(= :augment (:kind %)) catalog)]
    (merge draft-state
           {:title "Spell Composer"
     :form-palette (mapv palette-item (filter :admissible? forms))
     :effect-palette (mapv palette-item (filter :admissible? effects))
     :augment-palette (mapv palette-item (filter :admissible? augments))
     :form-label (ui-label (if form (glyph-str (:glyph form)) "(none)") 182.0)
     :selected-param-fields selected-params
     :effect-slots
     (mapv (fn [idx {:keys [glyph augments]}]
             (let [augment-height (* 14 (count augments))]
               {:index idx :label (ui-label (str (inc idx) ". " (glyph-str glyph)) 86.0)
                :selected? (= idx selected-effect)
                ;; The slot row grows with its augment list. Augments are
                ;; rendered as a vertical set of removable rows so eight
                ;; augments cannot overflow the fixed effect controls.
                :row-height (+ 16 augment-height)
                :augment-height augment-height
                :augment-label (when (seq augments)
                                 (str/join " " (map #(str "+" (glyph-str (:glyph %))) augments)))
                :augments (mapv (fn [augment-index a]
                                  {:effect-index idx
                                   :augment-index augment-index
                                   :label (ui-label (str "+ " (glyph-str (:glyph a))) 84.0)
                                   :remove-label "X"})
                                (range) augments)
                :can-move-up? (pos? idx)
                :can-move-down? (< idx (dec (count effect-groups)))
                :up-label "UP" :down-label "DN" :remove-label "X"}))
           (range) effect-groups)
     :can-cast? (boolean (and (not busy?) form (seq effect-groups)
                              (valid-param-drafts? state)))
     :busy? (boolean busy?)
     :status (ui-label (or status "") 456.0)
     :cast-label (if busy? "Casting..." "Cast")
     :clear-label "Clear"})))

;; --- input handling ------------------------------------------------------

(defn- payload-index [payload key]
  (let [value (or (get payload key) (get-in payload [:item key]))]
    (if (string? value) (try (Long/parseLong value) (catch Exception _ -1)) value)))

(defn- payload-keyword [value]
  (cond
    (keyword? value) value
    (string? value) (when (seq value) (keyword value))
    :else nil))

(defn- handle-action [state* owner action payload]
  (case action
    :composer/pick-form
    (swap! state* pick-form (payload-keyword (:glyph (:item payload))))

    :composer/add-effect
    (swap! state* add-effect (payload-keyword (:glyph (:item payload))))

    :composer/add-augment
    (swap! state* add-augment (payload-keyword (:glyph (:item payload))))

    :composer/select-effect
    (swap! state* select-effect (payload-index payload :index))

    :composer/remove-effect
    (swap! state* remove-effect (payload-index payload :index))

    :composer/move-effect-up
    (swap! state* move-effect (payload-index payload :index) -1)

    :composer/move-effect-down
    (swap! state* move-effect (payload-index payload :index) 1)

    :composer/remove-augment
    (swap! state* remove-augment
           (payload-index payload :effect-index)
           (payload-index payload :augment-index))

     :composer/param-change
     (swap! state* param-change payload)

     :composer/param-submit
     (swap! state* param-submit payload)

    :composer/clear
    (swap! state* clear-composition)

    :composer/cast
    (let [snapshot @state*
          glyphs (composed-glyphs snapshot)]
      (cond
        (:busy? snapshot) nil
        (not (and (:form snapshot) (seq (:effect-groups snapshot))))
        (swap! state* assoc :status "Pick a form and at least one effect first.")
        (not (valid-param-drafts? snapshot))
        (swap! state* assoc :status "Finish valid parameter edits before casting.")
        :else
        (let [analysis (combat-api/analyze-player-spell glyphs combat-api/player-spell-complexity-cap)]
          (if-not (:ok analysis)
            (swap! state* assoc :status
                   (get reject-labels (:reject analysis)
                        (str "Spell rejected: " (:reject analysis))))
            (do
              (swap! state* assoc :busy? true :status "Casting...")
              (api/req-submit-spell!
               owner glyphs
               (fn [result]
                 (swap! state* assoc :busy? false :status
                        (case (:status result)
                          :accepted "Spell cast!"
                          :rejected (get reject-labels (:reason result)
                                         (str "Spell rejected: " (:reason result)))
                          (str "Unexpected result: " result))))))))))

    nil)
  (render-state @state*))

;; --- mount ---------------------------------------------------------------
;; --- mount ---------------------------------------------------------------

(defn open! [player-uuid]
  (let [owner (owner-for player-uuid)
        state* (atom (initial-state))
        on-close #(swap! active-mounts dissoc (str player-uuid))
        vm (presentation/mount-view!
            {:view-id :academy.app/spell-composer
             :host-kind :screen
             :state (render-state @state*)
             :dispatch-action! (fn [action payload _current] (handle-action state* owner action payload))
             :on-close on-close})]
    (swap! active-mounts assoc (str player-uuid) {:mount (:mount vm) :state* state*})
    (bridge/call-adapter :presentation-open-screen!
                         (:mount vm) "Spell Composer" on-close)
    vm))

