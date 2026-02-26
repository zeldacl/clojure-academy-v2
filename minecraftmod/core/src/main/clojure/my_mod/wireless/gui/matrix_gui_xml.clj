(ns my-mod.wireless.gui.matrix-gui-xml
  "Wireless Matrix GUI - XML驱动实现
  
  功能：
  - 网络矩阵管理界面
  - 网络初始化（创建SSID和密码）
  - 网络信息编辑（修改SSID和密码）
  - 网络负载监控
  
  实现方式：
  - XML布局加载：page_wireless_matrix.xml
  - DSL集成：defgui-from-xml宏
  - 动态UI：根据网络初始化状态切换显示内容
  
  状态逻辑：
  1. 未初始化：显示初始化表单（SSID输入框 + 密码输入框 + INIT按钮）
  2. 已初始化：显示网络信息（SSID + 密码，所有者可编辑）"
  
  (:require [my-mod.gui.xml-parser :as xml]
            [my-mod.gui.components :as comp]
            [my-mod.gui.dsl :as dsl]
            [my-mod.network.client :as net-client]
            [my-mod.wireless.network :as wireless-net])
  (:import [net.minecraft.entity.player EntityPlayer]
           [net.minecraft.client Minecraft]))

;; ==================== 网络消息通道 ====================

(def ^:const MSG_GATHER_INFO "wireless_matrix_gather_info")
(def ^:const MSG_INIT "wireless_matrix_init")
(def ^:const MSG_CHANGE_SSID "wireless_matrix_change_ssid")
(def ^:const MSG_CHANGE_PASSWORD "wireless_matrix_change_password")

;; ==================== 数据结构 ====================

(defrecord NetworkInitData
  [ssid        ;; String | nil - 网络SSID（nil表示未初始化）
   password    ;; String | nil - 网络密码
   load        ;; int - 当前网络负载（连接的设备数）
   initialized ;; boolean - 是否已初始化
   ])

(defn init-data->initialized? [init-data]
  "判断网络是否已初始化"
  (boolean (:ssid init-data)))

(defn- tile-pos-payload
  "Extract position payload from a tile entity"
  [tile]
  (let [pos (.getPos tile)]
    {:pos-x (.getX pos)
     :pos-y (.getY pos)
     :pos-z (.getZ pos)}))

;; ==================== 网络消息发送 ====================

(defn send-gather-info
  "查询网络信息
  
  发送MSG_GATHER_INFO消息到服务端，查询网络的SSID、密码、负载信息。
  回调函数接收NetworkInitData记录。
  
  参数：
  - tile: TileMatrix实例
  - callback: (fn [NetworkInitData] ...) - 接收查询结果"
  [tile callback]
  (net-client/send-to-server
    MSG_GATHER_INFO
    (tile-pos-payload tile)
    (fn [response]
      (let [init-data (map->NetworkInitData
                        {:ssid (:ssid response)
                         :password (:password response)
                         :load (:load response 0)
                         :initialized (boolean (:ssid response))})]
        (callback init-data)))))

(defn send-init-network
  "初始化网络
  
  发送MSG_INIT消息到服务端，创建新的无线网络。
  
  参数：
  - tile: TileMatrix实例
  - ssid: String - 网络名称
  - password: String - 网络密码
  - callback: (fn [success?] ...) - 接收初始化结果（true/false）"
  [tile ^String ssid ^String password callback]
  (net-client/send-to-server
    MSG_INIT
    (assoc (tile-pos-payload tile)
           :ssid ssid
           :password password)
    (fn [response]
      (callback (:success response false)))))

(defn send-change-ssid
  "修改网络SSID
  
  发送MSG_CHANGE_SSID消息到服务端。
  只有网络所有者可以修改。
  
  参数：
  - tile: TileMatrix实例
  - new-ssid: String - 新的SSID"
  [tile ^String new-ssid]
  (net-client/send-to-server
    MSG_CHANGE_SSID
    (assoc (tile-pos-payload tile)
           :new-ssid new-ssid)))

(defn send-change-password
  "修改网络密码
  
  发送MSG_CHANGE_PASSWORD消息到服务端。
  只有网络所有者可以修改。
  
  参数：
  - tile: TileMatrix实例
  - new-password: String - 新的密码"
  [tile ^String new-password]
  (net-client/send-to-server
    MSG_CHANGE_PASSWORD
    (assoc (tile-pos-payload tile)
           :new-password new-password)))

