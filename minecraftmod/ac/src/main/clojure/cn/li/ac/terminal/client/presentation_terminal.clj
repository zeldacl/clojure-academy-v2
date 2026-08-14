(ns cn.li.ac.terminal.client.presentation-terminal
  "Terminal Presentation Runtime ViewModel.

   Menu/network authority stays in the existing terminal runtime and server
   protocol. This layer exposes only a typed snapshot and numeric UI actions."
  (:require [cn.li.ac.terminal.client.runtime :as terminal]
            [cn.li.presentation.core.host :as host]
            [cn.li.mcmod.client.platform-bridge :as client-bridge])
  (:import [cn.li.presentation.core ActionId ActionPayload ActionResult BindingTable
            PresentationViewModel PresentationInputEvent$Key
            PresentationInputEvent$CharacterInput EventResult]))

(def binding-ids
  {:installed? 0
   :apps 1
   :page 2
   :loading? 3
   :query 4
   :modal 5})

(def action-ids
  {0 :terminal/set-page
   1 :terminal/query
   2 :terminal/install-app
   3 :terminal/uninstall-app
   4 :terminal/submit-query
   5 :terminal/close-modal})

(defn- binding-value [state id]
  (case (int id)
    0 (boolean (:terminal-installed? state))
    1 (vec (:available-apps state []))
    2 (int (:page state 0))
    3 (boolean (:loading? state))
    4 (str (:query state ""))
    5 (:modal state)
    nil))

(defn- binding-table [state-atom]
  (reify BindingTable
    (value [_ id] (binding-value @state-atom id))))

(defn- input-handler [owner dispatch-action! event]
  (cond
    (instance? PresentationInputEvent$CharacterInput event)
    (let [^PresentationInputEvent$CharacterInput input event
          current (str (:query (terminal/state-snapshot owner) ""))]
      (terminal/dispatch-event! owner :terminal/set-query
                                {:query (str current (.text input))})
      EventResult/CONSUME)

    (instance? PresentationInputEvent$Key event)
    (let [^PresentationInputEvent$Key input event
          key (.keyCode input)]
      (if-not (.pressed input)
        EventResult/PASS
        (case key
          257 (do (dispatch-action! :terminal/submit-query
                                     {:query (:query (terminal/state-snapshot owner) "")})
                  EventResult/CONSUME)
          259 (do (let [query (str (:query (terminal/state-snapshot owner) ""))]
                    (terminal/dispatch-event! owner :terminal/set-query
                                              {:query (if (seq query) (subs query 0 (dec (count query))) "")}))
                  EventResult/CONSUME)
          263 (do (dispatch-action! :terminal/set-page
                                     {:page (dec (int (:page (terminal/state-snapshot owner) 0)))})
                  EventResult/CONSUME)
          262 (do (dispatch-action! :terminal/set-page
                                     {:page (inc (int (:page (terminal/state-snapshot owner) 0)))})
                  EventResult/CONSUME)
          EventResult/PASS)))

    :else EventResult/PASS))

(defn terminal-view-model [owner dispatch-action!]
  (let [state (atom (terminal/state-snapshot owner))
        last-action (atom nil)
        bindings (binding-table state)
        model (reify PresentationViewModel
                (bindings [_] bindings)
                (^ActionResult dispatch [_ ^ActionId action ^ActionPayload payload]
                  (let [action-key (get action-ids (.value action))]
                    (if-not action-key
                      (ActionResult/rejected "unknown terminal action")
                      (do
                        (reset! last-action [action-key payload])
                        (dispatch-action! action-key payload)
                        (ActionResult/accepted)))))
                (close [_] (reset! state {})))]
    {:model model
     :state state
     :bindings bindings
     :last-action last-action
     :refresh! (fn [] (reset! state (terminal/state-snapshot owner)) @state)}))

(defn mount-terminal!
  [runtime owner dispatch-action!]
  (terminal/ensure-owner! owner)
  (let [{:keys [model refresh!] :as vm} (terminal-view-model owner dispatch-action!)
        _ (refresh!)
        handle (host/mount-host! runtime :terminal :screen "academy:terminal" model)]
    (host/set-input-handler! runtime handle
                             (partial input-handler owner dispatch-action!))
    (assoc vm :mount handle)))

(defn open-screen!
  "Mount the Terminal ViewModel and hand its opaque mount to the version
   Screen boundary.  Minecraft-specific lifecycle remains outside this ns."
  [owner dispatch-action! on-close]
  (let [api (client-bridge/call-adapter :presentation-host-api)
        mount ((:mount-terminal! api) owner dispatch-action!)]
    (client-bridge/call-adapter :presentation-open-screen!
                                 mount "Terminal" on-close)
    mount))
