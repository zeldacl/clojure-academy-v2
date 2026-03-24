(ns cn.li.ac.block.solar-gen.gui
  "CLIENT-ONLY: Solar Generator GUI implementation.

  This file contains:
  - GUI layout and component builders
  - GUI interaction logic
  - Container atom management
  - Message registration and network handlers

  Must be loaded via side-checked requiring-resolve from platform layer.

  Uses existing XML page: assets/my_mod/guis/rework/page_solar.xml
  and composes it into TechUI tabs (inv + wireless) with InfoArea."
  (:require [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.cgui-document :as cgui-doc]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.ac.gui.tabbed-gui :as tabbed-gui]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.wireless.gui.wireless-tab :as wireless-tab]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.wireless.slot-schema :as slots]
            [cn.li.ac.energy.operations :as energy-ops]
            [cn.li.ac.wireless.gui.container-common :as common]
            [cn.li.ac.wireless.gui.container-schema :as schema]
            [cn.li.ac.wireless.gui.message-registry :as msg-registry]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.wireless.helper :as helper]
            [cn.li.ac.wireless.virtual-blocks :as vb]
            [cn.li.ac.wireless.gui.network-handler-helpers :as net-helpers]
            [cn.li.ac.wireless.node-connection :as node-conn]
            [cn.li.mcmod.platform.position :as pos])
  (:import [cn.li.acapi.wireless IWirelessNode]))

;; ============================================================================
;; Message Registration
;; ============================================================================

(msg-registry/register-block-messages!
  :generator
  [:get-status :list-nodes :connect :disconnect])

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

;; ============================================================================
;; Field Schema (from solar_container.clj)
;; ============================================================================

(def solar-fields
  [{:key :energy     :init (fn [s] (double (get s :energy 0.0)))     :sync? true  :coerce double :close-reset 0.0}
   {:key :max-energy :init (fn [s] (double (get s :max-energy 1000.0))) :sync? true  :coerce double :close-reset 0.0}
   {:key :status     :init (fn [s] (str (get s :status "STOPPED")))  :sync? true  :coerce str    :close-reset ""}
   {:key :gen-speed  :init (fn [s] (double (get s :gen-speed 0.0)))  :sync? true  :coerce double :close-reset 0.0}
   {:key :tab-index  :init (fn [_] 0)                                :sync? false :coerce int    :close-reset 0}
   {:key :sync-ticker :init (fn [_] 0)                               :sync? false :coerce int    :close-reset 0}])

;; ============================================================================
;; Container Creation (from solar_container.clj)
;; ============================================================================

(def ^:private solar-slot-schema-id slots/solar-gen-id)

(defn create-container [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (merge {:tile-entity    tile
            :player         player
            :container-type :solar}
           (schema/build-atoms solar-fields state))))

(defn get-slot-count [_container]
  (slot-schema/tile-slot-count solar-slot-schema-id))

(defn can-place-item? [_container _slot-index item-stack]
  (energy-ops/is-energy-item-supported? item-stack))

(defn get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {} identity))

(defn slot-changed! [_container _slot-index] nil)

(defn still-valid? [_container _player] true)

(defn sync-to-client! [container]
  (let [state (or (common/get-tile-state (:tile-entity container)) {})]
    (reset! (:energy container)     (double (get state :energy 0.0)))
    (reset! (:max-energy container) (double (get state :max-energy 1000.0)))
    (reset! (:status container)     (str (get state :status "STOPPED")))
    (reset! (:gen-speed container)  (double (get state :gen-speed 0.0)))))

(defn get-sync-data [container]
  (schema/get-sync-data solar-fields container))

(defn apply-sync-data! [container data]
  (schema/apply-sync-data! solar-fields container data))

(defn tick! [container]
  (swap! (:sync-ticker container) inc)
  (sync-to-client! container))

(defn handle-button-click! [_container _button-id _player] nil)

