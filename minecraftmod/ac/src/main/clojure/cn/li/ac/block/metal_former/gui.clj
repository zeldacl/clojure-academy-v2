(ns cn.li.ac.block.metal-former.gui
  "CLIENT-ONLY: Metal Former GUI"
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
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.block.metal-former.config :as cfg]
            [cn.li.ac.block.metal-former.recipes :as recipes]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.energy.operations :as energy]))

(def ^:private former-slot-schema-id :metal-former)
(def ^:private former-gui-type :metal-former)

(defn- msg [action]
  (msg-registry/msg former-gui-type action))

(defn- create-container
  [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    {:tile-entity tile
     :player player
     :container-type former-gui-type
     :energy (atom (double (get state :energy 0.0)))
     :max-energy (atom (double (get state :max-energy cfg/max-energy)))
     :mode (atom (recipes/normalize-mode (get state :mode :plate)))
     :work-counter (atom (int (get state :work-counter 0)))
     :working (atom (boolean (get state :working false)))}))

(defn- get-slot-count [_container]
  (slot-registry/get-slot-count former-slot-schema-id))

(defn- get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn- set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {:inventory [nil]} identity))

(defn- slot-changed! [_container _slot-index] nil)

(defn- can-place-item? [_container slot-index item-stack]
  (let [slot-index (int slot-index)]
    (case slot-index
      2 (energy/is-energy-item-supported? item-stack)
      true)))

(defn- still-valid? [_container _player] true)

(defn- sync-to-client! [container]
  (let [state (or (common/get-tile-state (:tile-entity container)) {})]
    (reset! (:energy container) (double (get state :energy 0.0)))
    (reset! (:max-energy container) (double (get state :max-energy cfg/max-energy)))
    (reset! (:mode container) (recipes/normalize-mode (get state :mode :plate)))
    (reset! (:work-counter container) (int (get state :work-counter 0)))
    (reset! (:working container) (boolean (get state :working false)))))

(defn- get-sync-data [container]
  {:energy @(:energy container)
   :max-energy @(:max-energy container)
   :mode (recipes/mode->string @(:mode container))
   :work-counter @(:work-counter container)
   :working @(:working container)})

(defn- apply-sync-data! [container data]
  (reset! (:energy container) (double (:energy data 0.0)))
  (reset! (:max-energy container) (double (:max-energy data cfg/max-energy)))
  (reset! (:mode container) (recipes/normalize-mode (:mode data :plate)))
  (reset! (:work-counter container) (int (:work-counter data 0)))
  (reset! (:working container) (boolean (:working data false))))

(defn- tick! [container]
  (sync-to-client! container))

(defn- on-close [_container] nil)
(defn- handle-button-click! [_container _button-id _player] nil)

(defn- request-alternate!
  [container dir]
  (let [tile (:tile-entity container)
        payload (assoc (net-helpers/tile-pos-payload tile) :dir (int dir))]
    (net-client/send-to-server
      (msg :alternate)
      payload
      (fn [resp]
        (when-let [mode (:mode resp)]
          (reset! (:mode container) (recipes/normalize-mode mode)))))))

(defn- bind-progress!
  [inv-window container]
  (when-let [widget (cgui/find-widget inv-window "progress")]
    (when-let [bar (comp/get-component widget :progressbar)]
      (events/on-frame widget
        (fn [_]
          (let [progress (if @(:working container)
                           (/ (double @(:work-counter container)) (double cfg/work-ticks))
                           0.0)]
            (comp/set-progress! bar (max 0.0 (min 1.0 progress)))))))))

(defn- bind-mode-icon!
  [inv-window container]
  (when-let [widget (cgui/find-widget inv-window "icon_mode")]
    (when-let [dt (comp/get-drawtexture-component widget)]
      (events/on-frame widget
        (fn [_]
          (comp/set-texture! dt (recipes/mode->icon-texture @(:mode container))))))))

(defn- bind-buttons!
  [inv-window container]
  (when-let [btn-left (cgui/find-widget inv-window "btn_left")]
    (events/on-left-click btn-left
      (fn [_]
        (request-alternate! container -1))))
  (when-let [btn-right (cgui/find-widget inv-window "btn_right")]
    (events/on-left-click btn-right
      (fn [_]
        (request-alternate! container 1)))))

(defn- create-screen
  [container minecraft-container _player]
  (sync-to-client! container)
  (let [doc (cgui-doc/read-xml "assets/my_mod/guis/rework/page_metalformer.xml")
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
        _ (bind-progress! inv-window container)
        _ (bind-mode-icon! inv-window container)
        _ (bind-buttons! inv-window container)
        _ (cgui/set-position! info-area (+ (cgui/get-width inv-window) 7) 5)
        _ (tech-ui/reset-info-area! info-area)
        y0 (tech-ui/add-histogram info-area
                                  [(tech-ui/hist-buffer (fn [] (double @(:energy container))) max-e)]
                                  0)
        y1 (tech-ui/add-sepline info-area "Metal Former" y0)
        y2 (tech-ui/add-property info-area "mode"
                                 (fn [] (str/upper-case (recipes/mode->string @(:mode container))))
                                 y1)
        _y3 (tech-ui/add-property info-area "progress"
                                  (fn []
                                    (if @(:working container)
                                      (format "%d/%d" @(:work-counter container) cfg/work-ticks)
                                      "IDLE"))
                                  y2)]
    (cgui/add-widget! root info-area)
    (tech-ui/assoc-tech-ui-screen-size
      (assoc (cgui/create-cgui-screen-container root minecraft-container)
             :current-tab-atom (:current tech)))))

(defn- former-container?
  [container]
  (and (map? container)
       (= (:container-type container) former-gui-type)
       (contains? container :tile-entity)
       (contains? container :mode)
       (contains? container :energy)))

(defonce ^:private metal-former-gui-installed? (atom false))

(defn init-metal-former-gui!
  []
  (when (compare-and-set! metal-former-gui-installed? false true)
    (slot-schema/register-slot-schema!
      {:schema-id former-slot-schema-id
       :slots [{:id :input :type :input :x 13 :y 49}
               {:id :output :type :output :x 143 :y 49}
               {:id :energy :type :energy :x 42 :y 80}]})
    (gui-dsl/register-gui!
      (gui-dsl/create-gui-spec
        "metal-former"
        {:gui-id 6
         :display-name "Metal Former"
         :gui-type former-gui-type
         :registry-name "metal_former_gui"
         :screen-factory-fn-kw :create-metal-former-screen
         :slot-layout (slot-schema/get-slot-layout former-slot-schema-id)
         :container-predicate former-container?
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
    (log/info "Metal Former GUI initialized")))
