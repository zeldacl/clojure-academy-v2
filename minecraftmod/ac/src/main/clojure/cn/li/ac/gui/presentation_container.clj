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
        altitude (when (contains? #{:wind-gen-main :wind-gen-base} (:container-type container))
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
     :histograms (cond-> []
                   (contains? container :energy)
                   (conj (let [value (double (or (value-of (:energy container)) 0.0))
                               maximum (max 1.0 (double (or (value-of (:max-energy container)) 1.0)))]
                           {:id :energy :label "Energy"
                            :ratio (max 0.0 (min 1.0 (/ value maximum)))
                            :value (format "%.0f IF" value)}))
                   (or (contains? container :capacity) (contains? container :max-capacity))
                   (conj (let [value (double (or (value-of (:capacity container)) 0.0))
                               maximum (max 1.0 (double (or (value-of (:max-capacity container)) 1.0)))]
                           {:id :capacity :label "Capacity"
                            :ratio (max 0.0 (min 1.0 (/ value maximum)))
                            :value (format "%.0f/%.0f" value maximum)}))
                   (contains? container :liquid-amount)
                   (conj (let [value (double (or (value-of (:liquid-amount container)) 0.0))
                               maximum (max 1.0 (double (or (value-of (:tank-size container)) 1.0)))]
                           {:id :liquid :label "Liquid"
                            :ratio (max 0.0 (min 1.0 (/ value maximum)))
                            :value (format "%.0f mB" value)})))
     :load-ratio (max 0.0 (min 1.0 (/ (double progress) max-progress)))}))
(def ^:private page-texture-by-type
  "Keys must match each GUI's `:container-type` (see create-schema-container callers)."
  {;; wireless family (dedicated artifacts also hardcode these; kept for page-composite)
   :matrix "textures/guis/ui/ui_matrix.png"
   :node "textures/guis/ui/ui_node.png"
   :wireless-matrix "textures/guis/ui/ui_matrix.png"
   :wireless-node "textures/guis/ui/ui_node.png"
   ;; machine_container surfaces
   :imag-fusor "textures/guis/ui/ui_imagfusor.png"
   :metal-former "textures/guis/ui/ui_metalformer.png"
   :phase-gen "textures/guis/ui/ui_phasegen.png"
   :phase-generator "textures/guis/ui/ui_phasegen.png"
   :wind-gen-main "textures/guis/ui/ui_windmain.png"
   :wind-gen-base "textures/guis/ui/ui_windbase.png"
   :ability-interferer "textures/guis/ui/ui_interfere.png"
   :energy-converter "textures/guis/ui/ui_node.png"
   ;; main page_solar.xml overlays ui_windbase.png (not phasegen).
   :solar "textures/guis/ui/ui_windbase.png"
   :solar-generator "textures/guis/ui/ui_windbase.png"
   :solar-gen "textures/guis/ui/ui_windbase.png"})

(defn- page-composite-for [container]
  (when-let [path (get page-texture-by-type (:container-type container))]
    [{:kind :image
      :src (str "academy:" path)
      :x 0.0 :y 0.0 :w 176.0 :h 187.0
      :rgba (unchecked-int 0xFFFFFFFF)}]))

