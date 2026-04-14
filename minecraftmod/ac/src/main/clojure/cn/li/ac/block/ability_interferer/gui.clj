(ns cn.li.ac.block.ability-interferer.gui
  "CLIENT-ONLY: Ability Interferer GUI"
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.block.ability-interferer.config :as cfg]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]))

(def ^:private interferer-slot-schema-id :ability-interferer)
(def ^:private interferer-gui-type :ability-interferer)

(defn- interferer-slot-layout
  "Match original AcademyCraft behavior:
   machine slot + player hotbar only (no player main inventory grid)."
  []
  (let [layout (slot-schema/get-slot-layout interferer-slot-schema-id)
        tile-slot-count (slot-registry/get-slot-count interferer-slot-schema-id)
        tile-end (dec tile-slot-count)
        hotbar-start tile-slot-count
        hotbar-end (+ hotbar-start 8)]
    (assoc layout
           :player-inventory-mode :hotbar-only
           :ranges {:tile [0 tile-end]
                    :player-main [1 0]
                    :player-hotbar [hotbar-start hotbar-end]})))

(defn- msg [action]
  (msg-registry/msg interferer-gui-type action))

(defn- create-container [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    {:tile-entity tile
     :player player
     :container-type interferer-gui-type
     :energy (atom (double (get state :energy 0.0)))
     :max-energy (atom (double (get state :max-energy cfg/max-energy)))
     :range (atom (double (get state :range cfg/default-range)))
     :enabled (atom (boolean (get state :enabled false)))
     :placer-name (atom (str (get state :placer-name "")))
     :whitelist (atom (vec (get state :whitelist [])))
     :whitelist-edit (atom (str/join "," (get state :whitelist [])))
     :affected-player-count (atom (int (get state :affected-player-count 0)))}))

(defn- get-slot-count [_container]
  (slot-registry/get-slot-count interferer-slot-schema-id))

(defn- get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn- set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {:inventory [nil]} identity))

(defn- slot-changed! [_container _slot-index] nil)

