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
                                               "local"))]
    (application/mount!
      owner
      "Installing..."
      {:lines [{:label (or (i18n/translate "gui.academycraft.terminal.installing")
                           "Installing terminal...")}]
       :status "Please wait..."
       :scroll 0.0}
      (fn [_action _state] nil)
      #(reset! done? true))
    ;; The installation notification is a finite presentation; gameplay
    ;; remains Clojure-owned and the old XML runtime is not involved.
    (future
      (Thread/sleep (long installation-ms))
      (when (compare-and-set! done? false true)
        (finish! player)))
    nil))

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
