(ns cn.li.ac.gui.presentation-container
  "Generic Container/Slot ViewModel sample used by machine GUIs.

   Server Menu/Slot state is supplied through mcmod's MenuBridge. Presentation
   Runtime only receives a snapshot and dispatches validated actions."
  (:require [cn.li.mcmod.gui.presentation-menu-bridge :as menu-bridge]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.wireless.gui.container.common :as container-common]
            [cn.li.presentation.core.host :as host]
            [cn.li.mcmod.client.platform-bridge :as client-bridge])
  (:import [cn.li.presentation.core ActionId ActionPayload ActionResult BindingTable
            PresentationViewModel EventResult PresentationInputEvent$Pointer
            PresentationInputEvent$Pointer$Type PresentationInputEvent
            PresentationInputEvent$Key PresentationInputEvent$CharacterInput]))

(def binding-ids
  {:slots 0 :slot-anchors 1 :energy-ratio 2 :progress-ratio 3 :machine-state 4
   :button-left 5 :button-right 6
   :network-state 7 :network-owner 8 :network-range 9 :network-bandwidth 10
   :network-load 11 :network-ssid 12 :network-password 13
   :node-name 14})

(def action-ids
  {0 :container/click-slot
   1 :container/quick-move
   2 :container/button})

(def ^:private max-text-length 256)

(defn- value-for [snapshot id]
  (let [values (:values snapshot {})]
    (case (int id)
      0 (:slots values [])
      1 (:slot-anchors snapshot [])
      2 (double (or (:energy-ratio values) 0.0))
      3 (double (or (:progress-ratio values) 0.0))
      4 (:machine-state values)
      5 (:button-left values)
      6 (:button-right values)
      7 (:network-state values)
      8 (:network-owner values)
      9 (:network-range values)
      10 (:network-bandwidth values)
      11 (:network-load values)
      12 (:network-ssid values)
      13 (:network-password values)
      14 (:node-name values)
      nil)))

(defn container-view-model [menu-bridge snapshot-fn dispatch-action!]
  (let [snapshot (atom (snapshot-fn))
        bindings (reify BindingTable
                   (value [_ id] (value-for @snapshot id)))
        model (reify PresentationViewModel
                (bindings [_] bindings)
                (^ActionResult dispatch [_ ^ActionId action ^ActionPayload payload]
                  (if-let [action-key (get action-ids (.value action))]
                    (do (menu-bridge/dispatch-action menu-bridge action-key payload dispatch-action!)
                        (ActionResult/accepted))
                    (ActionResult/rejected "unknown container action")))
                (close [_] (reset! snapshot {})))]
    {:model model
     :snapshot snapshot
     :refresh! (fn [] (reset! snapshot (snapshot-fn)) @snapshot)}))

(defn mount-container!
  [runtime menu-bridge snapshot-fn dispatch-action!]
  (let [{:keys [model refresh!] :as vm}
        (container-view-model menu-bridge snapshot-fn dispatch-action!)]
    (refresh!)
    (assoc vm :mount (host/mount-host! runtime :container :screen
                                       "academy:machine_container" model))))

(defn open-screen!
  "Mount a container ViewModel and open the opaque version Screen boundary."
  [menu-bridge snapshot-fn dispatch-action! on-close]
  (let [api (client-bridge/call-adapter :presentation-host-api)
        mount ((:mount-container! api) menu-bridge snapshot-fn dispatch-action!)]
    (client-bridge/call-adapter :presentation-open-screen!
                                 mount "Container" on-close)
    mount))

(defn- value-of [value]
  (if (instance? clojure.lang.IDeref value) @value value))

(defn- slot-value [container index]
  (try
    (if (map? (:tile-entity container))
      (container-common/get-slot-item container index)
      (container-common/get-slot-item-be container index))
    (catch Exception _ nil)))

