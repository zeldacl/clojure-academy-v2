(ns my-mod.wireless.gui.matrix-network-handler
  "Wireless Matrix GUI - 服务端网络消息处理器
  
  处理客户端发送的网络消息：
  - MSG_GATHER_INFO: 查询网络信息
  - MSG_INIT: 初始化网络
  - MSG_CHANGE_SSID: 修改SSID
  - MSG_CHANGE_PASSWORD: 修改密码
  
  权限验证：
  - 初始化网络：只有放置者
  - 修改SSID/密码：只有放置者"
  
  (:require [my-mod.network.server :as net-server]
            [my-mod.wireless.network :as wireless-net])
  (:import [net.minecraft.entity.player EntityPlayer]
           [cn.academy.block.tileentity TileMatrix]
           [cn.academy.wireless WirelessHelper]
           [net.minecraftforge.common MinecraftForge]
           [cn.academy.event.wireless CreateNetworkEvent ChangePassEvent]))

;; ==================== 消息通道常量 ====================

(def ^:const MSG_GATHER_INFO "wireless_matrix_gather_info")
(def ^:const MSG_INIT "wireless_matrix_init")
(def ^:const MSG_CHANGE_SSID "wireless_matrix_change_ssid")
(def ^:const MSG_CHANGE_PASSWORD "wireless_matrix_change_password")

;; ==================== 工具函数 ====================

(defn get-wireless-network
  "获取Matrix绑定的无线网络
  
  参数：
  - tile: TileMatrix实例
  
  返回：
  - WirelessNetwork实例或nil"
  [^TileMatrix tile]
  (WirelessHelper/getWirelessNet tile))

(defn is-owner?
  "检查玩家是否是Matrix的所有者
  
  参数：
  - tile: TileMatrix实例
  - player: EntityPlayer实例
  
  返回：
  - boolean"
  [^TileMatrix tile ^EntityPlayer player]
  (= (.getPlacerName tile) (.getName player)))

;; ==================== 消息处理器 ====================

(defn handle-gather-info
  "处理MSG_GATHER_INFO消息
  
  查询网络信息并返回给客户端。
  
  请求数据：
  - :tile - TileMatrix实例
  
  响应数据：
  - :ssid - String | nil
  - :password - String | nil
  - :load - int（当前连接的设备数）
  - :initialized - boolean"
  [{:keys [tile]} player]
  (if-let [network (get-wireless-network tile)]
    ;; 网络已创建
    {:ssid (.getSSID network)
     :password (.getPassword network)
     :load (.getLoad network)
     :initialized true}
    
    ;; 网络未创建
    {:ssid nil
     :password nil
     :load 0
     :initialized false}))

(defn handle-init-network
  "处理MSG_INIT消息
  
  初始化无线网络（创建新网络）。
  只有所有者可以执行此操作。
  
  请求数据：
  - :tile - TileMatrix实例
  - :ssid - String（网络名称）
  - :password - String（网络密码）
  
  响应数据：
  - :success - boolean"
  [{:keys [tile ssid password]} player]
  (if (is-owner? tile player)
    (try
      ;; 发送CreateNetworkEvent事件
      ;; 实际的网络创建由事件监听器处理
      (let [event (CreateNetworkEvent. tile ssid password)]
        (MinecraftForge/EVENT_BUS.post event)
        {:success true})
      
      (catch Exception e
        (println "Failed to initialize network:" (.getMessage e))
        {:success false}))
    
    ;; 非所有者：拒绝操作
    (do
      (println "Player" (.getName player) "attempted to initialize network without permission")
      {:success false})))

(defn handle-change-ssid
  "处理MSG_CHANGE_SSID消息
  
  修改网络SSID。
  只有所有者可以执行此操作。
  
  请求数据：
  - :tile - TileMatrix实例
  - :new-ssid - String（新的SSID）
  
  响应数据：
  - :success - boolean"
  [{:keys [tile new-ssid]} player]
  (if (is-owner? tile player)
    (if-let [network (get-wireless-network tile)]
      (try
        ;; 直接修改SSID
        (.setSSID network new-ssid)
        {:success true}
        
        (catch Exception e
          (println "Failed to change SSID:" (.getMessage e))
          {:success false}))
      
      ;; 网络不存在
      (do
        (println "Cannot change SSID: network not initialized")
        {:success false}))
    
    ;; 非所有者：拒绝操作
    (do
      (println "Player" (.getName player) "attempted to change SSID without permission")
      {:success false})))

(defn handle-change-password
  "处理MSG_CHANGE_PASSWORD消息
  
  修改网络密码。
  只有所有者可以执行此操作。
  
  请求数据：
  - :tile - TileMatrix实例
  - :new-password - String（新的密码）
  
  响应数据：
  - :success - boolean"
  [{:keys [tile new-password]} player]
  (if (is-owner? tile player)
    (if-let [network (get-wireless-network tile)]
      (try
        ;; 发送ChangePassEvent事件
        ;; 实际的密码修改由事件监听器处理
        (let [event (ChangePassEvent. tile new-password)]
          (MinecraftForge/EVENT_BUS.post event)
          {:success true})
        
        (catch Exception e
          (println "Failed to change password:" (.getMessage e))
          {:success false}))
      
      ;; 网络不存在
      (do
        (println "Cannot change password: network not initialized")
        {:success false}))
    
    ;; 非所有者：拒绝操作
    (do
      (println "Player" (.getName player) "attempted to change password without permission")
      {:success false})))

;; ==================== 注册 ====================

(defn register-handlers!
  "注册所有消息处理器
  
  在模组初始化时调用。"
  []
  ;; 注册MSG_GATHER_INFO处理器
  (net-server/register-handler
    MSG_GATHER_INFO
    handle-gather-info)
  
  ;; 注册MSG_INIT处理器
  (net-server/register-handler
    MSG_INIT
    handle-init-network)
  
  ;; 注册MSG_CHANGE_SSID处理器
  (net-server/register-handler
    MSG_CHANGE_SSID
    handle-change-ssid)
  
  ;; 注册MSG_CHANGE_PASSWORD处理器
  (net-server/register-handler
    MSG_CHANGE_PASSWORD
    handle-change-password)
  
  (println "Matrix GUI network handlers registered"))

;; ==================== 导出 ====================

(defn init!
  "初始化Matrix GUI网络模块
  
  注册消息处理器。"
  []
  (register-handlers!))
