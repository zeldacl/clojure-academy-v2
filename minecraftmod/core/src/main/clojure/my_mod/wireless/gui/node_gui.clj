(ns my-mod.wireless.gui.node-gui
  "Wireless Node GUI - TechUI architecture (参照Scala GuiNode)
  
  架构：使用ContainerUI + InfoArea模式
  
  功能：
  - 双页面UI：Inventory + Wireless
  - 网络消息：初始化、重命名、改密码、查询连接状态
  - InfoArea：histogram（能量+容量）+ 可编辑属性
  - 动态刷新：每2秒查询连接状态"
  (:require [my-mod.gui.cgui :as cgui]
            [my-mod.gui.components :as comp]
            [my-mod.gui.events :as events]
            [my-mod.gui.cgui-document :as cgui-doc]
            [my-mod.network.client :as net-client]
            [my-mod.wireless.gui.network-handler-helpers :as net-helpers]
            [my-mod.util.log :as log])
  (:import [net.minecraft.entity.player EntityPlayer]))

;; ============================================================================
;; Network Message Constants
;; ============================================================================

(def ^:const MSG_RENAME "node_rename")
(def ^:const MSG_CHANGE_PASS "node_change_pass")
(def ^:const MSG_INIT "node_init")
(def ^:const MSG_QUERY_LINK "node_query_link")

;; ============================================================================
;; GUI Dimensions
;; ============================================================================

(def gui-width 172)
(def gui-height 187)

;; ============================================================================
;; Network Message Functions
;; ============================================================================

(defn send-message
  "Send network message to server
  
  Args:
  - channel: Message channel name
  - tile: TileNode instance
  - args: Additional arguments
  - callback: (fn [response] ...) - Response handler"
  [channel tile & args]
  (try
    (let [payload (net-helpers/tile-pos-payload tile)
          full-args (concat [payload] args)]
      (apply net-client/send-to-server channel full-args))
    (catch Exception e
      (log/error "Error sending message to" channel ":" (.getMessage e)))))

(defn send-rename
  "Send node rename request"
  [player tile new-name]
  (try
    (net-client/send-to-server MSG_RENAME
      {:player-name (.getName player)
       :tile-pos (net-helpers/tile-pos-payload tile)
       :new-name new-name})
    (catch Exception e
      (log/error "Error renaming node:" (.getMessage e)))))

(defn send-change-password
  "Send password change request"
  [player tile new-password]
  (try
    (net-client/send-to-server MSG_CHANGE_PASS
      {:player-name (.getName player)
       :tile-pos (net-helpers/tile-pos-payload tile)
       :new-password new-password})
    (catch Exception e
      (log/error "Error changing password:" (.getMessage e)))))

(defn send-query-link
  "Query if node is linked to a matrix
  
  Args:
  - tile: TileNode
  - callback: (fn [is-linked] ...) - Boolean result handler"
  [tile callback]
  (try
    (net-client/send-to-server MSG_QUERY_LINK
      (net-helpers/tile-pos-payload tile)
      (fn [response]
        (try
          (let [is-linked (boolean (get response :is-linked false))]
            (callback is-linked))
          (catch Exception e
            (log/error "Error processing link query:" (.getMessage e))))))
    (catch Exception e
      (log/error "Error querying link status:" (.getMessage e)))))

(defn send-init
  "Initialize node data query
  
  Args:
  - tile: TileNode
  - callback: (fn [load-capacity] ...) - Load capacity handler"
  [tile callback]
  (try
    (net-client/send-to-server MSG_INIT
      (net-helpers/tile-pos-payload tile)
      (fn [response]
        (try
          (let [load (get response :load 0)]
            (callback load))
          (catch Exception e
            (log/error "Error processing init response:" (.getMessage e))))))
    (catch Exception e
      (log/error "Error initializing node:" (.getMessage e)))))

;; ============================================================================
;; Page Builders
;; ============================================================================

(defn create-inventory-page
  "Create inventory page widget
  
  Returns: Page widget with inventory UI"
  []
  (let [page-xml (cgui-doc/read-xml "my_mod:guis/rework/page_inv.xml")
        page-widget (cgui-doc/get-widget page-xml "main")]
    
    ;; Add breathing effect to UI elements
    (doseq [widget (cgui/get-draw-list page-widget)]
      (when (.startsWith (.getName widget) "ui_")
        (comp/add-component! widget (comp/breathe-effect))))
    
    ;; Set UI block texture
    (when-let [ui-block (cgui/find-widget page-widget "ui_block")]
      (comp/set-texture! ui-block "my_mod:textures/guis/ui/ui_node.png"))
    
    page-widget))

