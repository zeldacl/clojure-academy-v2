(ns my-mod.wireless.gui.matrix-gui-xml
  "Wireless Matrix GUI - 动态实现（参照Scala GuiMatrix2+TechUI）
  
  架构：使用TechUI.ContainerUI模式
  
  流程：
  1. 加载共享InventoryPage
  2. 查询服务端网络信息
  3. 根据3种状态构建动态InfoArea：
     - 未初始化+所有者: 显示初始化表单
     - 未初始化+非所有者: 显示提示消息
     - 已初始化: 显示网络信息（所有者可编辑）
  
  特性：
  - 网络容量直方图
  - 所有者/范围/带宽属性（只读）
  - 动态SSID/密码显示（初始化后所有者可编辑）
  - 初始化表单供所有者创建网络"
  
  (:require [my-mod.gui.cgui :as cgui]
            [my-mod.gui.components :as comp]
            [my-mod.gui.events :as events]
            [my-mod.gui.tech-ui-common :as tech-ui]
            [my-mod.network.client :as net-client]
            [my-mod.wireless.gui.matrix-messages :as matrix-msgs]
            [my-mod.wireless.gui.network-handler-helpers :as net-helpers]
            [my-mod.util.log :as log]))

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

;; ============================================================================
;; Data Structures
;; ============================================================================

(defrecord MatrixNetworkData
  [ssid              ;; String | nil - Network SSID (nil = uninitialized)
   password          ;; String | nil - Network password
   owner             ;; String - Owner player name
   load              ;; int - Current device count
   max-capacity      ;; int - Maximum device capacity
   range             ;; int - Network range in blocks
   bandwidth         ;; int - Network bandwidth
   initialized       ;; boolean - Whether network is initialized
   ])

(defn network-initialized? [data]
  "Check if network is initialized"
  (:initialized data))

;; ============================================================================
;; Network Messages
;; ============================================================================

(defn send-gather-info
  "Query network information from server
  
  Args:
  - tile: TileMatrix instance
  - callback: (fn [MatrixNetworkData] ...) - receives query result"
  [tile callback]
  (try
    (net-client/send-to-server
      (matrix-msgs/msg :gather-info)
      (net-helpers/tile-pos-payload tile)
      (fn [response]
        (try
          (let [data (map->MatrixNetworkData
                       {:ssid (get response :ssid)
                        :password (get response :password)
                        :owner (get response :owner "Unknown")
                        :load (get response :load 0)
                        :max-capacity (get response :max-capacity 16)
                        :range (get response :range 64)
                        :bandwidth (get response :bandwidth 100)
                        :initialized (boolean (get response :ssid))})]
            (callback data))
          (catch Exception e
            (log/error "Error processing gather-info response:" (.getMessage e))))))
    (catch Exception e
      (log/error "Error sending gather-info:" (.getMessage e)))))

(defn send-init-network
  "Initialize network on server
  
  Args:
  - tile: TileMatrix instance
  - ssid: String - network name
  - password: String - network password
  - callback: (fn [success] ...) - receives init result"
  [tile ssid password callback]
  (try
    (net-client/send-to-server
      (matrix-msgs/msg :init)
      (assoc (net-helpers/tile-pos-payload tile)
             :ssid ssid
             :password password)
      (fn [response]
        (try
          (callback (get response :success false))
          (catch Exception e
            (log/error "Error processing init response:" (.getMessage e))))))
    (catch Exception e
      (log/error "Error sending init:" (.getMessage e)))))

(defn send-change-ssid
  "Change network SSID
  
  Args:
  - tile: TileMatrix instance
  - new-ssid: String"
  [tile new-ssid]
  (try
    (net-client/send-to-server
      (matrix-msgs/msg :change-ssid)
      (assoc (net-helpers/tile-pos-payload tile)
             :new-ssid new-ssid))
    (catch Exception e
      (log/error "Error sending change-ssid:" (.getMessage e)))))

(defn send-change-password
  "Change network password
  
  Args:
  - tile: TileMatrix instance
  - new-password: String"
  [tile new-password]
  (try
    (net-client/send-to-server
      (matrix-msgs/msg :change-password)
      (assoc (net-helpers/tile-pos-payload tile)
             :new-password new-password))
    (catch Exception e
      (log/error "Error sending change-password:" (.getMessage e)))))

;; ============================================================================
;; Component Builders
;; ============================================================================

;; ============================================================================
;; InfoArea Builder (使用共享TechUI组件)
;; ============================================================================