(defn- snapshot-for [container revision slot-count]
  (let [network (wireless-state container)
        linked (:linked network)
        energy (double (or (value-of (:energy container)) 0.0))
        max-energy (max 1.0 (double (or (value-of (:max-energy container)) 1.0)))
        progress (double (or (when-let [f (:presentation-progress-fn container)] (f container))
                              (value-of (:progress container))
                              (value-of (:work-progress container))
                              (when (and (some? (value-of (:crafting-progress container)))
                                         (some? (value-of (:max-progress container))))
                                (/ (double (or (value-of (:crafting-progress container)) 0.0))
                                   (max 1.0 (double (or (value-of (:max-progress container)) 1.0)))))
                              0.0))
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
              :page-composite (or (page-composite-for container) [])
              :network-visible (boolean (wireless-config container))
              :network-state (if linked "Connected" "Not connected")
              :network-owner (str "Node: " (or (:node-name linked) "-"))
              :network-range (str "Range: " (or (:range linked) "-"))
              :network-bandwidth (str "Bandwidth: " (or (:bandwidth linked) "-"))
              :network-load (let [load (double (or (:load linked) 0.0))
                                  capacity (max 1.0 (double (or (:max-capacity linked) 1.0)))]
                              (max 0.0 (min 1.0 (/ load capacity))))
              :network-nodes (wireless-items container)
              :network-password (str (or (:password network) ""))
              :network-disconnect {:label "Disconnect"}
              :network-available-label {:label "Available"}}}))

(defn- runtime-owned-action?
  "Presentation Runtime owns hover/scroll/pointer/key routing. These must never
   cross the MenuBridge allow-list gate or mouseMoved will crash containers."
  [action]
  (or (nil? action)
      (and (keyword? action)
           (= "input" (namespace action)))))

(def ^:private draft-state-keys
  "Text-field drafts that must survive snapshot rebuilds / refresh."
  [:node-name :network-password :network-ssid])

(defn- merge-drafts
  "Keep in-progress text drafts across snapshot rebuilds.

   Preference (high -> low): payload active field, live view-state drafts,
   form-backed snapshot. Text-change handlers sync view -> form first so the
   next state-fn agrees with view; view still wins if form briefly lags."
  [snapshot current payload]
  (let [field (:field payload)
        value (when (contains? payload :value) (str (:value payload)))
        from-payload (case field
                       (:node-name) (when value {:node-name value})
                       (:password :network-password) (when value {:network-password value})
                       (:ssid :network-ssid) (when value {:network-ssid value})
                       nil)]
    (merge snapshot
           (select-keys (or current {}) draft-state-keys)
           from-payload)))

(defn- sync-view-drafts-into-form!
  "Push live text-input view keys into presentation-form-state before rebuild."
  [form current]
  (when (and form (map? current))
    (swap! form
           (fn [m]
             (cond-> (or m {})
               (contains? current :node-name)
               (assoc :node-name (str (:node-name current)))
               (contains? current :network-password)
               (assoc :password (str (:network-password current)))
               (contains? current :network-ssid)
               (assoc :ssid (str (:network-ssid current))))))))

(defn mount-container!
  ([runtime menu-bridge snapshot-fn dispatch-action!]
   (mount-container! runtime menu-bridge snapshot-fn dispatch-action!
                    :academy.app/machine-container))
  ([_runtime menu-bridge snapshot-fn dispatch-action! view-id]
  (let [state-fn (fn []
                   (let [snapshot (snapshot-fn)]
                     ;; Prefer flattened :values over envelope keys so draft
                     ;; fields are not shadowed by nested :values metadata.
                     (merge snapshot (:values snapshot {}))))
        vm (presentation/mount-view!
             {:view-id view-id
              :host-kind :container
              :state (state-fn)
              :dispatch-action!
              (fn [action payload current]
                ;; On focus change, push sibling drafts into form-state before any
                ;; later keystroke rebuilds the snapshot from a stale form.
                (when (and (map? current) (= action :input/focus))
                  (try (dispatch-action! :presentation/sync-drafts {} current)
                       (catch Throwable _)))
                (cond
                  (runtime-owned-action? action)
                  current

                  (contains? (:allowed-actions menu-bridge) action)
                  (do (menu-bridge/dispatch-action menu-bridge action payload dispatch-action!)
                      (merge-drafts (state-fn) current payload))

                  ;; Content-owned actions (wireless/text/custom) bypass the
                  ;; slot allow-list but still reach the container dispatcher.
                  :else
                  (do (try
                        (dispatch-action! action payload current)
                        (catch clojure.lang.ArityException _
                          (dispatch-action! action payload)))
                      (merge-drafts (state-fn) current payload))))})
        ;; Bind :snapshot before assoc — refresh! must not close over the
        ;; pre-assoc vm (where :snapshot is nil → reset! NPE every animate frame).
        snapshot* (atom (state-fn))
        refresh! (fn []
                   (let [cur (when-let [st (:state vm)] @st)
                         next (merge-drafts (state-fn) cur nil)]
                     (reset! snapshot* next)
                     (presentation/present! vm next)))]
    (assoc vm :snapshot snapshot* :refresh! refresh!))))

