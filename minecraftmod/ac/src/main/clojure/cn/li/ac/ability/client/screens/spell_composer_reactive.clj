(ns cn.li.ac.ability.client.screens.spell-composer-reactive
  "Presentation Runtime controller for the player spell composer
   (node-editor plan Phase 5). Follows preset-editor-reactive's
   self-contained active-mounts pattern, same as the node editor screen.

   State is shaped to make an INVALID glyph sequence unrepresentable
   rather than caught after the fact: the combat player-spell desugar
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

(defn- glyph-short
  "UI chip label: the name segment only (\"touch\"), keeping the
   namespace visible in the composition pane via glyph-str."
  [kw]
  (name kw))

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
   :over-budget "Spell exceeds the host-command/iteration budget."})

(def ^:private kind-rgba
  {:form [0.55 0.85 1.0 1.0]
   :effect [1.0 1.0 1.0 1.0]
   :augment [0.7 1.0 0.65 1.0]})

(def ^:private selected-rgba [1.0 1.0 0.55 1.0])
(def ^:private idle-rgba [0.92 0.92 0.92 1.0])
(def ^:private muted-rgba [0.45 0.45 0.48 0.7])
(def ^:private cast-ready-rgba [0.55 1.0 0.55 1.0])
(def ^:private cast-blocked-rgba [0.45 0.45 0.45 0.55])

(defn- owner-for [player-uuid]
  (read-model/local-client-owner player-uuid "spell-composer"))

;; --- pure state ------------------------------------------------------------

(defn- initial-state []
  {:catalog (combat-api/player-glyph-catalog)
   :form nil
   :effects []
   :status "Pick a form, then add effects, then Cast."})

(defn- pick-form [state glyph-kw]
  (assoc state :form {:glyph glyph-kw} :status (str "Form: " (glyph-str glyph-kw))))

(defn- add-effect [state glyph-kw]
  (if (:form state)
    (update state :effects conj {:glyph glyph-kw})
    (assoc state :status "Pick a form first.")))

(defn- clear-composition [state]
  (assoc state :form nil :effects [] :status "Cleared."))

(defn- composed-glyphs [{:keys [form effects]}]
  (when form (into [form] effects)))

(defn- catalog-by-glyph [catalog]
  (into {} (map (juxt :glyph identity) catalog)))

(defn- total-cost [state]
  (let [by (catalog-by-glyph (:catalog state))
        glyphs (or (composed-glyphs state) [])]
    (reduce (fn [^double acc g]
              (+ acc (double (or (:cost (by (:glyph g))) 0.0))))
            0.0
            glyphs)))

;; --- render-state ------------------------------------------------------

(defn- form-palette-item [entry selected-glyph]
  (let [g (:glyph entry)
        selected? (= g selected-glyph)]
    {:glyph (glyph-str g)
     :kind "form"
     :cost (double (:cost entry))
     :label (str (if selected? "▶ " "  ") (glyph-short g)
                 "  (" (:cost entry) ")")
     :rgba (if selected? selected-rgba idle-rgba)
     :admissible? (:admissible? entry)}))

(defn- effect-palette-item [entry form?]
  (let [g (:glyph entry)
        kind (:kind entry)]
    {:glyph (glyph-str g)
     :kind (name kind)
     :cost (double (:cost entry))
     :label (str "+ " (glyph-short g)
                 (when (pos? (double (:cost entry)))
                   (str "  (" (:cost entry) ")")))
     :rgba (if form?
             (get kind-rgba kind idle-rgba)
             muted-rgba)
     :admissible? (:admissible? entry)}))

(defn- composition-row [i {:keys [glyph]} kind]
  {:index i
   :label (str (inc i) ". [" (name kind) "] " (glyph-str glyph))
   :rgba (get kind-rgba kind idle-rgba)})

(defn- render-state [state]
  (let [{:keys [catalog form effects status]} state
        selected-glyph (when form (:glyph form))
        forms (filter #(= :form (:kind %)) catalog)
        others (remove #(= :form (:kind %)) catalog)
        form? (some? form)
        can-cast? (boolean (and form (seq effects)))
        composition (vec
                     (concat
                      (when form
                        [(composition-row 0 form :form)])
                      (map-indexed
                       (fn [i g]
                         (let [entry (get (catalog-by-glyph catalog) (:glyph g))
                               kind (or (:kind entry) :effect)]
                           (composition-row (inc i) g kind)))
                       effects)))]
    {:title "Spell Composer"
     :form-header "Form (pick one)"
     :effect-header "Effects / Augments"
     :spell-header "Composition"
     :form-palette (mapv #(form-palette-item % selected-glyph)
                         (filter :admissible? forms))
     :effect-palette (mapv #(effect-palette-item % form?)
                           (filter :admissible? others))
     :composition (if (seq composition)
                    composition
                    [{:label "(empty — pick a form)"
                      :rgba muted-rgba}])
     :cost-label (str "Cost: " (total-cost state))
     :can-cast? can-cast?
     :cast-rgba (if can-cast? cast-ready-rgba cast-blocked-rgba)
     :status (or status "")
     :cast-label "Cast"
     :clear-label "Clear"}))

;; --- input handling ------------------------------------------------------

(defn- handle-action [state* owner action payload]
  (case action
    :composer/pick-form
    (swap! state* pick-form (keyword (:glyph (:item payload))))

    :composer/add-effect
    (swap! state* add-effect (keyword (:glyph (:item payload))))

    :composer/clear
    (swap! state* clear-composition)

    :composer/cast
    (let [glyphs (composed-glyphs @state*)]
      (if-not (and glyphs (seq (rest glyphs)))
        (swap! state* assoc :status "Pick a form and at least one effect first.")
        (do
          (swap! state* assoc :status "Casting...")
          (api/req-submit-spell!
           owner glyphs
           (fn [result]
             (swap! state* assoc :status
                    (case (:status result)
                      :accepted "Spell cast!"
                      :rejected (get reject-labels (:reason result)
                                     (str "Spell rejected: " (:reason result)))
                      (str "Unexpected result: " result))))))))

    nil)
  (render-state @state*))

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
