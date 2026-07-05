(ns cn.li.ac.item.terminal-installer-handler
  "Server-driven terminal installer right-click handler matching original
   ItemTerminalInstaller.onItemRightClick (AcademyCraft Forge 1.12)."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.terminal.player :as terminal-player]
            [cn.li.ac.achievement.dispatcher :as achievement-dispatcher]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.player-feedback :as player-feedback]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.server.platform-bridge :as server-bridge]))

(defn handle-right-click
  "Server-side handler for terminal_installer right-click.
   Returns {:consume? true/false} matching original behavior:
   - Already installed → message, don't consume
   - Not installed → install + achievement + push effect, consume unless creative"
  [player]
    (let [uuid-str (uuid/player-uuid player)
        installed? (boolean (terminal-player/terminal-installed? player))]
    (if installed?
      ;; Already installed → send "alrdy_installed" chat message, item NOT consumed
      (do
        (player-feedback/send-chat-message! uuid-str "terminal.my_mod.alrdy_installed" [] true)
        {:consume? false})
      ;; Not installed → install, trigger achievement, push effect to client
      (do
        (terminal-player/install-terminal! player)
          (try
            (achievement-dispatcher/trigger-custom-event! uuid-str "terminal_installed")
            (catch Throwable _ nil))
          (try
            (server-bridge/send-to-client! uuid-str 1004 {})
            (catch Throwable _ nil))
        ;; Consume item unless creative mode
        ;; (matching original: if(!player.capabilities.isCreativeMode) stack.setCount(...))
        {:consume? (not (boolean (entity/player-creative? player)))}))))
