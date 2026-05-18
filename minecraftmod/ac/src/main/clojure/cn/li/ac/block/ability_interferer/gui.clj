(ns cn.li.ac.block.ability-interferer.gui
  "CLIENT-ONLY: Ability Interferer GUI"
  (:require [clojure.string :as str]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.block.ability-interferer.config :as cfg]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.block.ability-interferer.schema :as interferer-schema]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.energy.operations :as energy]))

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

(defn- sync-whitelist-edit!
  [container]
  (when-let [edit-atom (:whitelist-edit container)]
    (reset! edit-atom (str/join "," (or @(:whitelist container) [])))))

(def ^:private interferer-sync
  (gui-sync/schema-sync-fns interferer-schema/ability-interferer-schema
                            {:after-sync! sync-whitelist-edit!
                             :after-apply! (fn [container _data]
                                             (sync-whitelist-edit! container))}))

(defn- create-container [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (gui-sync/create-schema-container interferer-schema/ability-interferer-schema
                                      tile
                                      player
                                      interferer-gui-type
                                      {:state state
                                       :base {:whitelist-edit (atom (str/join "," (get state :whitelist [])))}})))

(defn- get-slot-count [_container]
  (slot-registry/get-slot-count interferer-slot-schema-id))

(defn- get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn- set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {:inventory [nil]} identity))

(defn- slot-changed! [_container _slot-index] nil)

(defn- can-place-item? [_container _slot-index item-stack]
  (boolean (energy/is-energy-item-supported? item-stack)))

(defn- still-valid? [_container _player] true)

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
        r (cfg/clamp-range new-range)]
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

(def ^:private sync-to-client! (:sync-to-client! interferer-sync))
(def ^:private get-sync-data (:get-sync-data interferer-sync))
(def ^:private apply-sync-data! (:apply-sync-data! interferer-sync))

(defn- tick! [container]
  (gui-sync/sync-tick! container sync-to-client!))

(def ^:private on-close (:on-close interferer-sync))
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
  (when-let [range-text (cgui-core/find-widget inv-window "panel_config/element_range/element_text_range")]
    (when-let [tb (comp/get-textbox-component range-text)]
      (comp/set-text! tb (format "%.0f" (double @(:range container)))))
    (events/on-frame range-text
      (fn [_]
        (when-let [tb (comp/get-textbox-component range-text)]
          (comp/set-text! tb (format "%.0f" (double @(:range container))))))))

  (when-let [switch-btn (cgui-core/find-widget inv-window "panel_config/element_switch/element_btn_switch")]
    (update-switch-texture! switch-btn @(:enabled container))
    (events/on-left-click switch-btn
      (fn [_]
        (request-set-enabled! container (not @(:enabled container)))
        (update-switch-texture! switch-btn @(:enabled container))))
    (events/on-frame switch-btn
      (fn [_]
        (update-switch-texture! switch-btn @(:enabled container)))))

  (when-let [btn-left (cgui-core/find-widget inv-window "panel_config/element_range/element_btn_left")]
    (events/on-left-click btn-left
      (fn [_]
        (request-set-range! container (- @(:range container) 10.0)))))

  (when-let [btn-right (cgui-core/find-widget inv-window "panel_config/element_range/element_btn_right")]
    (events/on-left-click btn-right
      (fn [_]
        (request-set-range! container (+ @(:range container) 10.0)))))

  (when-let [btn-add (cgui-core/find-widget inv-window "panel_whitelist/btn_add")]
    (events/on-left-click btn-add
      (fn [_]
        ;; Convenience behavior for current framework: add local player name quickly.
          (let [player-name (some-> (:player container) entity/player-get-name str)
              wl (set @(:whitelist container))]
          (when-not (or (str/blank? player-name) (contains? wl player-name))
            (request-set-whitelist! container (conj (vec wl) player-name)))))))

  (when-let [btn-remove (cgui-core/find-widget inv-window "panel_whitelist/btn_remove")]
    (events/on-left-click btn-remove
      (fn [_]
        ;; Remove the most recently added entry as a practical fallback UI action.
        (let [wl @(:whitelist container)]
          (when (seq wl)
            (request-set-whitelist! container (vec (butlast wl)))))))))

(defn- create-screen [container minecraft-container _player]
  (sync-to-client! container)
  (let [inv-page (tech-ui/create-rework-page "guis/rework/page_interfere.xml")
        inv-window (:window inv-page)
        wireless-window (wireless-tab/create-wireless-panel {:role :receiver :container container})
        wireless-page {:id "wireless" :window wireless-window}
        pages [inv-page wireless-page]
        max-e (fn [] (max 1.0 (double @(:max-energy container))))
        wl-text (fn []
                  (let [wl @(:whitelist container)]
                    (if (seq wl)
                      (str/join ", " wl)
                      "<empty>")))]
    (tech-ui/create-tech-screen-container
      {:pages pages
       :container container
       :minecraft-container minecraft-container
       :bind! (fn [_]
                (wire-xml-controls! inv-window container))
       :build-info-area!
       (fn [info-area]
         (let [y0 (tech-ui/add-histogram info-area
                                         [(tech-ui/hist-buffer (fn [] (double @(:energy container))) max-e)]
                                         0)
               y1 (tech-ui/add-sepline info-area "Interferer" y0)
               y2 (tech-ui/add-property info-area "enabled" (fn [] (if @(:enabled container) "ON" "OFF")) y1)
               y3 (tech-ui/add-property info-area "range" (fn [] (format "%.0f" (double @(:range container)))) y2)
               y4 (tech-ui/add-property info-area "affected" (fn [] (str @(:affected-player-count container))) y3)
               y5 (tech-ui/add-property info-area "owner" (fn [] @(:placer-name container)) y4)]
           (tech-ui/add-property info-area "whitelist" wl-text y5
                                  :editable? true
                                  :on-change (fn [new-text]
                                               (let [names (parse-whitelist new-text)]
                                                 (reset! (:whitelist-edit container) (str new-text))
                                                 (request-set-whitelist! container names))))))})))

(defn- interferer-container?
  [container]
  (and (map? container)
       (= (:container-type container) interferer-gui-type)
       (contains? container :tile-entity)
       (contains? container :energy)))

(defonce-guard ability-interferer-gui-installed?)

(defn init-ability-interferer-gui!
  []
  (with-init-guard ability-interferer-gui-installed?
    (slot-schema/register-slot-schema!
      {:schema-id interferer-slot-schema-id
       :slots [{:id :battery :type :energy :x 139 :y 25}]})
    (gui-reg/register-block-gui!
            (gui-manifest/gui-name :ability-interferer)
            (merge (gui-manifest/gui-registration :ability-interferer)
              {:slot-layout (interferer-slot-layout)
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