(defn open-screen!
  [menu-bridge snapshot-fn dispatch-action! on-close]
  (let [vm (mount-container! nil menu-bridge snapshot-fn dispatch-action!)]
    (client-bridge/call-adapter :presentation-open-screen!
                                 (:mount vm) "Container" on-close)
    vm))

(def ^:private techui-image-width 290)
(def ^:private techui-image-height 187)

(defn- player-inventory-anchors
  "Mirror mcbase `add-player-inventory-slots!` at (6,105): 3×9 main + hotbar.
   Menu indices follow slot-schema derived-ranges after the tile slots."
  [tile-slot-count]
  (let [x0 6.0
        y0 105.0
        main (for [row (range 3)
                   col (range 9)]
               {:slot-index (+ tile-slot-count (* row 9) col)
                :x (+ x0 (* col 18.0))
                :y (+ y0 (* row 18.0))
                :width 16.0 :height 16.0 :visible? true})
        hotbar (for [col (range 9)]
                 {:slot-index (+ tile-slot-count 27 col)
                  :x (+ x0 (* col 18.0))
                  :y (+ y0 58.0)
                  :width 16.0 :height 16.0 :visible? true})]
    (into [] (concat main hotbar))))

(defn- tile-slot-anchors [layout]
  (mapv (fn [{:keys [index x y]}]
          {:slot-index index :x (double x) :y (double y)
           :width 16.0 :height 16.0 :visible? true})
        (:slots layout)))

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
        anchors (into (tile-slot-anchors layout)
                      (player-inventory-anchors slot-count))
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
        dispatch-action! (fn dispatch-action!
                           ([action payload] (dispatch-action! action payload nil))
                           ([action payload current]
                           (cond
                             (= action :presentation/sync-drafts)
                             (sync-view-drafts-into-form!
                               (:presentation-form-state container) current)

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
                             (do (sync-view-drafts-into-form!
                                   (:presentation-form-state container) current)
                                 (when-let [handler (if (= action :container/text-submit)
                                                       (:presentation-text-submit! container)
                                                       (:presentation-text-change! container))]
                                   (let [field (or (:field payload)
                                                   ;; Fall back to bind-path tail when semantics omit :field.
                                                   (when-let [p (:path payload)]
                                                     (when (vector? p) (peek p))))
                                         field (case field
                                                 :network-password :password
                                                 :network-ssid :ssid
                                                 field)
                                         value (str (or (:value payload) ""))]
                                     (handler field value))))

                             :else
                             (if-let [dispatch (:presentation-dispatch-action! container)]
                               (dispatch action payload)
                               (when (= action :container/button)
                                 (when-let [button (:button-click-fn container)]
                                   (button container (:button-id payload) player)))))))]
    {:type :presentation-container-screen
     ;; Match TechUI host design so leftPos/topPos align with Presentation :fit.
     :image-width techui-image-width
     :image-height techui-image-height
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
                    :frame! (fn []
                              (when-let [frame! (:presentation-frame! container)]
                                (frame! container))
                              (when (:presentation-animate? container)
                                (when-let [refresh @refresh*]
                                  (refresh))))
                    :on-close (fn []
                                (when-let [close (or (:presentation-close-fn container)
                                                     (:close-fn container))]
                                  (close container)))}))}))