(defn rebuild-info-area!
  "Rebuild InfoArea based on network state (参照Scala GuiMatrix2.rebuildInfo)
  
  Args:
  - info-area: InfoArea widget
  - tile: TileMatrix
  - player: EntityPlayer
  - data: MatrixNetworkData"
  [info-area tile player data]
  (try
    (let [is-owner? (= (.getPlacerName tile) (.getName player))]
      
      ;; Clear current content
      (tech-ui/reset-info-area! info-area)
      
      ;; Build histogram
      (let [y (tech-ui/add-histogram
                info-area
                [(tech-ui/hist-capacity
                   (fn [] (:load data))
                   (.getCapacity tile))]
                10)]
        
        ;; Add separator - Info
        (let [y (tech-ui/add-sepline info-area "Info" y)]
          
          ;; Basic properties (always visible)
          (let [y (tech-ui/add-property info-area "Owner" (.getPlacerName tile) y)
                y (tech-ui/add-property info-area "Range" 
                                        (str (.getRange tile) " blocks")
                                        y)
                y (tech-ui/add-property info-area "Bandwidth"
                                        (str (.getBandwidth tile) " IF/T")
                                        y)]
            
            (if (network-initialized? data)
              ;; Network initialized: show wireless info
              (let [y (tech-ui/add-sepline info-area "Wireless Info" y)]
                (if is-owner?
                  ;; Owner: editable SSID and password
                  (let [y (tech-ui/add-property 
                            info-area "SSID" (:ssid data) y
                            :editable? true
                            :on-change (fn [new-ssid]
                                        (send-change-ssid tile new-ssid)))
                        y (tech-ui/add-sepline info-area "Change Pass" y)
                        y (tech-ui/add-property
                            info-area "Password" (:password data) y
                            :editable? true
                            :masked? true
                            :on-change (fn [new-pass]
                                        (send-change-password tile new-pass)))]
                    y)
                  ;; Non-owner: read-only
                  (let [y (tech-ui/add-property info-area "SSID" (:ssid data) y)
                        y (tech-ui/add-property info-area "Password" (:password data) y
                                                :masked? true)]
                    y)))
              
              ;; Network not initialized
              (if is-owner?
                ;; Owner: show init form
                (let [ssid-atom (atom "")
                      pass-atom (atom "")
                      y (tech-ui/add-sepline info-area "Wireless Init" y)
                      y (tech-ui/add-property
                          info-area "SSID" @ssid-atom y
                          :editable? true
                          :color-change? false
                          :on-change (fn [v] (reset! ssid-atom v)))
                      y (tech-ui/add-property
                          info-area "Password" @pass-atom y
                          :editable? true
                          :masked? true
                          :color-change? false
                          :on-change (fn [v] (reset! pass-atom v)))
                      y (+ y 1)
                      y (tech-ui/add-button
                          info-area "INIT"
                          (fn []
                            (send-init-network tile @ssid-atom @pass-atom
                              (fn [success]
                                (when success
                                  (send-gather-info tile
                                    (fn [new-data]
                                      (rebuild-info-area! info-area tile player new-data)))))))
                          y)]
                  y)
                ;; Non-owner: show message
                (let [y (tech-ui/add-sepline info-area "Wireless NoInit" y)]
                  y)))))))
    (catch Exception e
      (log/error "Error rebuilding info area:" (.getMessage e)))))

;; ============================================================================
;; Main GUI Factory
;; ============================================================================

(defn create-matrix-gui
  "Create Wireless Matrix GUI (参照Scala GuiMatrix2)
  
  Args:
  - container: ContainerMatrix instance
  - player: EntityPlayer who opened GUI
  
  Returns: Root CGui widget"
  [container player]
  (try
    (let [tile (or (:tile-entity container)
                   (try (.tile container) (catch Exception _ nil)))
          
          ;; Create inventory page using shared builder
          inv-page (tech-ui/create-inventory-page "matrix")
          
          ;; Create main TechUI widget
          main-widget (cgui/create-container :pos [-18 0] :size [gui-width gui-height])
          ;;tech-ui (apply tech-ui/create-tech-ui pages)
          
          ;; Create InfoArea
          info-area (tech-ui/create-info-area)]
      
      ;; Add inventory page window
      (cgui/add-widget! main-widget (:window inv-page))
      
      ;; Position info area
      (cgui/set-position! info-area
        (+ (cgui/get-width main-widget) 7)
        5)
      
      ;; Initialize network data and build InfoArea
      (send-gather-info tile
        (fn [data]
          (rebuild-info-area! info-area tile player data)))
      
      ;; Add info area to main widget
      (cgui/add-widget! main-widget info-area)
      
      (log/info "Created Wireless Matrix GUI")
      main-widget)
    (catch Exception e
      (log/error "Error creating Matrix GUI:" (.getMessage e))
      (throw e))))

;; ============================================================================
;; Screen Creation
;; ============================================================================

(defn create-screen
  "Create screen container for GUI
  
  Args:
  - container: ContainerMatrix
  - minecraft-container: Minecraft Container
  - player: EntityPlayer
  
  Returns: CGuiScreenContainer"
  [container minecraft-container player]
  (let [root (create-matrix-gui container player)
        base (cgui/create-cgui-screen-container root minecraft-container)]
    (tech-ui/assoc-tech-ui-screen-size base)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn open-matrix-gui
  "Open Wireless Matrix GUI for player"
  [container player]
  (create-matrix-gui container player))

(defn init!
  "Initialize Matrix GUI module"
  []
  (log/info "Wireless Matrix GUI XML module initialized"))
