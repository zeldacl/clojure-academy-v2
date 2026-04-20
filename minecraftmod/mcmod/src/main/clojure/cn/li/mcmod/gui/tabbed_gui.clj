(ns cn.li.mcmod.gui.tabbed-gui
  "AC 通用：多页签 TechUI 约定与辅助。

   This is shared client/server tab switching logic used by platform GUI/menu bridges."
  (:require [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

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
  "Map page id (e.g. \"inv\", \"panel\") to 0-based tab index from pages sequence.
   pages: seq of {:id string :window widget}"
  [pages id]
  (let [idx (first (keep-indexed (fn [i p] (when (= (:id p) id) i)) pages))]
    (when (integer? idx) idx)))

(defn index->page-id
  "Map 0-based tab index to page id. Returns nil if index out of range."
  [pages index]
  (when (and (>= index 0) (< index (count pages)))
    (:id (nth pages index))))

(defn switch-tab!
  "Client: show the page whose `:id` is `page-id`, hide others, and `reset!` TechUI `:current`.
  Use with the map from your screen builder after `attach-tab-sync!` so
  server `:tab-index` and slot gating stay in sync."
  [tech-ui pages page-id]
  (when-let [cur (:current tech-ui)]
    (doseq [q pages]
      (when-let [w (:window q)]
        (cgui/set-visible! w (= page-id (:id q)))))
    (reset! cur page-id)))

;; ============================================================================
;; Set-tab C2S handler (server)
;; ============================================================================

(def ^:const set-tab-msg-id "set-tab")

(defn- get-player-menu
  "Get player's current open container menu. Tries field 'containerMenu' then method 'getContainerMenu' (Mojang 1.20.1)."
  [player]
  (try
    (entity/player-get-container-menu player)
    (catch Exception _ nil)))

(defn- handle-set-tab
  "Server handler for set-tab: update the container that the player's OPEN MENU uses
   (so clicked/slots see it)."
  [payload player]
  (let [tab-index (int (or (:tab-index payload) 0))
        menu (get-player-menu player)
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
        (log/info "Set tab-index to" tab-index "for player" (entity/player-get-name player)))
      (when-not container
        (log/warn "set-tab: no container for player" (entity/player-get-name player)))))
  {})

(defn register-set-tab-handler!
  "Register the set-tab C2S handler. Call once during init (e.g. from core)."
  []
  (net-server/register-handler set-tab-msg-id handle-set-tab)
  (log/info "Registered set-tab network handler"))

(defn send-set-tab!
  "Client: send tab index and optional container-id to server.
   tab-index: 0 = inv-window (slots enabled), >= 1 = other panels (slots disabled)."
  ([tab-index]
   (send-set-tab! tab-index nil))
  ([tab-index container-id]
   (net-client/send-to-server set-tab-msg-id
     (cond-> {:tab-index (int tab-index)}
       (some? container-id) (assoc :container-id (int container-id))))))

(defn attach-tab-sync!
  "Attach generic tab-change sync between a TechUI instance and a container.

   pages: sequential collection of page maps (same sequence passed to create-tech-ui)
   tech-ui: map returned by create-tech-ui (expects :current atom)
   container: container map (may contain :tab-index atom)
   container-id: optional integer id from `gui/get-menu-container-id`"
  [pages tech-ui container container-id]
  (let [current-atom (:current tech-ui)]
    (when (some? current-atom)
      (add-watch current-atom :tab-sync
        (fn [_ _ _ new-id]
          (when-let [idx (page-id->index pages new-id)]
            (when (tabbed-container? container)
              (reset! (:tab-index container) (int idx)))
            (when (integer? container-id)
              (set-tab-index-by-container-id! container-id (int idx)))
            (send-set-tab! idx container-id)))))

    ;; initial sync of current tab
    (when-let [cur (and current-atom @current-atom)]
      (when-let [idx (page-id->index pages cur)]
        (when (tabbed-container? container)
          (reset! (:tab-index container) (int idx)))
        (when (integer? container-id)
          (set-tab-index-by-container-id! container-id (int idx)))
        (send-set-tab! idx container-id)))))