;; ==================== 组件创建 ====================

(defn create-histogram-widget
  "创建直方图组件
  
  复用自Node GUI的实现，用于显示网络负载。
  
  参数：
  - spec: XML解析的Histogram规格
  - tile: TileMatrix实例
  - type: :capacity - 容量类型"
  [spec tile type]
  (let [label (get-in spec [:content :label])
        color (get-in spec [:content :color] "#ff6c00")
        x (get-in spec [:content :x] 0)
        y (get-in spec [:content :y] 0)
        width (get-in spec [:content :width] 65)
        height (get-in spec [:content :height] 45)]
    (case type
      :capacity
      (comp/histogram
        :label label
        :x x :y y :width width :height height
        :color color
        :value-fn (fn [] (.getLoad tile))
        :max-fn (fn [] (.getCapacity tile))
        :direction :vertical)
      
      (throw (ex-info "Unknown histogram type" {:type type})))))

(defn create-property-widget
  "创建属性字段组件
  
  复用自Node GUI的实现，支持只读和可编辑两种模式。
  
  参数：
  - spec: XML解析的Property规格
  - tile: TileMatrix实例
  - player: EntityPlayer - 当前玩家
  - on-change: (fn [new-value] ...) - 值改变回调（可编辑模式）"
  [spec tile ^EntityPlayer player on-change]
  (let [label (get-in spec [:content :label])
        x (get-in spec [:content :x] 0)
        y (get-in spec [:content :y] 0)
        width (get-in spec [:content :width] 71)
        editable? (get-in spec [:content :editable] false)
        masked? (get-in spec [:content :masked] false)
        requires-owner? (get-in spec [:content :requiresOwner] false)
        label-color (get-in spec [:content :labelColor] "#aaaaaa")
        value-color (get-in spec [:content :valueColor] "#ffffff")
        max-length (get-in spec [:content :maxLength] 16)]
    
    ;; 权限检查
    (let [is-owner? (= (.getPlacerName tile) (.getName player))
          actual-editable? (and editable?
                              (or (not requires-owner?) is-owner?))]
      (comp/property-field
        :label label
        :x x :y y :width width
        :editable actual-editable?
        :masked masked?
        :label-color label-color
        :value-color value-color
        :max-length max-length
        :on-change on-change))))

(defn create-text-field-widget
  "创建文本输入框组件
  
  用于初始化表单中的SSID和密码输入。
  
  参数：
  - spec: XML解析的TextField规格"
  [spec]
  (let [label (get-in spec [:content :label])
        x (get-in spec [:content :x] 0)
        y (get-in spec [:content :y] 0)
        width (get-in spec [:content :width] 71)
        height (get-in spec [:content :height] 10)
        max-length (get-in spec [:content :maxLength] 32)
        masked? (get-in spec [:content :masked] false)
        placeholder (get-in spec [:content :placeholder] "")
        label-color (get-in spec [:content :labelColor] "#aaaaaa")
        text-color (get-in spec [:content :textColor] "#ffffff")
        bg-color (get-in spec [:content :backgroundColor] "#333333")]
    (comp/text-field
      :label label
      :x x :y y :width width :height height
      :max-length max-length
      :masked masked?
      :placeholder placeholder
      :label-color label-color
      :text-color text-color
      :background-color bg-color)))

(defn create-button-widget
  "创建按钮组件
  
  用于初始化表单的INIT按钮。
  
  参数：
  - spec: XML解析的Button规格
  - on-click: (fn [] ...) - 点击回调"
  [spec on-click]
  (let [text (get-in spec [:content :text])
        x (get-in spec [:content :x] 0)
        y (get-in spec [:content :y] 0)
        width (get-in spec [:content :width] 71)
        height (get-in spec [:content :height] 15)
        color (get-in spec [:content :color] "#00cc00")
        hover-color (get-in spec [:content :hoverColor] "#00ff00")
        text-color (get-in spec [:content :textColor] "#ffffff")]
    (comp/button
      :text text
      :x x :y y :width width :height height
      :color color
      :hover-color hover-color
      :text-color text-color
      :on-click on-click)))

;; ==================== 动态面板构建 ====================

