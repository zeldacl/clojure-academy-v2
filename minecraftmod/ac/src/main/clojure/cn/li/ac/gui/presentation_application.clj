(ns cn.li.ac.gui.presentation-application
  "Small data-driven Screen host for non-container applications.

   Application-specific state and actions stay in AC Clojure.  The screen
   boundary receives only the opaque mount token, while this host owns the
   retained Presentation ViewModel and input policy."
  (:require [cn.li.presentation.core.host :as host]
            [cn.li.mcmod.client.platform-bridge :as client-bridge])
  (:import [cn.li.presentation.core ActionId ActionPayload ActionResult BindingTable
            PresentationViewModel PresentationInputEvent$Key PresentationInputEvent$Scroll
            PresentationInputEvent$CharacterInput
            EventResult]))

(def binding-ids
  {:title 0 :lines 1 :status 2 :scroll 3 :modal 4
   :button-left 5 :button-right 6 :input 7})

(def action-ids
  {0 :application/left
   1 :application/right
   2 :application/activate
   3 :application/delete})

(defn- value-for [state id]
  (case (int id)
    0 (str (:title @state ""))
    1 (vec (:lines @state []))
    2 (str (:status @state ""))
    3 (double (or (:scroll @state) 0.0))
    4 (:modal @state)
    5 (:button-left @state)
    6 (:button-right @state)
    7 (str (:input @state ""))
    nil))

(defn- binding-table [state]
  (reify BindingTable
    (value [_ id] (value-for state id))))

(defn- model [state dispatch-action!]
  (let [bindings (binding-table state)]
    (reify PresentationViewModel
      (bindings [_] bindings)
      (^ActionResult dispatch [_ ^ActionId action ^ActionPayload _payload]
        (if-let [action-key (get action-ids (.value action))]
          (do (dispatch-action! action-key @state)
              (ActionResult/accepted))
          (ActionResult/rejected "unknown application action")))
      (close [_] (reset! state {})))))

(defn- input-handler [state dispatch-action! on-close event]
  (cond
    (instance? PresentationInputEvent$Key event)
    (let [^PresentationInputEvent$Key key-event event]
      (if-not (.pressed key-event)
        EventResult/PASS
        (case (.keyCode key-event)
          256 (do (when on-close (on-close)) EventResult/CONSUME)
          263 (do (swap! state update :scroll #(max 0.0 (- (double (or % 0.0)) 1.0)))
                  (dispatch-action! :application/left @state)
                  EventResult/CONSUME)
          262 (do (swap! state update :scroll #(+ (double (or % 0.0)) 1.0))
                  (dispatch-action! :application/right @state)
                  EventResult/CONSUME)
          257 (do (dispatch-action! :application/activate @state)
                  EventResult/CONSUME)
          259 (do (swap! state update :input #(let [s (str (or % ""))]
                                                (if (seq s) (subs s 0 (dec (count s))) s)))
                  (dispatch-action! :application/input @state)
                  EventResult/CONSUME)
          261 (do (dispatch-action! :application/delete @state)
                  EventResult/CONSUME)
          EventResult/PASS)))

    (instance? PresentationInputEvent$CharacterInput event)
    (let [^PresentationInputEvent$CharacterInput char-event event
          text (str (.text char-event))]
      (when (and (seq text) (not (.composing char-event)))
        (swap! state update :input #(str (or % "") text))
        (dispatch-action! :application/input @state))
      EventResult/CONSUME)

    (instance? PresentationInputEvent$Scroll event)
    (let [^PresentationInputEvent$Scroll scroll-event event
          delta (double (.y scroll-event))]
      (swap! state update :scroll #(max 0.0 (+ (double (or % 0.0)) (- delta))))
      (dispatch-action! :application/scroll @state)
      EventResult/CONSUME)

    :else EventResult/PASS))

(defn mount!
  "Mount a generic application Screen and return its VM/mount/refresh handle.
   `snapshot` is an initial state map; `dispatch-action!` receives a keyword
   action and the current state and may update the supplied state atom through
   `:refresh!` or return a replacement state map.

   `host-kind` is normally `:screen`; finite HUD applications (for example a
   world interaction prompt) use `:hud` and therefore stay in the same
   Presentation frame without creating a second overlay renderer."
  ([owner title snapshot dispatch-action! on-close]
   (mount! owner title snapshot dispatch-action! on-close :screen))
  ([owner title snapshot dispatch-action! on-close host-kind]
  (let [api (client-bridge/call-adapter :presentation-host-api)
        state (atom (merge {:title title :lines [] :status "" :scroll 0.0}
                           snapshot))
        refresh! (fn [next-state]
                   (when (map? next-state)
                     (swap! state merge next-state))
                   @state)
        action! (fn [action current]
                  (let [result (dispatch-action! action current)]
                    (when (map? result) (swap! state merge result))
                    result))
        vm (model state action!)
        mount ((:mount! api) owner host-kind "academy:application" vm)]
    ((:set-input-handler! api) mount
      (fn [event] (input-handler state action! on-close event)))
    (when (= host-kind :screen)
      (client-bridge/call-adapter :presentation-open-screen!
                                   mount title on-close))
    {:mount mount :model vm :state state :refresh! refresh! :host-kind host-kind})))

(defn unmount!
  "Unmount an application regardless of whether it is a Screen or HUD host."
  [{:keys [mount]}]
  (when mount
    (when-let [api (client-bridge/call-adapter :presentation-host-api)]
      ((:unmount! api) mount)))
  nil)