(defn- can-place-item? [_container _slot-index item-stack]
  (try
    (let [is-energy-item? (requiring-resolve 'cn.li.ac.energy.operations/is-energy-item-supported?)]
      (boolean (is-energy-item? item-stack)))
    (catch Exception _
      false)))

(defn- still-valid? [_container _player] true)

(defn- clamp-range [v]
  (let [r (double (or v cfg/default-range))]
    (-> r
        (max cfg/min-range)
        (min cfg/max-range))))

(defn- parse-whitelist
  [s]
  (->> (str/split (str s) #",")
       (map str/trim)
       (remove str/blank?)
       distinct
       sort
       vec))

(defn- request-set-whitelist! [container names]
  (let [tile (:tile-entity container)]
    (net-client/send-to-server
      (msg :set-whitelist)
      (assoc (net-helpers/tile-pos-payload tile)
             :whitelist (vec names)))))

(defn- request-set-range! [container new-range]
  (let [tile (:tile-entity container)
        r (clamp-range new-range)]
    (net-client/send-to-server
      (msg :change-range)
      (assoc (net-helpers/tile-pos-payload tile) :range r))
    (reset! (:range container) r)))

(defn- request-set-enabled! [container v]
  (let [tile (:tile-entity container)
        enabled? (boolean v)]
    (net-client/send-to-server
      (msg :toggle-enabled)
      (assoc (net-helpers/tile-pos-payload tile) :enabled enabled?))
    (reset! (:enabled container) enabled?)))

(defn- sync-to-client! [container]
  (let [state (or (common/get-tile-state (:tile-entity container)) {})
        wl (vec (get state :whitelist []))]
    (reset! (:energy container) (double (get state :energy 0.0)))
    (reset! (:max-energy container) (double (get state :max-energy cfg/max-energy)))
    (reset! (:range container) (double (get state :range cfg/default-range)))
    (reset! (:enabled container) (boolean (get state :enabled false)))
    (reset! (:placer-name container) (str (get state :placer-name "")))
    (reset! (:whitelist container) wl)
    (reset! (:whitelist-edit container) (str/join "," wl))
    (reset! (:affected-player-count container) (int (get state :affected-player-count 0)))))

(defn- get-sync-data [container]
  {:energy @(:energy container)
   :max-energy @(:max-energy container)
   :range @(:range container)
   :enabled @(:enabled container)
   :placer-name @(:placer-name container)
   :whitelist @(:whitelist container)
   :affected-player-count @(:affected-player-count container)})

(defn- apply-sync-data! [container data]
  (let [wl (vec (:whitelist data []))]
    (reset! (:energy container) (double (:energy data 0.0)))
    (reset! (:max-energy container) (double (:max-energy data cfg/max-energy)))
    (reset! (:range container) (double (:range data cfg/default-range)))
    (reset! (:enabled container) (boolean (:enabled data false)))
    (reset! (:placer-name container) (str (:placer-name data "")))
    (reset! (:whitelist container) wl)
    (reset! (:whitelist-edit container) (str/join "," wl))
    (reset! (:affected-player-count container) (int (:affected-player-count data 0)))))

(defn- tick! [container]
  (sync-to-client! container))

(defn- on-close [_container] nil)
(defn- handle-button-click! [_container _button-id _player] nil)

(defn- update-switch-texture!
  [switch-widget enabled?]
  (when-let [dt (comp/get-drawtexture-component switch-widget)]
    (comp/set-texture!
      dt
      (if enabled?
        (modid/asset-path "textures" "guis/button/button_switch_on.png")
        (modid/asset-path "textures" "guis/button/button_switch_off.png")))))

(defn- wire-xml-controls!
  [inv-window container]
  (when-let [range-text (cgui/find-widget inv-window "panel_config/element_range/element_text_range")]
    (when-let [tb (comp/get-textbox-component range-text)]
      (comp/set-text! tb (format "%.0f" (double @(:range container)))))
    (events/on-frame range-text
      (fn [_]
        (when-let [tb (comp/get-textbox-component range-text)]
          (comp/set-text! tb (format "%.0f" (double @(:range container))))))))

  (when-let [switch-btn (cgui/find-widget inv-window "panel_config/element_switch/element_btn_switch")]
    (update-switch-texture! switch-btn @(:enabled container))
    (events/on-left-click switch-btn
      (fn [_]
        (request-set-enabled! container (not @(:enabled container)))
        (update-switch-texture! switch-btn @(:enabled container))))
    (events/on-frame switch-btn
      (fn [_]
        (update-switch-texture! switch-btn @(:enabled container)))))

  (when-let [btn-left (cgui/find-widget inv-window "panel_config/element_range/element_btn_left")]
    (events/on-left-click btn-left
      (fn [_]
        (request-set-range! container (- @(:range container) 10.0)))))

  (when-let [btn-right (cgui/find-widget inv-window "panel_config/element_range/element_btn_right")]
    (events/on-left-click btn-right
      (fn [_]
        (request-set-range! container (+ @(:range container) 10.0)))))

  (when-let [btn-add (cgui/find-widget inv-window "panel_whitelist/btn_add")]
    (events/on-left-click btn-add
      (fn [_]
        ;; Convenience behavior for current framework: add local player name quickly.
          (let [player-name (some-> (:player container) entity/player-get-name str)
              wl (set @(:whitelist container))]
          (when-not (or (str/blank? player-name) (contains? wl player-name))
            (request-set-whitelist! container (conj (vec wl) player-name)))))))

  (when-let [btn-remove (cgui/find-widget inv-window "panel_whitelist/btn_remove")]
    (events/on-left-click btn-remove
      (fn [_]
        ;; Remove the most recently added entry as a practical fallback UI action.
        (let [wl @(:whitelist container)]
          (when (seq wl)
            (request-set-whitelist! container (vec (butlast wl)))))))))

(defn- create-screen [container minecraft-container _player]
  (sync-to-client! container)
  (let [doc (cgui-doc/read-xml "assets/my_mod/guis/rework/page_interfere.xml")
        inv-window (cgui-doc/get-widget doc "main")
        wireless-window (wireless-tab/create-wireless-panel {:mode :receiver :container container})
        inv-page {:id "inv" :window inv-window}
        wireless-page {:id "wireless" :window wireless-window}
        pages [inv-page wireless-page]
        container-id (gui/get-menu-container-id minecraft-container)
        tech (apply tech-ui/create-tech-ui pages)
        _ (tabbed-gui/attach-tab-sync! pages tech container container-id)
        root (:window tech)
        info-area (tech-ui/create-info-area)
        max-e (fn [] (max 1.0 (double @(:max-energy container))))
        wl-text (fn []
                  (let [wl @(:whitelist container)]
                    (if (seq wl)
                      (str/join ", " wl)
                      "<empty>")))
        y0 (tech-ui/add-histogram info-area
                                  [(tech-ui/hist-buffer (fn [] (double @(:energy container))) max-e)]
                                  0)
        y1 (tech-ui/add-sepline info-area "Interferer" y0)
        y2 (tech-ui/add-property info-area "enabled" (fn [] (if @(:enabled container) "ON" "OFF")) y1)
        y3 (tech-ui/add-property info-area "range" (fn [] (format "%.0f" (double @(:range container)))) y2)
        y4 (tech-ui/add-property info-area "affected" (fn [] (str @(:affected-player-count container))) y3)
        y5 (tech-ui/add-property info-area "owner" (fn [] @(:placer-name container)) y4)
        _y6 (tech-ui/add-property info-area "whitelist" wl-text y5
                                  :editable? true
                                  :on-change (fn [new-text]
                                               (let [names (parse-whitelist new-text)]
                                                 (reset! (:whitelist-edit container) (str new-text))
                                                 (request-set-whitelist! container names))))
        _ (cgui/set-position! info-area (+ (cgui/get-width inv-window) 7) 5)
        _ (cgui/add-widget! root info-area)
        _ (wire-xml-controls! inv-window container)
        base (cgui/create-cgui-screen-container root minecraft-container)]
    (tech-ui/assoc-tech-ui-screen-size (assoc base :current-tab-atom (:current tech)))))

(defn- interferer-container?
  [container]
  (and (map? container)
       (= (:container-type container) interferer-gui-type)
       (contains? container :tile-entity)
       (contains? container :energy)))

(defonce ^:private ability-interferer-gui-installed? (atom false))

(defn init-ability-interferer-gui!
  []
  (when (compare-and-set! ability-interferer-gui-installed? false true)
    (slot-schema/register-slot-schema!
      {:schema-id interferer-slot-schema-id
       :slots [{:id :battery :type :energy :x 139 :y 25}]})
    (gui-dsl/register-gui!
      (gui-dsl/create-gui-spec
        "ability-interferer"
        {:gui-id 15
         :display-name "Ability Interferer"
         :gui-type interferer-gui-type
         :registry-name "ability_interferer_gui"
         :screen-factory-fn-kw :create-ability-interferer-screen
         :slot-layout (interferer-slot-layout)
         :container-predicate interferer-container?
         :container-fn create-container
         :screen-fn create-screen
         :tick-fn tick!
         :sync-get get-sync-data
         :sync-apply apply-sync-data!
         :validate-fn still-valid?
         :close-fn on-close
         :button-click-fn handle-button-click!
         :slot-count-fn get-slot-count
         :slot-get-fn get-slot-item
         :slot-set-fn set-slot-item!
         :slot-can-place-fn can-place-item?
         :slot-changed-fn slot-changed!}))
    (log/info "Ability Interferer GUI initialized")))