(defn build-network-info-panel
  "构建网络信息面板（已初始化状态）
  
  显示SSID和密码，所有者可编辑。
  
  参数：
  - xml-spec: XML解析的network_info_panel规格
  - tile: TileMatrix实例
  - player: EntityPlayer
  - init-data: NetworkInitData - 当前网络信息"
  [xml-spec tile ^EntityPlayer player init-data]
  (let [ssid-spec (xml/find-child-by-name xml-spec "prop_ssid")
        password-spec (xml/find-child-by-name xml-spec "prop_password")]
    {:type :container
     :x (get-in xml-spec [:content :x] 0)
     :y (get-in xml-spec [:content :y] 0)
     :children [(create-property-widget
                  ssid-spec
                  tile
                  player
                  (fn [new-ssid]
                    (send-change-ssid tile new-ssid)))
                
                (create-property-widget
                  password-spec
                  tile
                  player
                  (fn [new-password]
                    (send-change-password tile new-password)))]}))

(defn build-init-form-panel
  "构建初始化表单面板（未初始化状态）
  
  显示SSID输入框、密码输入框和INIT按钮。
  点击INIT按钮后，发送初始化请求。
  
  参数：
  - xml-spec: XML解析的init_form_panel规格
  - tile: TileMatrix实例
  - player: EntityPlayer
  - rebuild-callback: (fn [] ...) - 重建UI的回调（初始化成功后调用）"
  [xml-spec tile ^EntityPlayer player rebuild-callback]
  (let [ssid-input-spec (xml/find-child-by-name xml-spec "input_ssid")
        password-input-spec (xml/find-child-by-name xml-spec "input_password")
        button-spec (xml/find-child-by-name xml-spec "btn_initialize")
        
        ;; 创建输入框引用
        ssid-field (atom nil)
        password-field (atom nil)]
    
    {:type :container
     :x (get-in xml-spec [:content :x] 0)
     :y (get-in xml-spec [:content :y] 0)
     :children [(let [field (create-text-field-widget ssid-input-spec)]
                  (reset! ssid-field field)
                  field)
                
                (let [field (create-text-field-widget password-input-spec)]
                  (reset! password-field field)
                  field)
                
                (create-button-widget
                  button-spec
                  (fn []
                    ;; 获取输入框的内容
                    (let [ssid (comp/get-text @ssid-field)
                          password (comp/get-text @password-field)]
                      ;; 发送初始化请求
                      (send-init-network
                        tile
                        ssid
                        password
                        (fn [success]
                          (when success
                            ;; 初始化成功，重新查询并重建UI
                            (send-gather-info tile rebuild-callback)))))))]}))

(defn build-no-init-message
  "构建未初始化提示（非所有者）
  
  当网络未初始化且玩家不是所有者时显示此消息。
  
  参数：
  - xml-spec: XML解析的no_init_message规格"
  [xml-spec]
  (let [msg-spec (xml/find-child-by-name xml-spec "msg_not_initialized")
        text (get-in msg-spec [:content :text] "Network not initialized")
        x (get-in msg-spec [:content :x] 0)
        y (get-in msg-spec [:content :y] 0)
        color (get-in msg-spec [:content :color] "#ff0000")
        scale (get-in msg-spec [:content :scale] 0.5)]
    {:type :text
     :text text
     :x x :y y
     :color color
     :scale scale}))

