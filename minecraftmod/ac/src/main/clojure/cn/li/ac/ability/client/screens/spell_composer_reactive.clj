(ns cn.li.ac.ability.client.screens.spell-composer-reactive
  "Presentation Runtime controller for the player spell composer
   (node-editor plan Phase 5). Follows preset-editor-reactive's
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

   The composed glyphs carry only DEFAULT params (:amount 2.0, :range
   16.0, etc -- the same fallbacks the combat player-spell form/effect
   statement builders already use) -- no per-glyph parameter tuning UI
   in this pass. A reasonable follow-up once the basic compose-and-cast
   loop has been used in-game, not a silent gap: the composer works
   completely without it today, just with fixed glyph strengths.

   Physical glyph items / a spell-storage item's NBT (the plan's other
   Phase 5 deliverable) are NOT part of this pass either -- both need a
   texture, a model json, and creative-tab placement this environment
   cannot create or visually verify, and neither is on the critical path
   for the compose-and-cast LOOP to work end to end (this screen can be
   opened directly, e.g. from a command, with no physical item
   involved). Deferred as a real, separate content-integration task."
  (:require [cn.li.ac.gui.presentation :as presentation]
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
        groups (:effect-groups state)]
    (cond
      (nil? (:form state)) (assoc state :status "Pick a form first.")
      (nil? idx) (assoc state :status "Select an effect first.")
      (not= :augment (:kind (descriptor-for state glyph-kw)))
      (assoc state :status "Choose an augment glyph.")
      (>= (count (get-in groups [idx :augments])) 8)
      (assoc state :status "Maximum 8 augments on one effect.")
      :else
      (-> state
          (update-in [:effect-groups idx :augments] conj (glyph-entry state glyph-kw))
          (assoc :status (str "Added " (glyph-str glyph-kw) " to effect " (inc idx) "."))))))

(defn- remove-effect [state idx]
  (if (and (integer? idx) (< -1 idx) (< idx (count (:effect-groups state))))
    (let [groups (vec (concat (subvec (:effect-groups state) 0 idx)
                               (subvec (:effect-groups state) (inc idx))))]
      (assoc state :effect-groups groups
             :selected-effect (when (seq groups) (min idx (dec (count groups))))
             :status "Effect removed."))
    state))

(defn- move-effect [state idx delta]
  (let [groups (:effect-groups state) target (+ idx delta)]
    (if (and (integer? idx) (integer? delta) (<= 0 idx) (< idx (count groups))
             (<= 0 target) (< target (count groups)))
      (let [item (nth groups idx)
            reordered (-> groups vec
                          (assoc idx (nth groups target))
                          (assoc target item))]
        (assoc state :effect-groups reordered :selected-effect target))
      state)))

(defn- remove-augment [state effect-idx augment-idx]
  (if (and (<= 0 effect-idx) (< effect-idx (count (:effect-groups state)))
           (<= 0 augment-idx) (< augment-idx (count (get-in state [:effect-groups effect-idx :augments]))))
    (update-in state [:effect-groups effect-idx :augments]
               #(vec (concat (subvec % 0 augment-idx) (subvec % (inc augment-idx)))))
    state))

(defn- clear-composition [state]
  (assoc state :form nil :effect-groups [] :selected-effect nil :busy? false :status "Cleared."))

(defn- composed-glyphs [{:keys [form effect-groups]}]
  (when form
    (into [form]
          (mapcat (fn [group] (cons (dissoc group :augments) (:augments group)))
                  effect-groups))))

;; --- render-state ------------------------------------------------------

(defn- palette-item [{:keys [glyph kind cost admissible? params]}]
  {:glyph (glyph-str glyph) :kind (name kind)
   :cost (double cost)
   :label (str (glyph-str glyph) " (cost " cost ")")
   :params params
   :admissible? admissible?})

(defn- render-state [state]
  (let [{:keys [catalog form effect-groups selected-effect status busy?]} state
        forms (filter #(= :form (:kind %)) catalog)
        effects (filter #(= :effect (:kind %)) catalog)
        augments (filter #(= :augment (:kind %)) catalog)]
    {:title "Spell Composer"
     :form-palette (mapv palette-item (filter :admissible? forms))
     :effect-palette (mapv palette-item (filter :admissible? effects))
     :augment-palette (mapv palette-item (filter :admissible? augments))
     :form-label (if form (glyph-str (:glyph form)) "(none)")
     :effect-slots
     (mapv (fn [idx {:keys [glyph augments]}]
             {:index idx :label (str (inc idx) ". " (glyph-str glyph))
              :selected? (= idx selected-effect)
              :augments (mapv (fn [a] {:label (glyph-str (:glyph a))}) augments)
              :can-move-up? (pos? idx)
              :can-move-down? (< idx (dec (count effect-groups)))
              :up-label "UP" :down-label "DN" :remove-label "X"})
           (range) effect-groups)
     :can-cast? (boolean (and (not busy?) form (seq effect-groups)))
     :busy? (boolean busy?)
     :status (or status "")
     :cast-label (if busy? "Casting..." "Cast")
     :clear-label "Clear"}))

;; --- input handling ------------------------------------------------------

(defn- payload-index [payload key]
  (let [value (or (get payload key) (get-in payload [:item key]))]
    (if (string? value) (try (Long/parseLong value) (catch Exception _ -1)) value)))

(defn- handle-action [state* owner action payload]
  (case action
    :composer/pick-form
    (swap! state* pick-form (keyword (:glyph (:item payload))))

    :composer/add-effect
    (swap! state* add-effect (keyword (:glyph (:item payload))))

    :composer/add-augment
    (swap! state* add-augment (keyword (:glyph (:item payload))))

    :composer/select-effect
    (swap! state* assoc :selected-effect (payload-index payload :index))

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

    :composer/clear
    (swap! state* clear-composition)

    :composer/cast
    (let [snapshot @state*
          glyphs (composed-glyphs snapshot)]
      (cond
        (:busy? snapshot) nil
        (not glyphs) (swap! state* assoc :status "Pick a form and at least one effect first.")
        :else
        (let [analysis (combat-api/analyze-player-spell glyphs 20)]
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
