(ns cn.li.ac.item.terminal-installer-handler
  "Server-driven terminal installer right-click handler matching original
   ItemTerminalInstaller.onItemRightClick (AcademyCraft Forge 1.12)."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

(defn handle-right-click
  "Server-side handler for terminal_installer right-click.
   Returns {:consume? true/false} matching original behavior:
   - Already installed → message, don't consume
   - Not installed → install + achievement + push effect, consume unless creative
   Uses requiring-resolve for cross-layer dynamic dispatch."
  [player]
    (let [uuid-str (uuid/player-uuid player)
        installed? (boolean
                    (when-let [chk (requiring-resolve 'cn.li.ac.terminal.player/terminal-installed?)]
                      (chk player)))]
    (if installed?
      ;; Already installed → send "alrdy_installed" chat message, item NOT consumed
      (do
        (when-let [send-msg (requiring-resolve 'cn.li.mcmod.platform.player-feedback/send-chat-message!)]
          (send-msg uuid-str "terminal.my_mod.alrdy_installed" [] true))
        {:consume? false})
      ;; Not installed → install, trigger achievement, push effect to client
      (do
        (when-let [install! (requiring-resolve 'cn.li.ac.terminal.player/install-terminal!)]
          (install! player))
          (try
            (when-let [trigger (requiring-resolve 'cn.li.ac.achievement.dispatcher/trigger-custom-event!)]
              (trigger uuid-str "terminal_installed"))
            (catch Throwable _ nil))
          (try
            (when-let [send-push (requiring-resolve 'cn.li.mc1201.runtime.network-core/send-to-client!)]
              (send-push uuid-str 1004 {}))
            (catch Throwable _ nil))
        ;; Consume item unless creative mode
        ;; (matching original: if(!player.capabilities.isCreativeMode) stack.setCount(...))
        {:consume? (not (boolean (entity/player-creative? player)))}))))
