(ns cn.li.ac.block.wireless-node.owner
  "Shared owner-name resolution and authorization helpers for wireless node."
  (:require [clojure.string :as str]
            [cn.li.mcmod.platform.entity :as entity]))

(defn owner-name
  "Normalize node owner from tile custom state.

  Returns an empty string when owner cannot be resolved."
  [tile-state]
  (str (get (or tile-state {}) :placer-name "")))

(defn player-name
  "Normalize player name for authorization checks.

  Returns an empty string on any lookup failure."
  [player]
  (try
    (str (or (entity/player-get-name player) ""))
    (catch Exception _
      "")))

(defn owner-authorized?
  "True when player is allowed to edit node owner-protected fields.

  Backward compatibility: accept older owner serialization that embeds the
  player's name as `...'<name>'...`."
  [owner player]
  (let [owner (str owner)
        player-name (player-name player)]
    (or (str/blank? owner)
        (= owner player-name)
        (and (not (str/blank? player-name))
             (str/includes? owner (str "'" player-name "'"))))))
