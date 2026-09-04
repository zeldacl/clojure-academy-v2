(ns cn.li.ac.terminal.client.install-effect-reactive
  "Terminal installation notification on the shared Presentation Runtime."
  (:require [cn.li.ac.gui.presentation-application :as application]
            [cn.li.ac.terminal.client.actions :as terminal-actions]
            [cn.li.ac.terminal.messages :as terminal-messages]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log]))

(def ^:private installation-ms 4700)
(def ^:private tick-ms 100)

(defn- current-terminal-key-name []
  (or (bridge/call-adapter :keybind-get-key-name :content/toggle-terminal)
      "Left Alt"))

(defn- finish! [player]
  (bridge/close-screen!)
  (terminal-actions/open-terminal! player)
  (when-let [client-player (bridge/get-client-player)]
    (bridge/send-system-message!
      client-player
      (str "terminal." (or (bridge/call-adapter :mod-id) "academy") ".key_hint")
      (current-terminal-key-name))))

(defn show! [player]
  (let [done? (atom false)
        owner (str "application/install/" (or (bridge/call-adapter :client-session-id)
                                               "local"))
        vm (application/mount!
             owner
             "Installing..."
             {:progress 0.0
              :tag "INST"
              :status (or (i18n/translate "gui.academycraft.terminal.installing")
                          "Installing terminal...")
              :lines [{:label (or (i18n/translate "gui.academycraft.terminal.installing")
                                  "Installing terminal...")}]}
             (fn [_action _state] nil)
             #(reset! done? true)
             :hud
             :academy.app/install-effect)
        refresh! (:refresh! vm)
        steps (max 1 (long (/ installation-ms tick-ms)))]
    (future
      (doseq [i (range steps)
              :while (not @done?)]
        (Thread/sleep (long tick-ms))
        (when refresh!
          (refresh! {:progress (min 1.0 (/ (double (inc i)) (double steps)))})))
      (when (compare-and-set! done? false true)
        (application/unmount! vm)
        (finish! player)))
    vm))

(defn open!
  "Surface-manifest entry; same as show!."
  ([] (show! (bridge/get-client-player)))
  ([player] (show! player)))

(defn install-push-handler! []
  (install/framework-once! ::install-effect-reactive-push-handler-installed?
    (fn []
      (net-client/register-push-handler!
        (terminal-messages/msg-id :terminal-install-effect)
        (fn [_payload]
          (when-let [player (bridge/get-client-player)]
            (show! player))))
      (log/info "AC terminal install-effect Presentation handler installed")))
  nil)
