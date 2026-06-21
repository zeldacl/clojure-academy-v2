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
       :misaka-id       (tut-player/get-misaka-id (session-id) uuid-str)
       :first-open?     (:first-open? tut-state)})
    (catch Exception e
      (log/error "Error handling tutorial request-sync:" (ex-message e))
      {:error (ex-message e)})))

(defn handle-mark-first-open-done
  "Client reports that the first-open animation has played.
  Persists the flag on the server side so subsequent opens skip the animation."
  [_payload player]
  (try
    (let [uuid-str (uuid/player-uuid player)]
      (tut-player/mark-first-open-done! (session-id) uuid-str)
      {:ok true})
    (catch Exception e
      (log/error "Error handling mark-first-open-done:" (ex-message e))
      {:error (ex-message e)})))

;; --- Registration ---

(defn register-handlers!
  []
  (net-server/register-handler (tut-msg/msg-id :tutorial/request-sync) handle-request-sync
                               {:owner-spec :server :payload-routing :none})
  (net-server/register-handler (tut-msg/msg-id :tutorial/mark-first-open-done) handle-mark-first-open-done
                               {:owner-spec :server :payload-routing :none})
  (log/info "Tutorial network handlers registered"))
