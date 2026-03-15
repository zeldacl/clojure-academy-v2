(ns my-mod.gui.tabbed-gui
  "AC 通用：多页签 TechUI 约定与辅助。

  约定：
  - 同一 UI、同一 container；切换页签不替换 container，只更新「当前页签」状态。
  - 多页 create-tech-ui 下，第一页固定为 inv-window（id \"inv\"），tab-index = 0，唯一启用 slot 的页签。
  - 其余页（wireless-panel、matrix-panel 等）对应 tab-index >= 1，不启用 slot。
  - 切换页签 = 改变对应 tab 的布局显示（client CGui set-visible!）+ 根据 tab-index 启用/禁用 slot 交互。
  - 凡「inv-window + 其他 panel」的 GUI，其 container 提供 :tab-index atom 存当前页签索引。
  平台层通过「是否存在 :tab-index」判断是否为多页签 GUI，决定是否添加 DataSlot、条件 Slot 与高亮控制。"
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.network.client :as net-client]
            [my-mod.network.server :as net-server]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Constants & protocol (platform-agnostic)
;; ============================================================================

(def inv-tab-index
  "Tab index for the inventory (inv-window) page. Only this tab has slot interaction enabled."
  0)

(defn slots-active?
  "True when the current tab is inv-window (slot interaction and highlight should be enabled).
   Call only when container has :tab-index; returns false if :tab-index is missing."
  [container]
  (boolean
   (when (contains? container :tab-index)
     (zero? @(:tab-index container)))))

(defn tabbed-container?
  "True if container supports tab switching (has :tab-index). Used by platform to add DataSlot and conditional slots."
  [container]
  (contains? container :tab-index))

;; Client-side tab index by container id so menu.clicked() can resolve tab even when container ref differs (e.g. integrated server).
(defonce ^:private tab-index-by-container-id (atom {}))

(defn set-tab-index-by-container-id!
  "Set current tab index for a container id (client-side). Called from on-tab-change so menu.clicked() can block slot packets."
  [container-id tab-index]
  (when (integer? container-id)
    (swap! tab-index-by-container-id assoc (int container-id) (int tab-index))))

(defn get-tab-index-by-container-id
  "Get tab index for container id, or nil if not set."
  [container-id]
  (when (integer? container-id)
    (get @tab-index-by-container-id (int container-id))))

(defn clear-tab-index-by-container-id!
  "Clear tab state when menu is closed."
  [container-id]
  (when (integer? container-id)
    (swap! tab-index-by-container-id dissoc (int container-id))))

(defn slots-active-for-menu?
  "True when slot clicks should be allowed for this menu. Prefers client-set tab by container id, else container :tab-index."
  [menu container]
  (let [cid (when menu (gui/get-menu-container-id menu))
        tid (when cid (get-tab-index-by-container-id cid))]
    (if (some? tid)
      (zero? (int tid))
      (slots-active? container))))

(defn page-id->index
  "Map page id (e.g. \"inv\", \"wireless\") to 0-based tab index from pages sequence.
   pages: seq of {:id string :window widget}
   Returns index or nil if id not found."
  [pages id]
  (let [idx (first (keep-indexed (fn [i p] (when (= (:id p) id) i)) pages))]
    (when (integer? idx) idx)))

(defn index->page-id
  "Map 0-based tab index to page id. Returns nil if index out of range."
  [pages index]
  (when (and (>= index 0) (< index (count pages)))
    (:id (nth pages index))))

;; ============================================================================
;; Set-tab C2S handler (server)
;; ============================================================================

(def ^:const set-tab-msg-id "set-tab")

(defn- get-player-menu
  "Get player's current open container menu. Tries field 'containerMenu' then method 'getContainerMenu' (Mojang 1.20.1)."
  [player]
  (or (try
        (let [klass (class player)
              f (.getDeclaredField klass "containerMenu")]
          (.setAccessible f true)
          (.get f player))
        (catch Exception _ nil))
      (try
        (clojure.lang.Reflector/invokeInstanceMethod player "getContainerMenu" (object-array []))
        (catch Exception _ nil))))

(defn- handle-set-tab
  "Server handler for set-tab: update the container that the player's OPEN MENU uses (so clicked/slots see it).
   Prefer container from player.containerMenu so we update the same instance the menu uses; fallbacks by id/active/player."
  [payload player]
  (let [tab-index (int (or (:tab-index payload) 0))
        menu (get-player-menu player)
        ;; Must update the container attached to the menu that will handle slot clicks (may be client-created in integrated)
        container (or (when menu (gui/get-container-for-menu menu))
                      (when (and menu (gui/get-menu-container-id menu))
                        (gui/get-container-by-id (gui/get-menu-container-id menu)))
                      (when (some? (:container-id payload))
                        (gui/get-container-by-id (int (:container-id payload))))
                      (gui/get-player-container-from-active player)
                      (gui/get-player-container player))]
    (if (and container (tabbed-container? container))
      (do
        (reset! (:tab-index container) tab-index)
        (log/info "Set tab-index to" tab-index "for player" (.getName player)))
      (when-not container
        (log/warn "set-tab: no container for player" (.getName player)))))
  {})

(defn register-set-tab-handler!
  "Register the set-tab C2S handler. Call once during init (e.g. from core)."
  []
  (net-server/register-handler set-tab-msg-id handle-set-tab)
  (log/info "Registered set-tab network handler"))

(defn send-set-tab!
  "Client: send tab index and optional container-id to server. container-id from client menu so server can find container.
   tab-index: 0 = inv-window (slots enabled), >= 1 = other panels (slots disabled)."
  ([tab-index]
   (send-set-tab! tab-index nil))
  ([tab-index container-id]
   (net-client/send-to-server set-tab-msg-id
     (cond-> {:tab-index (int tab-index)}
       (some? container-id) (assoc :container-id (int container-id))))))
