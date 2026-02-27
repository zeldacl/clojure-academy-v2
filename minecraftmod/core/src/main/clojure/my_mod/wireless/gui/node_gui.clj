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
            [my-mod.gui.tech-ui-common :as tech-ui]
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

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

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
;; InfoArea Builder (使用共享TechUI组件)
;; ============================================================================

(defn build-info-area!
  "Build InfoArea for Node GUI (参照Scala GuiNode)
  
  Args:
  - info-area: InfoArea widget
  - tile: TileNode
  - player: EntityPlayer
  - load-atom: Atom holding current load value"
  [info-area tile player load-atom]
  (try
    (let [is-owner? (= (.getPlacerName tile) (.getName player))]
      
      ;; Clear current content
      (cgui/clear-widgets! info-area)
      
      ;; Build histogram (energy + capacity)
      (let [y (tech-ui/add-histogram
                info-area
                [(tech-ui/hist-energy
                   (fn [] (.getEnergy tile))
                   (.getMaxEnergy tile))
                 (tech-ui/hist-capacity
                   (fn [] @load-atom)
                   (.getCapacity tile))]
                10)]
        
        ;; Add separator - Info
        (let [y (tech-ui/add-sepline info-area "Info" y)]
          
          ;; Basic properties (always visible)
          (let [y (tech-ui/add-property info-area "Range" (.getRange tile) y)
                y (tech-ui/add-property info-area "Owner" (.getPlacerName tile) y)]
            
            (if is-owner?
              ;; Owner: editable node name and password
              (let [y (tech-ui/add-property
                        info-area "Node Name" (.getNodeName tile) y
                        :editable? true
                        :on-change (fn [new-name]
                                    (send-rename player tile new-name)))
                    y (tech-ui/add-property
                        info-area "Password" (.getPassword tile) y
                        :editable? true
                        :masked? true
                        :on-change (fn [new-pass]
                                    (send-change-password player tile new-pass)))]
                y)
              ;; Non-owner: read-only node name
              (let [y (tech-ui/add-property info-area "Node Name" (.getNodeName tile) y)]
                y))))))
    (catch Exception e
      (log/error "Error building info area:" (.getMessage e)))))

;; ============================================================================
;; Main GUI Factory (TechUI.ContainerUI架构)
;; ============================================================================

(defn create-node-gui
  "Create Node GUI with TechUI architecture (参照Scala GuiNode)
  
  Args:
  - container: ContainerNode
  - player: EntityPlayer
  
  Returns: ContainerUI instance"
  [container player]
  (let [tile (or (:tile-entity container)
                 (try (.tile container) (catch Exception _ nil)))
        
        ;; Create pages using shared builder
        inv-page (tech-ui/create-inventory-page "node")
        wireless-page {:id "wireless" :window (create-wireless-page tile)}
        
        ;; Create main TechUI widget
        main-widget (cgui/create-container :pos [-18 0] :size [gui-width gui-height])
        
        ;; Create InfoArea
        info-area (tech-ui/create-info-area)
        
        ;; Load atom for tracking node load
        load-atom (atom 1)]
    
    ;; Add inventory page window
    (cgui/add-widget! main-widget (:window inv-page))
    
    ;; TODO: Add page switching logic for wireless page
    
    ;; Position info area
    (cgui/set-position! info-area
      (+ (cgui/get-width main-widget) 7)
      5)
    
    ;; Initialize node data and build InfoArea
    (send-init tile
      (fn [load]
        (reset! load-atom load)
        (build-info-area! info-area tile player load-atom)))
    
    ;; Add info area to main widget
    (cgui/add-widget! main-widget info-area)
    
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
