(ns cn.li.ac.terminal.network
  "Server-side terminal network handlers."
  (:require [cn.li.ac.terminal.messages :as terminal-messages]
            [cn.li.ac.terminal.model :as model]
            [cn.li.ac.terminal.player :as player]
            [cn.li.ac.terminal.catalog :as catalog]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log]))

(defn- session-id
  []
  (runtime-hooks/require-player-state-session-id "terminal.network"))

(defn handle-install-terminal
  [_payload player]
  (let [sid (session-id)
        uid (uuid/player-uuid player)]
    (if (player/terminal-installed? sid uid)
      {:success false :error :already-installed}
      (try
        (player/install-terminal! sid uid)
        ;; Trigger terminal_installed achievement (matches original ACAdvancements.trigger)
        (try
          (when-let [trigger-fn (requiring-resolve 'cn.li.ac.achievement.dispatcher/trigger-custom-event!)]
            (trigger-fn uid "terminal_installed"))
          (catch Throwable _ nil))
        ;; Push install effect to client (matches original NetworkMessage.sendTo("install"))
        (try
          (when-let [send-push (requiring-resolve 'cn.li.mc1201.runtime.network-core/send-to-client!)]
            (send-push uid (terminal-messages/msg-id :terminal-install-effect) {}))
          (catch Throwable _ nil))
        {:success true}
        (catch Exception e
          (log/error "Error installing terminal:" (ex-message e))
          {:success false :error (ex-message e)})))))

(defn handle-install-app
  [payload player]
  (try
    (let [uuid-str (uuid/player-uuid player)
          app-id (keyword (:app-id payload))]
      (if (catalog/app-exists? app-id)
        (do
          (player/install-app! (session-id) uuid-str app-id)
          {:success true :app-id app-id})
        {:success false :error "App not found"}))
    (catch Exception e
      (log/error "Error installing app:" (ex-message e))
      {:success false :error (ex-message e)})))

(defn handle-uninstall-app
  [payload player]
  (try
    (let [uuid-str (uuid/player-uuid player)
          app-id (keyword (:app-id payload))]
      (player/uninstall-app! (session-id) uuid-str app-id)
      {:success true :app-id app-id})
    (catch Exception e
      (log/error "Error uninstalling app:" (ex-message e))
      {:success false :error (ex-message e)})))

(defn handle-get-state
  [_payload player]
  (try
    (let [uuid-str (uuid/player-uuid player)
          terminal-data (player/state (session-id) uuid-str)]
      {:terminal-installed? (:terminal-installed? terminal-data)
       :installed-apps (vec (:installed-apps (model/normalize-state terminal-data)))
       :available-apps (catalog/app-ids)
       :app-count (catalog/app-count)})
    (catch Exception e
      (log/error "Error getting terminal state:" (ex-message e))
      {:error (ex-message e)})))

(defn register-handlers!
  []
  (net-server/register-handler (terminal-messages/msg-id :install-terminal) handle-install-terminal)
  (net-server/register-handler (terminal-messages/msg-id :install-app) handle-install-app)
  (net-server/register-handler (terminal-messages/msg-id :uninstall-app) handle-uninstall-app)
  (net-server/register-handler (terminal-messages/msg-id :get-state) handle-get-state)
  (log/info "Terminal network handlers registered"))
