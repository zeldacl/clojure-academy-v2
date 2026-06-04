(ns cn.li.mcmod.gui.tabbed-gui
  "AC 通用：多页签 TechUI 约定与辅助。

   This is shared client/server tab switching logic used by platform GUI/menu bridges."
  (:require [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.owner-contract :as owner-contract]
            [cn.li.mcmod.gui.registry-contract :as registry-contract]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
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

(declare send-set-tab!)

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

(defn- require-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Tabbed GUI owner requires %s" label)
                    {:owner owner
                     :required label}))))

(defn- player-key
  [player]
  (when player
    (some-> (entity/player-get-uuid player) str)))

(defn- server-owner-for-player
  [player]
  (let [runtime-owner runtime-hooks/*player-state-owner*
        server-session-id (require-owner-value runtime-owner
                                               ":server-session-id"
                                               (:server-session-id runtime-owner))
        player-id (require-owner-value {:player player}
                                       ":player-uuid"
                                       (some-> player player-key))]
    (owner-contract/require-server-owner
     {:logical-side :server
      :server-session-id server-session-id
      :player-uuid player-id
      :player player})))

(defn set-tab-index-by-container-id!
  "Set current tab index for a container id (client-side)."
  [owner container-id tab-index]
  (when (integer? container-id)
    (container-state/set-tab-index-by-container-id! owner container-id tab-index)))

(defn get-tab-index-by-container-id
  "Get tab index for container id, or nil if not set."
  [owner container-id]
  (when (integer? container-id)
    (container-state/get-tab-index-by-container-id owner container-id)))

(defn clear-tab-index-by-container-id!
  "Clear tab state when menu is closed."
  [owner container-id]
  (when (integer? container-id)
    (container-state/clear-tab-index-by-container-id! owner container-id)))

(defn- owner-client-session-id
  [owner]
  (when (owner-contract/valid-client-owner? owner)
    (:client-session-id owner)))

(defn- send-set-tab-safe!
  [owner tab-index container-id]
  (let [owner-session-id (owner-client-session-id owner)]
    (try
      (if owner-session-id
        (send-set-tab! owner tab-index container-id)
        (log/debug "Skip set-tab sync: missing canonical client owner"
                   {:container-id container-id
                    :tab-index tab-index
                    :owner owner}))
      (catch Exception e
        (log/warn "Skip set-tab sync: send failed"
                  {:container-id container-id
                   :tab-index tab-index
                   :owner owner
                   :reason (ex-message e)})))))

(defn slots-active-for-menu?
  "True when slot clicks should be allowed for this menu. Prefers client-set tab by container id, else container :tab-index."
  [menu container]
  (let [cid (when menu (container-state/get-menu-container-id menu))
      owner (container-state/owner-from-container container)
      tid (when cid (get-tab-index-by-container-id owner cid))]
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
        (cgui-core/set-visible! w (= page-id (:id q)))))
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
        menu-container (when menu (container-state/get-container-for-menu menu))
        owner (if menu-container
                (container-state/owner-from-container menu-container)
                (server-owner-for-player player))
        container (or menu-container
                      (when (and menu (container-state/get-menu-container-id menu))
                        (container-state/get-container-by-id owner (container-state/get-menu-container-id menu)))
                      (when (some? (:container-id payload))
                        (container-state/get-container-by-id owner (int (:container-id payload))))
                      (container-state/get-player-container owner))]
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
  (net-server/register-handler set-tab-msg-id handle-set-tab
                               {:owner-spec :server :payload-routing :none})
  (log/info "Registered set-tab network handler"))

(defn send-set-tab!
  "Client: send tab index and optional container-id to server.
   tab-index: 0 = inv-window (slots enabled), >= 1 = other panels (slots disabled)."
  ([tab-index]
   (send-set-tab! tab-index nil))
  ([tab-index container-id]
   (net-client/send-to-server set-tab-msg-id
     (cond-> {:tab-index (int tab-index)}
       (some? container-id) (assoc :container-id (int container-id)))))
  ([owner tab-index container-id]
   (net-client/send-to-server owner set-tab-msg-id
     (cond-> {:tab-index (int tab-index)}
       (some? container-id) (assoc :container-id (int container-id))))))

(defn attach-tab-sync!
  "Attach generic tab-change sync between a TechUI instance and a container.

   pages: sequential collection of page maps (same sequence passed to create-tech-ui)
   tech-ui: map returned by create-tech-ui (expects :current atom)
   container: container map (may contain :tab-index atom)
   container-id: optional integer id from `gui/get-menu-container-id`"
  [pages tech-ui container container-id]
    (let [current-atom (:current tech-ui)
      owner (container-state/owner-from-container container)]
    (when (some? current-atom)
      (add-watch current-atom :tab-sync
        (fn [_ _ _ new-id]
          (when-let [idx (page-id->index pages new-id)]
            (when (tabbed-container? container)
              (reset! (:tab-index container) (int idx)))
            (when (and (integer? container-id)
                       (owner-client-session-id owner))
              (set-tab-index-by-container-id! owner container-id (int idx)))
            (send-set-tab-safe! owner idx container-id)))))

    ;; initial sync of current tab
    (when-let [cur (and current-atom @current-atom)]
      (when-let [idx (page-id->index pages cur)]
        (when (tabbed-container? container)
          (reset! (:tab-index container) (int idx)))
        (when (and (integer? container-id)
                   (owner-client-session-id owner))
          (set-tab-index-by-container-id! owner container-id (int idx)))
        (send-set-tab-safe! owner idx container-id)))))

