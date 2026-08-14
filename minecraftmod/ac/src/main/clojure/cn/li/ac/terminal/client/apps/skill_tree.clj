(ns cn.li.ac.terminal.client.apps.skill-tree
  "CLIENT-ONLY: skill tree terminal app launcher."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.ability.client.screens.skill-tree :as skill-tree]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

(defn open!
  ([player] (open! player nil))
  ([player learn-context]
   (log/info "Opening skill tree from terminal for player:" (entity/player-get-name player))
   (skill-tree/open-presentation! (uuid/player-uuid player) learn-context)))
