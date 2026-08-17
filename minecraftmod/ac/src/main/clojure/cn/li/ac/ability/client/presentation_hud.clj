(ns cn.li.ac.ability.client.presentation-hud
  "Combat HUD vertical slice for Presentation Runtime.

   The existing HUD builders remain the source of game projection data. This
   namespace is the AC ViewModel/Action controller boundary; it does not emit
   Minecraft draw calls or own hover/focus/animation state."
  (:require [cn.li.ac.ability.client.reactive-hud :as reactive-hud]
            [cn.li.presentation.core.host :as bridge])
  (:import [cn.li.presentation.core ActionId ActionPayload ActionResult BindingTable
            PresentationViewModel]))

(def binding-ids
  {:cp-ratio 0
   :overload-ratio 1
   :skills 2
   :selected-skill 3
   :cooldowns 4
   :crosshair 5
   :screen-flash-alpha 6})

(def action-ids
  {0 :combat/select-skill
   1 :combat/toggle-skill-wheel})

(defn- binding-value [snapshot id]
  (case (int id)
    0 (get-in snapshot [:cp-bar :percent] 0.0)
    1 (get-in snapshot [:overload-bar :percent] 0.0)
    2 (:skill-slots snapshot [])
    3 (:selected-skill snapshot)
    4 (mapv #(select-keys % [:skill-id :cooldown-remaining :cooldown-total])
            (:skill-slots snapshot []))
    5 (:crosshair snapshot)
    6 (:screen-flash-alpha snapshot 0.0)
    nil))

(defn- binding-table [snapshot-atom]
  (reify BindingTable
    (value [_ id] (binding-value @snapshot-atom id))))

;; :selected-skill and :skill-wheel-open? are UI-only state (which wedge is
;; highlighted, whether the wheel overlay is open) — reactive-hud/build-snapshot
;; never sets either, so refresh! must not clobber them when it pulls in a new
;; server-derived snapshot every frame.
(def ^:private ui-only-keys [:selected-skill :skill-wheel-open?])

(defn combat-view-model
  "Create the ViewModel for one local player. `dispatch-action!` is injected by
   AC so the core Runtime never knows game-specific skill semantics."
  [player-uuid dispatch-action!]
  (let [snapshot (atom {})
        last-action (atom nil)
        bindings (binding-table snapshot)
        model (reify PresentationViewModel
                (bindings [_] bindings)
                (^ActionResult dispatch [_ ^ActionId action ^ActionPayload payload]
                  ;; payload is the raw Java ActionPayload wrapper (empty or a
                  ;; capped byte array) — there is no decoder for structured
                  ;; click data (e.g. "which wedge") on this path yet, so
                  ;; :combat/select-skill cycles rather than jumping to an
                  ;; index the payload can't actually carry today.
                  (let [action-key (get action-ids (.value action))]
                    (case action-key
                      :combat/select-skill
                      (do (swap! snapshot (fn [s]
                                            (let [n (max 1 (count (:skill-slots s [])))
                                                  current (long (or (:selected-skill s) -1))]
                                              (assoc s :selected-skill (mod (inc current) n)))))
                          (reset! last-action [action-key payload])
                          (dispatch-action! action-key payload)
                          (ActionResult/accepted))

                      :combat/toggle-skill-wheel
                      (do (swap! snapshot update :skill-wheel-open? not)
                          (reset! last-action [action-key payload])
                          (dispatch-action! action-key payload)
                          (ActionResult/accepted))

                      nil (ActionResult/rejected "unknown combat HUD action")

                      (do (reset! last-action [action-key payload])
                          (dispatch-action! action-key payload)
                          (ActionResult/accepted)))))
                (close [_] (reset! snapshot {})))]
    {:model model
     :snapshot snapshot
     :bindings bindings
     :last-action last-action
     :refresh! (fn [screen-w screen-h opts]
                 (let [ui-state (select-keys @snapshot ui-only-keys)]
                   (reset! snapshot (merge (reactive-hud/build-snapshot player-uuid screen-w screen-h opts)
                                           ui-state)))
                 @snapshot)}))

(defn mount-combat-hud!
  "Mount the HUD through the version-neutral bridge. The bridge owns host
   lifecycle and Runtime state; AC only supplies template/model/content."
  [runtime player-uuid screen-w screen-h opts dispatch-action!]
  (let [{:keys [model refresh!] :as vm} (combat-view-model player-uuid dispatch-action!)
        _ (refresh! screen-w screen-h opts)
        handle (bridge/mount-host! runtime :combat-hud :hud "academy:combat_hud" model)]
    (assoc vm :mount handle)))
