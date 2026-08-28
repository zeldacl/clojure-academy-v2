(ns cn.li.ac.gui.presentation-container
  "Container surface controller backed by the Presentation artifact runtime.
   Menu authority stays in MenuBridge; only normalized snapshots and actions
   cross into the retained UI runtime."
  (:require [cn.li.mcmod.gui.presentation-menu-bridge :as menu-bridge]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.wireless.gui.container.common :as container-common]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def binding-ids
  {:slots 0 :slot-anchors 1 :energy-ratio 2 :progress-ratio 3 :machine-state 4
   :button-left 5 :button-right 6 :network-state 7 :network-owner 8
   :network-range 9 :network-bandwidth 10 :network-load 11 :network-ssid 12
   :network-password 13 :node-name 14})
(def action-ids {0 :container/click-slot 1 :container/quick-move 2 :container/button})

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

(defn mount-container!
  ([runtime menu-bridge snapshot-fn dispatch-action!]
   (mount-container! runtime menu-bridge snapshot-fn dispatch-action!
                    :academy.app/machine-container))
  ([_runtime menu-bridge snapshot-fn dispatch-action! view-id]
  (let [state-fn (fn []
                   (let [snapshot (snapshot-fn)]
                     (merge (:values snapshot {}) snapshot)))
        vm (presentation/mount-view!
             {:view-id view-id
              :host-kind :container
              :state (state-fn)
              :dispatch-action!
              (fn [action payload _current]
                (menu-bridge/dispatch-action menu-bridge action payload dispatch-action!)
                (state-fn))})]
    (assoc vm
           :snapshot (atom (state-fn))
           :refresh! (fn []
                       (let [next (state-fn)]
                         (reset! (:snapshot vm) next)
                         (presentation/present! vm next)))))))

(defn open-screen!
  [menu-bridge snapshot-fn dispatch-action! on-close]
  (let [vm (mount-container! nil menu-bridge snapshot-fn dispatch-action!)]
    (client-bridge/call-adapter :presentation-open-screen!
                                 (:mount vm) "Container" on-close)
    vm))

(defn presentation-screen-data
  [container menu player schema-id template-id]
  (let [revision (atom 0)
        layout (or (slot-schema/get-slot-layout schema-id) {:slots []})
        slot-count (count (:slots layout))
        anchors (mapv (fn [{:keys [index x y]}]
                        {:slot-index index :x x :y y :width 16 :height 16 :visible? true})
                      (:slots layout))
        bridge (menu-bridge/create (or (:container-type container) schema-id)
                                   anchors
                                   #{:container/click-slot :container/quick-move
                                     :container/button})
        snapshot-fn (fn []
                      (swap! revision inc)
                      (let [base-values (:values (snapshot-for container revision slot-count))
                            extra-values (if-let [snapshot! (:presentation-snapshot-fn container)]
                                           (or (snapshot! container player) {})
                                           {})
                            form-state (when-let [form (:presentation-form-state container)] @form)
                            text-values (cond-> {}
                                          (some? (:ssid form-state)) (assoc :network-ssid (:ssid form-state))
                                          (some? (:password form-state)) (assoc :network-password (:password form-state))
                                          (some? (:node-name form-state)) (assoc :node-name (:node-name form-state)))
                            button-values (into {}
                                           (mapcat (fn [{:keys [button-id label]}]
                                                     (case (int (or button-id -1))
                                                       0 [[:button-left {:label (str (or label ""))}]]
                                                       1 [[:button-right {:label (str (or label ""))}]]
                                                       []))
                                                   (or (:presentation-buttons container) [])))
                            values (merge base-values extra-values text-values button-values)]
                        (menu-bridge/update-snapshot! bridge @revision values)
                        (menu-bridge/snapshot bridge)))
        dispatch-action! (fn [action payload]
                           (cond
                             (contains? #{:container/text-change :container/text-submit} action)
                             (when-let [handler (if (= action :container/text-submit)
                                                   (:presentation-text-submit! container)
                                                   (:presentation-text-change! container))]
                               (handler (:field payload) (str (or (:value payload) ""))))

                             :else
                             (if-let [dispatch (:presentation-dispatch-action! container)]
                               (dispatch action payload)
                               (when (= action :container/button)
                                 (when-let [button (:button-click-fn container)]
                                   (button container (:button-id payload) player))))))]
    {:type :presentation-container-screen
     :template-id template-id
     :container container
     :menu menu
     :player player
     :mount-fn (fn [_]
                 (let [vm (mount-container! nil bridge snapshot-fn dispatch-action! template-id)]
                   {:mount (:mount vm)
                    :on-close (fn []
                                (when-let [close (or (:presentation-close-fn container)
                                                     (:close-fn container))]
                                  (close container)))}))}))

