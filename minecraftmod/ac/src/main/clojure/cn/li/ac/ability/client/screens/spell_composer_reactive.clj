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

(def ^:private reject-labels
  {:over-complexity "Spell is too complex for your current mastery."
   :forbidden-effect "Spell uses an effect players are not allowed to cast."
   :over-budget "Spell exceeds the host-command/iteration budget."})

(defn- owner-for [player-uuid]
  (read-model/local-client-owner player-uuid "spell-composer"))

;; --- pure state ------------------------------------------------------------

(defn- initial-state []
  {:catalog (combat-api/player-glyph-catalog)
   :form nil
   :effects []
   :status "Pick a form, then add effects/augments, then Cast."})

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

;; --- render-state ------------------------------------------------------

(defn- palette-item [{:keys [glyph kind cost admissible?]}]
  {:glyph (glyph-str glyph) :kind (name kind)
   :cost (double cost)
   :label (str (glyph-str glyph) " (cost " cost ")")
   :admissible? admissible?})

(defn- render-state [state]
  (let [{:keys [catalog form effects status]} state
        forms (filter #(= :form (:kind %)) catalog)
        others (remove #(= :form (:kind %)) catalog)]
    {:title "Spell Composer"
     :form-palette (mapv palette-item (filter :admissible? forms))
     :effect-palette (mapv palette-item (filter :admissible? others))
     :form-label (if form (glyph-str (:glyph form)) "(none)")
     :effect-slots (mapv (fn [g] {:label (glyph-str (:glyph g))}) effects)
     :can-cast? (boolean (and form (seq effects)))
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
      (if-not glyphs
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
        vm (presentation/mount-view!
            {:view-id :academy.app/spell-composer
             :host-kind :screen
             :state (render-state @state*)
             :dispatch-action! (fn [action payload _current] (handle-action state* owner action payload))
             :on-close #(swap! active-mounts dissoc (str player-uuid))})]
    (swap! active-mounts assoc (str player-uuid) {:mount (:mount vm) :state* state*})
    vm))