(defn create-wireless-page
  "Create wireless connection page
  
  Args:  
  - tile: TileNode instance
  
  Returns: Page widget"
  [tile]
  ;; TODO: Integrate with WirelessPage.nodePage implementation
  ;; For now, return placeholder
  (let [widget (cgui/create-container :pos [0 0] :size [gui-width gui-height])]
    (comp/add-component! widget
      (comp/text-box
        :text "Wireless Page\n(Not yet implemented)"
        :color 0xFFAAAAAA
        :scale 0.8))
    widget))

;; ============================================================================
;; InfoArea Builder (TechUI架构)
;; ============================================================================

(defn create-info-area
  "Create InfoArea with histograms and properties
  
  Args:
  - tile: TileNode instance
  - player: EntityPlayer
  - container: NodeContainer
  
  Returns: InfoArea widget"
  [tile player container]
  (let [info-area (cgui/create-container :pos [0 0] :size [100 50])
        load-atom (atom 1)
        
        ;; Get tile properties
        energy (.getEnergy tile)
        max-energy (.getMaxEnergy tile)
        capacity (.getCapacity tile)
        range (.getRange tile)
        owner (.getPlacerName tile)
        node-name (.getNodeName tile)
        password (.getPassword tile)
        
        is-owner? (= owner (.getName player))]
    
    ;; Initialize load query
    (send-init tile
      (fn [load]
        (reset! load-atom load)))
    
    ;; Build histogram
    (let [hist-widget (cgui/create-widget :pos [0 10] :size [210 210])
          _ (comp/add-component! hist-widget
              (comp/texture "my_mod:textures/guis/histogram.png" 0 0 210 210))
          
          ;; Energy histogram bar
          energy-bar (cgui/create-widget :pos [56 78] :size [16 120])
          energy-progress (comp/progress-bar
                            :direction :vertical
                            :progress 0.0
                            :color 0xFF25c4ff)
          
          ;; Capacity histogram bar  
          capacity-bar (cgui/create-widget :pos [96 78] :size [16 120])
          capacity-progress (comp/progress-bar
                              :direction :vertical
                              :progress 0.0
                              :color 0xFFff6c00)]
      
      ;; Setup energy bar
      (comp/add-component! energy-bar energy-progress)
      (events/on-frame energy-bar
        (fn [_]
          (let [current-energy (.getEnergy tile)
                current-max (.getMaxEnergy tile)
                progress (if (> current-max 0)
                          (/ (double current-energy) (double current-max))
                          0.03)]
            (comp/set-progress! energy-progress (Math/max 0.03 (Math/min 1.0 progress))))))
      
      ;; Setup capacity bar
      (comp/add-component! capacity-bar capacity-progress)
      (events/on-frame capacity-bar
        (fn [_]
          (let [current-load @load-atom
                current-cap capacity
                progress (if (> current-cap 0)
                          (/ (double current-load) (double current-cap))
                          0.03)]
            (comp/set-progress! capacity-progress (Math/max 0.03 (Math/min 1.0 progress))))))
      
      (cgui/add-widget! hist-widget energy-bar)
      (cgui/add-widget! hist-widget capacity-bar)
      (cgui/add-widget! info-area hist-widget))
    
    ;; Separator line - Info
    (let [sep-widget (cgui/create-widget :pos [3 120] :size [97 8])]
      (comp/add-component! sep-widget
        (comp/text-box
          :text "Info"
          :color 0x99FFFFFF
          :scale 0.6))
      (cgui/add-widget! info-area sep-widget))
    
    (let [prop-y-start 130
          prop-spacing 10]
      
      ;; Range property (read-only)
      (let [range-widget (cgui/create-widget :pos [6 prop-y-start] :size [88 8])
            label-box (comp/text-box :text "Range" :color 0xFFAAAAAA :scale 0.8)
            value-box (comp/text-box :text (str range) :color 0xFFFFFFFF :scale 0.8)]
        (comp/add-component! range-widget label-box)
        (comp/add-component! range-widget value-box)
        (cgui/add-widget! info-area range-widget))
      
      ;; Owner property (read-only)
      (let [owner-widget (cgui/create-widget :pos [6 (+ prop-y-start prop-spacing)] :size [88 8])
            label-box (comp/text-box :text "Owner" :color 0xFFAAAAAA :scale 0.8)
            value-box (comp/text-box :text owner :color 0xFFFFFFFF :scale 0.8)]
        (comp/add-component! owner-widget label-box)
        (comp/add-component! owner-widget value-box)
        (cgui/add-widget! info-area owner-widget))
      
      ;; Node name property (editable if owner)
      (if is-owner?
        (let [name-widget (cgui/create-widget :pos [6 (+ prop-y-start (* 2 prop-spacing))] :size [88 8])
              label-box (comp/text-box :text "Node Name" :color 0xFFAAAAAA :scale 0.8)
              value-box (comp/text-box :text node-name :color 0xFF2180d8 :scale 0.8)
              _ (comp/set-editable! value-box true)]
          (comp/add-component! name-widget label-box)
          (comp/add-component! name-widget value-box)
          (events/on-confirm-input value-box
            (fn [new-name]
              (send-rename player tile new-name)
              (comp/set-text-color! value-box 0xFFFFFFFF)))
          (events/on-change-content value-box
            (fn [_]
              (comp/set-text-color! value-box 0xFF2180d8)))
          (cgui/add-widget! info-area name-widget))
        ;; Read-only for non-owners
        (let [name-widget (cgui/create-widget :pos [6 (+ prop-y-start (* 2 prop-spacing))] :size [88 8])
              label-box (comp/text-box :text "Node Name" :color 0xFFAAAAAA :scale 0.8)
              value-box (comp/text-box :text node-name :color 0xFFFFFFFF :scale 0.8)]
          (comp/add-component! name-widget label-box)
          (comp/add-component! name-widget value-box)
          (cgui/add-widget! info-area name-widget)))
      
      ;; Password property (editable if owner, masked)
      (if is-owner?
        (let [pass-widget (cgui/create-widget :pos [6 (+ prop-y-start (* 3 prop-spacing))] :size [88 8])
              label-box (comp/text-box :text "Password" :color 0xFFAAAAAA :scale 0.8)
              value-box (comp/text-box :text password :color 0xFF2180d8 :scale 0.8 :masked? true)
              _ (comp/set-editable! value-box true)]
          (comp/add-component! pass-widget label-box)
          (comp/add-component! pass-widget value-box)
          (events/on-confirm-input value-box
            (fn [new-pass]
              (send-change-password player tile new-pass)
              (comp/set-text-color! value-box 0xFFFFFFFF)))
          (events/on-change-content value-box
            (fn [_]
              (comp/set-text-color! value-box 0xFF2180d8)))
          (cgui/add-widget! info-area pass-widget))))
    
    info-area))

