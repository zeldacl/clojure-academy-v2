(ns cn.li.ac.wireless.gui.matrix-network-handler
  "Wireless Matrix GUI - 服务端网络消息处理器

  处理客户端发送的网络消息：
  - MSG_GATHER_INFO: 查询网络信息
  - MSG_INIT: 初始化网络
  - MSG_CHANGE_SSID: 修改SSID
  - MSG_CHANGE_PASSWORD: 修改密码

  权限验证：
  - 初始化网络：只有放置者
  - 修改SSID/密码：只有放置者"

  (:require [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.wireless.helper :as helper]
            [cn.li.ac.wireless.network :as wireless-net]
            [cn.li.ac.wireless.gui.matrix-messages :as matrix-msgs]
            [cn.li.ac.wireless.gui.wireless-messages :as wireless-msgs]
            [cn.li.ac.wireless.gui.network-handler-helpers :as net-helpers]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log])
  (:import [my_mod.api.wireless IWirelessMatrix]))

;; ==================== 工具函数 ====================

(defn get-wireless-network
  "获取Matrix绑定的无线网络"
  [tile]
  (helper/get-wireless-net-by-matrix tile))

(defn is-owner?
  "检查玩家是否是Matrix的所有者"
  [^ IWirelessMatrix tile player]
  (let [player-name (entity/player-get-name player)
        placer-name (.getPlacerName tile)]
    (= (str placer-name) (str player-name))))

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
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if-let [network (and tile (get-wireless-network tile))]
    ;; 网络已创建
    {:ssid (:ssid network)
     :password (:password network)
     :load (wireless-net/get-load network)
     :initialized true}
    
    ;; 网络未创建
    {:ssid nil
     :password nil
     :load 0
     :initialized false})))

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
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        {:keys [ssid password]} payload]
    (if (and tile (is-owner? tile player))
      (try
        {:success (boolean (helper/create-network! tile ssid password))}
        (catch Exception e
          (log/error "Failed to initialize network:" (.getMessage e))
          {:success false}))
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
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        new-ssid (:new-ssid payload)]
    (if (and tile (is-owner? tile player))
      (if-let [network (get-wireless-network tile)]
        (try
          (let [old-ssid (:ssid network)]
            (wireless-net/reset-ssid! network new-ssid)
            (swap! (:net-lookup (:world-data network)) dissoc old-ssid)
            (swap! (:net-lookup (:world-data network)) assoc new-ssid network)
            {:success true})
          (catch Exception e
            (log/error "Failed to change SSID:" (.getMessage e))
            {:success false}))
        {:success false})
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
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        new-password (:new-password payload)]
    (if (and tile (is-owner? tile player))
      (if-let [network (get-wireless-network tile)]
        (try
          (wireless-net/reset-password! network new-password)
          {:success true}
          (catch Exception e
            (log/error "Failed to change password:" (.getMessage e))
            {:success false}))
        {:success false})
      {:success false})))

;; ==================== 注册 ====================

(defn register-handlers!
  "注册所有消息处理器
  
  在模组初始化时调用。"
  []
  ;; 注册MSG_GATHER_INFO处理器
  (net-server/register-handler
    (matrix-msgs/msg :gather-info)
    handle-gather-info)
  
  ;; 注册MSG_INIT处理器
  (net-server/register-handler
    (matrix-msgs/msg :init)
    handle-init-network)
  
  ;; 注册MSG_CHANGE_SSID处理器
  (net-server/register-handler
    (matrix-msgs/msg :change-ssid)
    handle-change-ssid)
  
  ;; 注册MSG_CHANGE_PASSWORD处理器
  (net-server/register-handler
    (matrix-msgs/msg :change-password)
    handle-change-password)
  
  (log/info "Matrix GUI network handlers registered"))

;; ==================== 导出 ====================

(defn init!
  "初始化Matrix GUI网络模块
  
  注册消息处理器。"
  []
  ;; Touching catalog ensures cross-domain validation runs at init time.
  (:specs wireless-msgs/catalog)
  (register-handlers!))