(defn- snapshot-for [container revision slot-count]
  (let [energy (double (or (value-of (:energy container)) 0.0))
        max-energy (max 1.0 (double (or (value-of (:max-energy container)) 1.0)))
        progress (double (or (value-of (:progress container)) 0.0))
        max-progress (max 1.0 (double (or (value-of (:max-progress container)) 1.0)))]
    {:revision @revision
     :values {:slots (mapv #(slot-value container %) (range slot-count))
              :energy-ratio (max 0.0 (min 1.0 (/ energy max-energy)))
              :progress-ratio (max 0.0 (min 1.0 (/ progress max-progress)))
              :machine-state (or (value-of (:status container))
                                 (value-of (:machine-state container))
                                 (value-of (:mode container))
                                 "IDLE")}}))

(defn- pointer-action-handler [bridge anchors dispatch-action! focus* event]
  (if (instance? PresentationInputEvent$Pointer event)
    (let [^PresentationInputEvent$Pointer pointer event]
      (if (= PresentationInputEvent$Pointer$Type/DOWN (.type pointer))
        (if-let [anchor (some (fn [anchor]
                                (when (and (<= (double (:x anchor)) (double (.x pointer))
                                              (+ (double (:x anchor)) (:width anchor)))
                                           (<= (double (:y anchor)) (double (.y pointer))
                                              (+ (double (:y anchor)) (:height anchor))))
                                  anchor))
                              anchors)]
          (let [action (or (:action anchor) :container/click-slot)
                payload (cond-> {:button (.button pointer)}
                          (contains? anchor :slot-index) (assoc :slot-index (:slot-index anchor))
                          (:button-id anchor) (assoc :button-id (:button-id anchor)))]
            (if (= action :container/focus-field)
              (do (reset! focus* (:field-id anchor)) EventResult/CONSUME)
              (do
                (menu-bridge/dispatch-action bridge action payload dispatch-action!)
                ;; Native slots remain authoritative; custom Presentation
                ;; buttons consume only their own click region.
                (if (= action :container/button) EventResult/CONSUME EventResult/PASS))))
          EventResult/PASS)
        EventResult/PASS))
    EventResult/PASS))

(defn- text-input-handler [container player fields focus* edit-state event]
  "Handle text editing state owned by the Presentation Container runtime.

   The content callback receives only committed values; caret/focus routing is
   kept here so Matrix/Node do not each implement a second input pipeline."
  (when-let [field @focus*]
    (let [field-spec (some #(when (= field (:id %)) %) fields)
          initial (when-let [value-fn (:value-fn field-spec)]
                    (value-fn container player))
          current (str (get @edit-state field (or initial "")))]
      (cond
        (instance? PresentationInputEvent$CharacterInput event)
        (let [text (str (.text ^PresentationInputEvent$CharacterInput event))]
          (when (seq text)
            (let [next (subs (str current text)
                             0 (min max-text-length (count (str current text))))]
              (swap! edit-state assoc field next)
              (when-let [changed (:presentation-text-change! container)]
                (changed field next))))
          EventResult/CONSUME)

        (instance? PresentationInputEvent$Key event)
        (let [^PresentationInputEvent$Key key event]
          (cond
            (and (.pressed key) (= 259 (.keyCode key)))
            (do (swap! edit-state assoc field (if (seq current) (subs current 0 (dec (count current))) ""))
                (when-let [changed (:presentation-text-change! container)]
                  (changed field (get @edit-state field)))
                EventResult/CONSUME)
            (and (.pressed key) (= 257 (.keyCode key)))
            (do (when-let [submit (:presentation-text-submit! container)]
                  (submit field current))
                EventResult/CONSUME)
            :else EventResult/PASS))

        :else EventResult/PASS))))

(defn presentation-screen-data
  "Build the opaque screen-data consumed by a version Presentation boundary.

   `schema-id` resolves slot anchors from mcmod's neutral slot schema. The
   server menu itself is never mirrored: only a snapshot and validated action
   callback cross into Presentation Runtime."
  [container menu player schema-id template-id]
  (let [revision (atom 0)
        focus* (atom nil)
        edit-state (atom {})
        layout (or (slot-schema/get-slot-layout schema-id) {:slots []})
        slot-anchors (mapv (fn [{:keys [index x y]}]
                             {:slot-index index :x x :y y :width 16 :height 16 :visible? true})
                           (:slots layout))
        button-anchors (mapv (fn [{:keys [id x y width height button-id label rgba]
                                   :or {width 24 height 18}}]
                               {:id id :action :container/button :button-id button-id
                                :x x :y y :width width :height height :label label :rgba rgba})
                             (or (:presentation-buttons container) []))
        text-fields (vec (or (:presentation-text-fields container) []))
        text-anchors (mapv (fn [{:keys [id x y width height]
                                 :or {width 96 height 18}}]
                             {:id id :action :container/focus-field :field-id id
                              :x x :y y :width width :height height :visible? true})
                           text-fields)
        anchors (into (into slot-anchors button-anchors) text-anchors)
        bridge (menu-bridge/create (or (:container-type container) schema-id)
                                   anchors
                                   #{:container/click-slot :container/quick-move
                                     :container/button})
        snapshot-fn (fn []
                      (swap! revision inc)
                      (let [base-values (:values (snapshot-for container revision
                                                                (count slot-anchors)))
                            extra-values (if-let [snapshot! (:presentation-snapshot-fn container)]
                                           (or (snapshot! container player) {}) {})
                            values (merge base-values extra-values)
                            values-with-fields (reduce (fn [result field]
                                             (let [binding-key (:binding-key field)
                                                   value-fn (:value-fn field)
                                                   initial (when value-fn (value-fn container player))
                                                   value (get @edit-state (:id field) initial)]
                                               (if binding-key
                                                 (assoc result binding-key {:text (str (or value ""))
                                                                            :focused? (= @focus* (:id field))})
                                                 result)))
                                           values
                                           text-fields)
                            button-value (fn [id]
                                           (some (fn [button]
                                                   (when (= id (:id button))
                                                     (select-keys button [:x :y :width :height :label :rgba])))
                                                 button-anchors))]
                        (menu-bridge/update-snapshot!
                          bridge @revision
                          (assoc values-with-fields
                                 :button-left (button-value :left)
                                 :button-right (button-value :right))))
                      (menu-bridge/snapshot bridge))
        dispatch-action! (fn [action payload]
                           (if-let [dispatch (:presentation-dispatch-action! container)]
                             (dispatch action payload)
                             (when (= action :container/button)
                               (when-let [button (:button-click-fn container)]
                                 (button container (:button-id payload) player)))))]
    {:type :presentation-container-screen
     :template-id template-id
     :container container
     :menu menu
     :player player
     :mount-fn
     (fn [_]
       (let [api (client-bridge/call-adapter :presentation-host-api)
             mount ((:mount-container! api) bridge snapshot-fn dispatch-action!)]
         (when-let [set-handler! (:set-input-handler! api)]
           (set-handler!
             mount
             (fn [event]
               (let [pointer-result (pointer-action-handler
                                      bridge anchors dispatch-action! focus* event)]
                 (if (= pointer-result EventResult/PASS)
                   (or (text-input-handler container player text-fields focus* edit-state event)
                       (when-let [handler (:presentation-input-handler! container)]
                         (handler event)))
                   pointer-result)))))
         {:mount mount
          :on-close (fn []
                      (when-let [close (or (:presentation-close-fn container)
                                           (:close-fn container))]
                        (close container)))}))}))