(defn on-close [container]
  (schema/reset-atoms! solar-fields container))

;; ============================================================================
;; GUI Components
;; ============================================================================

(defn- status->frame-v0
  "Map Solar status string to v0 for 3-frame vertical texture."
  [status]
  (case status
    "STOPPED" (/ 1.0 3.0)
    "STRONG" 0.0
    "WEAK" (/ 2.0 3.0)
    (/ 1.0 3.0)))

(def ^:private effect-solar-texture
  (modid/asset-path "textures" "guis/effect/effect_solar.png"))

(defn- attach-anim-frame!
  "Attach per-frame UV update to `ui_block/anim_frame` so runtime renders 3-frame effect texture.
   Degrades gracefully if widget is missing."
  [inv-window container]
  (when-let [anim-frame (cgui/find-widget inv-window "ui_block/anim_frame")]
    (events/on-frame anim-frame
      (fn [_]
        (try
          (let [v0 (status->frame-v0 @(:status container))
                v1 (+ v0 (/ 1.0 3.0))]
            (comp/render-texture-region anim-frame effect-solar-texture 0 0 0 0 0.0 v0 1.0 v1))
          (catch Exception _e
            ;; Keep UI alive even if texture path/resource fails at runtime.
            nil))))))

(declare create-wireless-panel)

(defn create-solar-gui
  "Create Solar Generator GUI root widget."
  [container _player & [opts]]
  (try
    (let [doc (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_solar.xml"))
          inv-window (cgui-doc/get-widget doc "main")
          wireless-window (wireless-tab/create-wireless-panel {:mode :generator :container container})
          inv-page {:id "inv" :window inv-window}
          wireless-page {:id "wireless" :window wireless-window}
          pages [inv-page wireless-page]
          container-id (when-let [m (:menu opts)] (gui/get-menu-container-id m))
          tech-ui (apply tech-ui/create-tech-ui pages)
          _ (tabbed-gui/attach-tab-sync! pages tech-ui container container-id)
          main-widget (:window tech-ui)
          info-area (tech-ui/create-info-area)
          max-e (fn [] (max 1.0 (double @(:max-energy container))))
          speed-str (fn []
                      (try
                        (format "%.2fIF/T" (double @(:gen-speed container)))
                        (catch Exception _ "0.00IF/T")))]

      (attach-anim-frame! inv-window container)

      (cgui/set-position! info-area (+ (cgui/get-width inv-window) 7) 5)
      (tech-ui/reset-info-area! info-area)
      (let [y (tech-ui/add-histogram
                info-area
                [(tech-ui/hist-buffer (fn [] (double @(:energy container)))
                                      (max-e))]
                0)
            y (tech-ui/add-sepline info-area "Info" y)
            y (tech-ui/add-property info-area "gen_speed" speed-str y)
            y (tech-ui/add-property info-area "status" (fn [] @(:status container)) y)]
        y)

      (cgui/add-widget! main-widget info-area)

      (log/info "Created Solar Generator GUI (TechUI)")
      (if (:menu opts)
        {:root main-widget :current (:current tech-ui)}
        main-widget))
    (catch Exception e
      (log/error "Error creating Solar GUI:" ((ex-message e)))
      (throw e))))

(defn create-screen
  "Create CGuiScreenContainer for Solar GUI."
  [container minecraft-container player]
  (let [gui (create-solar-gui container player {:menu minecraft-container})
        root (if (map? gui) (:root gui) gui)
        base (cgui/create-cgui-screen-container root minecraft-container)]
    (if (map? gui)
      (tech-ui/assoc-tech-ui-screen-size (assoc base :current-tab-atom (:current gui)))
      (tech-ui/assoc-tech-ui-screen-size base))))

;; ============================================================================
;; Network Handlers
;; ============================================================================

(defn- handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [conn (try (helper/get-node-conn-by-generator tile) (catch Exception _ nil))
            node ^IWirelessNode (when conn (try (node-conn/get-node conn) (catch Exception _ nil)))
            node-pos (when node (.getBlockPos node))
            pw (when node (try (str (.getPassword node)) (catch Exception _ "")))]
        {:linked (when node
                   {:node-name (try (str (.getNodeName node)) (catch Exception _ "Node"))
                    :pos-x (when node-pos (pos/pos-x node-pos))
                    :pos-y (when node-pos (pos/pos-y node-pos))
                    :pos-z (when node-pos (pos/pos-z node-pos))
                    :is-encrypted? (not (empty? pw))})
         :avail []})
      {:linked nil :avail []})))