;; ============================================================================
;; Main GUI Factory (TechUI.ContainerUI架构)
;; ============================================================================

(defn create-node-gui
  "Create Node GUI with TechUI architecture
  
  Args:
  - container: ContainerNode
  - player: EntityPlayer
  
  Returns: ContainerUI instance"
  [container player]
  (let [tile (or (:tile-entity container)
                 (try (.tile container) (catch Exception _ nil)))
        
        ;; Create pages
        inv-page {:id "inv" :window (create-inventory-page)}
        wireless-page {:id "wireless" :window (create-wireless-page tile)}
        
        ;; Create main TechUI widget
        main-widget (cgui/create-container :pos [-18 0] :size [gui-width gui-height])
        
        ;; Create page buttons
        page-buttons (atom [])]
    
    ;; TODO: Implement TechUI page switching logic
    ;; For now, just show inventory page
    (cgui/add-widget! main-widget (:window inv-page))
    
    ;; Create info area
    (let [info-area (create-info-area tile player container)]
      (cgui/set-position! info-area
        (+ (cgui/get-width main-widget) 7)
        5)
      (cgui/add-widget! main-widget info-area))
    
    ;; Add link status polling (every 2 seconds)
    (let [last-time (atom (- (System/currentTimeMillis) 2000))
          link-state (atom false)]
      (events/on-frame main-widget
        (fn [_]
          (let [current-time (System/currentTimeMillis)
                dt (- current-time @last-time)]
            (when (> dt 2000)
              (send-query-link tile
                (fn [is-linked]
                  (reset! link-state is-linked)))
              (reset! last-time current-time))))))
    
    main-widget))

;; ============================================================================
;; Screen Creation
;; ============================================================================

(defn create-screen
  "Create CGuiScreenContainer for Node GUI
  
  Args:
  - container: NodeContainer instance
  - minecraft-container: Minecraft Container object
  - player: EntityPlayer
  
  Returns: CGuiScreenContainer instance"
  [container minecraft-container player]
  (let [gui-root (create-node-gui container player)]
    (cgui/create-cgui-screen-container gui-root minecraft-container)))
