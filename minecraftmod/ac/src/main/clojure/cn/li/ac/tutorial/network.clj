(ns cn.li.ac.tutorial.network
  "Server-side tutorial network handlers.
  Follows terminal/network.clj pattern."
  (:require [cn.li.ac.tutorial.messages :as tut-msg]
            [cn.li.ac.tutorial.player :as tut-player]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log]))

(defn- session-id
  []
  (runtime-hooks/require-player-state-session-id "tutorial.network"))

;; --- Handlers ---

(defn handle-request-sync
  "Client requests current tutorial state.  Returns full state snapshot."
  [_payload player]
  (try
    (let [uuid-str (uuid/player-uuid player)
          tut-state (tut-player/state (session-id) uuid-str)]
      (tut-player/ensure-state! (session-id) uuid-str)
      {:activated-tuts  (vec (:activated-tuts tut-state))
       :misaka-id       (:misaka-id tut-state)
       :first-open?     (:first-open? tut-state)})
    (catch Exception e
      (log/error "Error handling tutorial request-sync:" (ex-message e))
      {:error (ex-message e)})))

;; --- Registration ---

(defn register-handlers!
  []
  (net-server/register-handler (tut-msg/msg-id :tutorial/request-sync) handle-request-sync)
  (log/info "Tutorial network handlers registered"))
