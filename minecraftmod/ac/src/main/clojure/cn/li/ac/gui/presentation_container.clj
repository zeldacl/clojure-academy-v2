(ns cn.li.ac.gui.presentation-container
  "Container surface controller backed by the Presentation artifact runtime.
   Menu authority stays in MenuBridge; only normalized snapshots and actions
   cross into the retained UI runtime."
  (:require [cn.li.mcmod.gui.presentation-menu-bridge :as menu-bridge]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.wireless.gui.container.common :as container-common]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.tab.role-config :as role-config]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

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

(defn- wireless-config [container]
  (:presentation-wireless container))

(defn- wireless-state [container]
  (or (some-> (:presentation-wireless-state container) deref)
      {:linked nil :avail [] :password ""}))

(defn- wireless-items [container]
  (let [cfg (wireless-config container)
        data (wireless-state container)
        name-fn (or (:name-fn (get role-config/role-config (:role cfg)))
                    (fn [item] (or (:node-name item) (:ssid item) "Node")))]
    (mapv (fn [item]
            {:label (str (name-fn item)) :action-label "Link"
             :node-x (:pos-x item) :node-y (:pos-y item) :node-z (:pos-z item)
             :is-encrypted? (boolean (:is-encrypted? item))})
          (:avail data))))

(defn- send-wireless! [container action payload callback]
  (when-let [{:keys [domain]} (wireless-config container)]
    (let [owner (or (:owner container) (runtime-hooks/default-client-owner))
          message-id (msg-registry/msg domain action)]
      (net-client/send-to-server owner message-id
        (action-payload/action-payload container payload)
        callback))))

(defn- update-wireless-state! [container response]
  (when-let [state* (:presentation-wireless-state container)]
    (swap! state* merge {:linked (:linked response)
                         :avail (vec (or (:avail response) []))}))
  nil)
(defn- generic-info-area [container progress]
  "Project the common code-built InfoArea contract into declarative state."
  (let [tile (:tile-entity container)
        altitude (when (= (:container-type container) :wind-gen-main)
                   (try (some-> tile pos/block-pos pos/pos-y str)
                        (catch Exception _ nil)))
        fields (cond-> []
                 (contains? container :status)
                 (conj {:label "Status" :value (str (or (value-of (:status container)) "-"))})
                 (contains? container :mode)
                 (conj {:label "Mode" :value (str (or (value-of (:mode container)) "-"))})
                 (contains? container :wireless-mode)
                 (conj {:label "Wireless" :value (str (or (value-of (:wireless-mode container)) "-"))})
                 altitude
                 (conj {:label "Altitude" :value altitude})
                 (contains? container :fan-installed)
                 (conj {:label "Fan" :value (if (value-of (:fan-installed container)) "YES" "NO")})
                 (contains? container :no-obstacle)
                 (conj {:label "Obstacle" :value (if (value-of (:no-obstacle container)) "CLEAR" "BLOCKED")})
                 (contains? container :gen-speed)
                 (conj {:label "Generation" :value (format "%.2f IF/T" (double (or (value-of (:gen-speed container)) 0.0)))})
                 (contains? container :work-progress)
                 (conj {:label "Work" :value (str (or (value-of (:work-progress container)) "-"))})
                 (contains? container :work-counter)
                 (conj {:label "Work" :value (str (or (value-of (:work-counter container)) "-"))})
                 (contains? container :current-recipe-liquid)
                 (conj {:label "Liquid Needed" :value (str (or (value-of (:current-recipe-liquid container)) "-"))})
                 (contains? container :liquid-amount)
                 (conj {:label "Liquid" :value (str (or (value-of (:liquid-amount container)) "-"))}))
        max-progress (max 1.0 (double (or (value-of (:max-progress container)) 1.0)))]
    {:title "Machine Info"
     :fields fields
     :load-ratio (max 0.0 (min 1.0 (/ (double progress) max-progress)))}))
(defn- snapshot-for [container revision slot-count]
  (let [network (wireless-state container)
        linked (:linked network)
        energy (double (or (value-of (:energy container)) 0.0))
        max-energy (max 1.0 (double (or (value-of (:max-energy container)) 1.0)))
        progress (double (or (when-let [f (:presentation-progress-fn container)] (f container)) (value-of (:progress container)) 0.0))
        max-progress (max 1.0 (double (or (value-of (:max-progress container)) 1.0)))]
    {:revision @revision
     :values {:slots (mapv #(slot-value container %) (range slot-count))
              :energy-ratio (max 0.0 (min 1.0 (/ energy max-energy)))
              :progress-ratio (max 0.0 (min 1.0 (/ progress max-progress)))
              :machine-state (or (value-of (:status container))
                                 (value-of (:machine-state container))
                                 (value-of (:mode container))
                                 "IDLE")
              :info-area (generic-info-area container progress)
              :network-visible (boolean (wireless-config container))
              :network-state (if linked "Connected" "Not connected")
              :network-owner (str "Node: " (or (:node-name linked) "-"))
              :network-range (str "Range: " (or (:range linked) "-"))
              :network-bandwidth (str "Bandwidth: " (or (:bandwidth linked) "-"))
              :network-load 0.0
              :network-nodes (wireless-items container)
              :network-password (str (or (:password network) ""))
              :network-disconnect {:label "Disconnect"}
              :network-available-label {:label "Available"}}}))

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
        refresh* (atom nil)
        wireless* (or (:presentation-wireless-state container) (atom {:linked nil :avail [] :password ""}))
        container (assoc container
                     :minecraft-container menu
                     :presentation-wireless-state wireless*
                     :presentation-refresh! (fn []
                       (when-let [refresh @refresh*] (refresh))))
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
                            values (merge base-values extra-values text-values button-values
                                          {:slot-anchors anchors})]
                        (menu-bridge/update-snapshot! bridge @revision values)
                        (menu-bridge/snapshot bridge)))
        dispatch-action! (fn [action payload]
                           (cond
                             (= action :container/wireless-password)
                             (swap! wireless* assoc :password (str (or (:value payload) "")))

                             (= action :container/wireless-connect)
                             (let [cfg (wireless-config container)
                                   role-cfg (get role-config/role-config (:role cfg))
                                   item (:item payload)
                                   password (str (or (:password @wireless*) ""))
                                   payload* (if-let [build (:connect-payload-fn role-cfg)]
                                              (build {} item password)
                                              item)]
                               (send-wireless! container :connect payload*
                                 (fn [response]
                                   (update-wireless-state! container response)
                                   (when-let [refresh @refresh*] (refresh)))))

                             (= action :container/wireless-disconnect)
                             (send-wireless! container :disconnect {}
                               (fn [response]
                                 (update-wireless-state! container response)
                                 (when-let [refresh @refresh*] (refresh))))

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
                                   (button container (:button-id payload) player))))))]    {:type :presentation-container-screen
     :template-id template-id
     :container container
     :menu menu
     :player player
     :mount-fn (fn [_]
                 (let [vm (mount-container! nil bridge snapshot-fn dispatch-action! template-id)]
                   (reset! refresh* (:refresh! vm))
                   (when-let [on-mount (:presentation-on-mount! container)]
                     (on-mount container))
                   (when (wireless-config container)
                     (send-wireless! container :list-nodes {}
                       (fn [response]
                         (update-wireless-state! container response)
                         (when-let [refresh @refresh*] (refresh)))))
                   {:mount (:mount vm)
                    :on-close (fn []
                                (when-let [close (or (:presentation-close-fn container)
                                                     (:close-fn container))]
                                  (close container)))}))}))

