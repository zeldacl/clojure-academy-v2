(ns cn.li.ac.terminal.network
  "Network message handlers for terminal operations.

  Handles server-side logic for:
  - Terminal installation
  - App installation/uninstallation
  - Terminal state queries"
  (:require [cn.li.ac.terminal.player-data :as term-data]
            [cn.li.ac.terminal.app-registry :as app-reg]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Message IDs
;; ============================================================================

(def msg-ids
  {:install-terminal 1000
   :install-app 1001
   :uninstall-app 1002
   :get-state 1003})

(defn msg-id [action]
  (get msg-ids action))

;; ============================================================================
;; Message Handlers
;; ============================================================================

(defn handle-install-terminal
  "Handle terminal installation request."
  [_payload player]
  (try
    (let [uuid-str (str (.getUUID player))]
      (term-data/install-terminal! uuid-str)
      {:success true})
    (catch Exception e
      (log/error "Error installing terminal:" (ex-message e))
      {:success false :error (ex-message e)})))

(defn handle-install-app
  "Handle app installation request."
  [payload player]
  (try
    (let [uuid-str (str (.getUUID player))
          app-id (keyword (:app-id payload))]
      (if (app-reg/get-app app-id)
        (do
          (term-data/install-app! uuid-str app-id)
          {:success true :app-id app-id})
        {:success false :error "App not found"}))
    (catch Exception e
      (log/error "Error installing app:" (ex-message e))
      {:success false :error (ex-message e)})))

(defn handle-uninstall-app
  "Handle app uninstallation request."
  [payload player]
  (try
    (let [uuid-str (str (.getUUID player))
          app-id (keyword (:app-id payload))]
      (term-data/uninstall-app! uuid-str app-id)
      {:success true :app-id app-id})
    (catch Exception e
      (log/error "Error uninstalling app:" (ex-message e))
      {:success false :error (ex-message e)})))

(defn handle-get-state
  "Handle terminal state query."
  [_payload player]
  (try
    (let [uuid-str (str (.getUUID player))
          terminal-data (term-data/get-terminal-data uuid-str)
          available-apps (app-reg/list-available-apps player)]
      {:terminal-installed? (:terminal-installed? terminal-data)
       :installed-apps (vec (:installed-apps terminal-data))
       :available-apps (mapv :id available-apps)
       :app-count (app-reg/app-count)})
    (catch Exception e
      (log/error "Error getting terminal state:" (ex-message e))
      {:error (ex-message e)})))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-handlers!
  "Register all terminal network handlers."
  []
  (net-server/register-handler (msg-id :install-terminal) handle-install-terminal)
  (net-server/register-handler (msg-id :install-app) handle-install-app)
  (net-server/register-handler (msg-id :uninstall-app) handle-uninstall-app)
  (net-server/register-handler (msg-id :get-state) handle-get-state)
  (log/info "Terminal network handlers registered"))