(defn rebuild-info-panel!
  "动态重建信息面板
  
  这是Matrix GUI的核心逻辑：根据网络初始化状态切换显示内容。
  
  状态1: 未初始化 + 所有者 -> 显示初始化表单
  状态2: 未初始化 + 非所有者 -> 显示提示消息
  状态3: 已初始化 -> 显示网络信息（所有者可编辑）
  
  参数：
  - container-atom: 指向info_panel容器的atom
  - xml-layout: 完整的XML布局
  - tile: TileMatrix实例
  - player: EntityPlayer
  - init-data: NetworkInitData - 当前网络信息"
  [container-atom xml-layout tile ^EntityPlayer player init-data]
  (let [is-owner? (= (.getPlacerName tile) (.getName player))
        info-panel-spec (xml/find-child-by-name xml-layout "info_panel")
        network-info-spec (xml/find-child-by-name info-panel-spec "network_info_panel")
        init-form-spec (xml/find-child-by-name info-panel-spec "init_form_panel")
        no-init-msg-spec (xml/find-child-by-name info-panel-spec "no_init_message")]
    
    ;; 清空当前面板
    (swap! container-atom assoc :children [])
    
    ;; 根据状态重建
    (if (:initialized init-data)
      ;; 已初始化：显示网络信息
      (let [panel (build-network-info-panel
                    network-info-spec
                    tile
                    player
                    init-data)]
        (swap! container-atom update :children conj panel))
      
      ;; 未初始化：显示初始化表单或提示
      (if is-owner?
        ;; 所有者：显示初始化表单
        (let [panel (build-init-form-panel
                      init-form-spec
                      tile
                      player
                      ;; 重建回调：重新查询并重建
                      (fn [new-data]
                        (rebuild-info-panel!
                          container-atom
                          xml-layout
                          tile
                          player
                          new-data)))]
          (swap! container-atom update :children conj panel))
        
        ;; 非所有者：显示提示消息
        (let [message (build-no-init-message no-init-msg-spec)]
          (swap! container-atom update :children conj message))))))

;; ==================== GUI主入口 ====================

(defn create-matrix-gui
  "创建Matrix GUI实例
  
  加载XML布局，创建静态组件（插槽、直方图、基本属性），
  并初始化动态信息面板（根据网络状态切换）。
  
  参数：
  - container: ContainerMatrix实例
  - player: EntityPlayer - 当前玩家
  
  返回：
  - GUI组件树"
  [container ^EntityPlayer player]
  (let [tile (or (:tile-entity container)
                 (try (.tile container) (catch Exception _ nil)))
        
        ;; 加载XML布局
        xml-layout (xml/load-gui-xml "page_wireless_matrix.xml")
        
        ;; 查找关键组件
        root-spec (xml/find-child-by-name xml-layout "root")
        info-panel-spec (xml/find-child-by-name root-spec "info_panel")
        hist-spec (xml/find-child-by-name info-panel-spec "hist_capacity")
        
        ;; 基本属性
        basic-props-spec (xml/find-child-by-name info-panel-spec "basic_properties")
        owner-prop-spec (xml/find-child-by-name basic-props-spec "prop_owner")
        range-prop-spec (xml/find-child-by-name basic-props-spec "prop_range")
        bandwidth-prop-spec (xml/find-child-by-name basic-props-spec "prop_bandwidth")
        
        ;; 创建信息面板容器（动态内容）
        info-panel-atom (atom {:type :container
                               :x (get-in info-panel-spec [:content :x] 0)
                               :y (get-in info-panel-spec [:content :y] 0)
                               :children []})]
    
    ;; 初始化：查询网络信息并构建UI
    (send-gather-info
      tile
      (fn [init-data]
        (rebuild-info-panel!
          info-panel-atom
          xml-layout
          tile
          player
          init-data)))
    
    ;; 返回GUI树
    {:type :container
     :x 0 :y 0
     :width 230 :height 200
     :children [;; 容量直方图
                (create-histogram-widget hist-spec tile :capacity)
                
                ;; 基本属性
                (create-property-widget
                  owner-prop-spec
                  tile
                  player
                  nil) ;; 只读，无回调
                
                (create-property-widget
                  range-prop-spec
                  tile
                  player
                  nil)
                
                (create-property-widget
                  bandwidth-prop-spec
                  tile
                  player
                  nil)
                
                ;; 动态信息面板（引用atom）
                @info-panel-atom]}))

;; ==================== DSL集成 ====================

(dsl/defgui-from-xml wireless-matrix
  "Wireless Matrix GUI - XML驱动版本
  
  使用方式：
  (wireless-matrix container player)
  
  特性：
  - 自动加载page_wireless_matrix.xml布局
  - 动态切换初始化表单/网络信息
  - 所有者权限控制
  - 网络状态实时查询"
  
  :xml-file "page_wireless_matrix.xml"
  
  :init-fn
  (fn [container player]
    (create-matrix-gui container player)))

;; ==================== 导出 ====================

(defn init!
  "初始化Matrix GUI模块
  
  注册网络消息处理器。"
  []
  ;; 注册消息处理器（服务端）
  ;; 实际的消息处理器需要在服务端代码中实现
  (println "Matrix GUI XML module initialized"))
