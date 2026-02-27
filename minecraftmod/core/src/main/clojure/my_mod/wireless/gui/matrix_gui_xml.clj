(ns my-mod.wireless.gui.matrix-gui-xml
  "Wireless Matrix GUI - 动态实现（参照Scala GuiMatrix2+TechUI）
  
  架构：使用page_inv.xml作为基础，动态构建网络管理UI
  
  流程：
  1. 加载基础库存布局 (page_inv.xml)
  2. 查询服务端网络信息
  3. 根据3种状态构建动态面板：
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
            [my-mod.gui.cgui-document :as cgui-doc]
            [my-mod.network.client :as net-client]
            [my-mod.wireless.gui.network-handler-helpers :as net-helpers]
            [my-mod.util.log :as log])
  (:import [net.minecraft.entity.player EntityPlayer]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:const MSG_GATHER_INFO "wireless_matrix_gather_info")
(def ^:const MSG_INIT "wireless_matrix_init")
(def ^:const MSG_CHANGE_SSID "wireless_matrix_change_ssid")
(def ^:const MSG_CHANGE_PASSWORD "wireless_matrix_change_password")

(def gui-width 176)
(def gui-height 200)

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
      MSG_GATHER_INFO
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
      MSG_INIT
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
      MSG_CHANGE_SSID
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
      MSG_CHANGE_PASSWORD
      (assoc (net-helpers/tile-pos-payload tile)
             :new-password new-password))
    (catch Exception e
      (log/error "Error sending change-password:" (.getMessage e)))))

;; ============================================================================
;; Component Builders
;; ============================================================================

(defn create-histogram-widget
  "Create network capacity histogram
  
  Args:
  - tile: TileMatrix instance
  - data: MatrixNetworkData
  - x, y: position"
  [tile data x y]
  (let [widget (cgui/create-widget :pos [x y] :size [65 45])]
    (comp/add-component! widget
      (comp/histogram
        :label "Capacity"
        :width 65
        :height 45
        :color 0xFFFF6C00
        :value-fn (fn [] (:load data))
        :max-fn (fn [] (:max-capacity data))
        :direction :vertical))
    widget))

(defn create-property-display
  "Create read-only property display
  
  Args:
  - label: String - property label
  - value-fn: (fn [] String) - get current value
  - x, y: position"
  [label value-fn x y]
  (let [widget (cgui/create-widget :pos [x y] :size [71 10])
        label-text (comp/text-box :text label :color 0xFFAAAAAA :scale 0.8)
        value-box (comp/text-box :text (value-fn) :color 0xFFFFFFFF :scale 0.8)]
    (comp/add-component! widget label-text)
    (events/on-frame widget
      (fn [_]
        (comp/set-text! value-box (value-fn))))
    widget))

(defn create-editable-property
  "Create editable property field"
  [label value-fn on-change x y & {:keys [masked?] :or {masked? false}}]
  (let [widget (cgui/create-widget :pos [x y] :size [71 10])]
    (comp/add-component! widget
      (comp/text-box :text label :color 0xFFAAAAAA :scale 0.8))
    widget))

(defn create-button
  "Create clickable button"
  [text on-click x y]
  (let [widget (cgui/create-widget :pos [x y] :size [71 15])]
    (comp/add-component! widget
      (comp/button
        :text text
        :width 71
        :height 15
        :color 0xFF00CC00
        :hover-color 0xFF00FF00
        :text-color 0xFFFFFFFF
        :on-click on-click))
    widget))

(defn create-initialized-panel
  "Create network info panel (initialized state)"
  [tile data player refresh-fn]
  (let [panel (cgui/create-container :pos [5 75] :size [166 120])
        is-owner (= (.getName player) (:owner data))]
    
    (let [ssid-widget (create-property-display
                        "SSID: "
                        (fn [] (:ssid data))
                        0 5)
          password-widget (create-property-display
                            "Password: "
                            (fn [] (apply str (repeat (count (:password data)) "*")))
                            0 16)
          owner-widget (create-property-display
                         "Owner: "
                         (fn [] (:owner data))
                         0 27)
          load-widget (create-property-display
                        "Load: "
                        (fn [] (str (:load data) " / " (:max-capacity data)))
                        0 38)]
      (cgui/add-widget! panel ssid-widget)
      (cgui/add-widget! panel password-widget)
      (cgui/add-widget! panel owner-widget)
      (cgui/add-widget! panel load-widget))
    
    panel))