(defn- handle-list-nodes [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [tile-pos (pos/position-get-block-pos tile)
            linked-conn (try (helper/get-node-conn-by-generator tile) (catch Exception _ nil))
            linked-node ^IWirelessNode (when linked-conn (try (node-conn/get-node linked-conn) (catch Exception _ nil)))
            linked-pos (when linked-node (.getBlockPos linked-node))
            nodes (if tile-pos (helper/get-nodes-in-range world tile-pos) [])
            linked (when linked-node
                     (let [pw (try (str (.getPassword linked-node)) (catch Exception _ ""))]
                       {:node-name (try (str (.getNodeName linked-node)) (catch Exception _ "Node"))
                        :pos-x (when linked-pos (pos/pos-x linked-pos))
                        :pos-y (when linked-pos (pos/pos-y linked-pos))
                        :pos-z (when linked-pos (pos/pos-z linked-pos))
                        :is-encrypted? (not (empty? pw))}))
            avail (->> nodes
                       (remove (fn [^IWirelessNode node]
                                 (let [p (.getBlockPos node)]
                                   (and p linked-pos
                                        (= (pos/pos-x p) (pos/pos-x linked-pos))
                                        (= (pos/pos-y p) (pos/pos-y linked-pos))
                                        (= (pos/pos-z p) (pos/pos-z linked-pos))))))
                       (mapv (fn [^IWirelessNode node]
                               (let [p (.getBlockPos node)
                                     pw (try (str (.getPassword node)) (catch Exception _ ""))]
                                 {:node-name (try (str (.getNodeName node)) (catch Exception _ "Node"))
                                  :pos-x (when p (pos/pos-x p))
                                  :pos-y (when p (pos/pos-y p))
                                  :pos-z (when p (pos/pos-z p))
                                  :is-encrypted? (not (empty? pw))}))))]
        {:linked linked :avail avail})
      {:linked nil :avail []})))

(defn- handle-connect [payload player]
  (let [world (net-helpers/get-world player)
        gen (net-helpers/get-tile-at world payload)
        node-pos (select-keys payload [:node-x :node-y :node-z])
        pass (:password payload "")]
    (if (and world gen (every? number? (vals node-pos)))
      (let [node (net-helpers/get-tile-at world {:pos-x (:node-x node-pos)
                                                 :pos-y (:node-y node-pos)
                                                 :pos-z (:node-z node-pos)})
            need-auth? (boolean (:need-auth? payload true))]
        (if node
          {:success (boolean (helper/link-generator-to-node! gen node pass need-auth?))}
          {:success false}))
      {:success false})))

(defn- handle-disconnect [payload player]
  (let [world (net-helpers/get-world player)
        gen (net-helpers/get-tile-at world payload)]
    (if (and world gen)
      (do (helper/unlink-generator-from-node! gen)
          {:success true})
      {:success false})))

(defn register-network-handlers! []
  (net-server/register-handler (msg-registry/msg :generator :get-status) handle-get-status)
  (net-server/register-handler (msg-registry/msg :generator :list-nodes) handle-list-nodes)
  (net-server/register-handler (msg-registry/msg :generator :connect) handle-connect)
  (net-server/register-handler (msg-registry/msg :generator :disconnect) handle-disconnect)
  (log/info "Solar Generator GUI network handlers registered"))