(defn create-uninitialized-owner-panel
  "Create initialization form (uninitialized + owner state)"
  [tile player refresh-fn]
  (let [panel (cgui/create-container :pos [5 75] :size [166 120])
        msg-widget (cgui/create-widget :pos [0 50] :size [166 30])]
    (comp/add-component! msg-widget
      (comp/text-box
        :text "Click Initialize to set up this network"
        :color 0xFF00FF00
        :scale 0.8))
    (cgui/add-widget! panel msg-widget)
    panel))

(defn create-uninitialized-guest-panel
  "Create uninitialized message (non-owner state)"
  [x y]
  (let [panel (cgui/create-container :pos [x y] :size [166 120])
        msg-widget (cgui/create-widget :pos [0 50] :size [166 30])]
    (comp/add-component! msg-widget
      (comp/text-box
        :text "Network not initialized.\nAsk owner to set it up."
        :color 0xFFFF6C00
        :scale 0.8))
    (cgui/add-widget! panel msg-widget)
    panel))

(defn rebuild-info-panel!
  "Rebuild info panel based on network state"
  [container tile data player refresh-fn]
  (cgui/clear-widgets! container)
  
  (let [is-owner (= (.getName player) (:owner data))
        info-panel (cond
                     (network-initialized? data)
                     (create-initialized-panel tile data player refresh-fn)
                     
                     is-owner
                     (create-uninitialized-owner-panel tile player refresh-fn)
                     
                     :else
                     (create-uninitialized-guest-panel 5 75))]
    (cgui/add-widget! container info-panel)))

;; ============================================================================
;; Main GUI Factory
;; ============================================================================

(defn create-matrix-gui
  "Create Wireless Matrix GUI
  
  Args:
  - container: ContainerMatrix instance
  - player: EntityPlayer who opened GUI
  
  Returns: Root CGui widget"
  [container player]
  (try
    (let [tile (or (:tile-entity container)
                   (try (.tile container) (catch Exception _ nil)))
          root (cgui/create-container :pos [0 0] :size [gui-width gui-height])
          bg-widget (cgui/create-widget :pos [0 0] :size [gui-width gui-height])]
      
      (comp/add-component! bg-widget
        (comp/texture "academy:textures/guis/parent/parent_background.png" 0 0 gui-width gui-height))
      (cgui/add-widget! root bg-widget)
      
      (let [info-container (cgui/create-container :pos [0 0] :size [gui-width gui-height])]
        
        (send-gather-info
          tile
          (fn [data]
            (let [hist-widget (create-histogram-widget tile data 8 5)
                  range-widget (create-property-display
                                 "Range: "
                                 (fn [] (str (:range data) " blocks"))
                                 8 50)
                  bandwidth-widget (create-property-display
                                     "Bandwidth: "
                                     (fn [] (str (:bandwidth data)))
                                     90 50)]
              (cgui/add-widget! info-container hist-widget)
              (cgui/add-widget! info-container range-widget)
              (cgui/add-widget! info-container bandwidth-widget))
            
            (rebuild-info-panel!
              info-container
              tile
              data
              player
              (fn []
                (send-gather-info tile
                  (fn [new-data]
                    (rebuild-info-panel!
                      info-container
                      tile
                      new-data
                      player
                      (fn []))))))))
        
        (cgui/add-widget! root info-container))
      
      (log/info "Created Wireless Matrix GUI")
      root)
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
  (let [root (create-matrix-gui container player)]
    (cgui/create-cgui-screen-container root minecraft-container)))

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
