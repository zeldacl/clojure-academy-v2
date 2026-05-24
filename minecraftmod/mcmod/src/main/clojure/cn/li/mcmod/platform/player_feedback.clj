(ns cn.li.mcmod.platform.player-feedback
  "Platform-neutral player feedback bridge for runtime gameplay messages."
  (:require [cn.li.mcmod.util.log :as log]))

(defprotocol IPlayerFeedback
  (send-player-feedback! [this player-uuid payload]
    "Send runtime feedback to a player.

    payload schema:
      {:mode :chat
       :message string|keyword
       :args [..]
       :translate? boolean}

    Returns true when the message was delivered."))

(def ^:dynamic *player-feedback*
  "Bound by the mc1201/platform runtime to an IPlayerFeedback implementation."
  nil)

(defn send-feedback!
  [player-uuid payload]
  (if *player-feedback*
    (boolean (send-player-feedback! *player-feedback* player-uuid payload))
    (do
      (log/debug "Player feedback unavailable; dropping payload" payload)
      false)))

(defn send-chat-message!
  ([player-uuid message]
   (send-chat-message! player-uuid message [] true))
  ([player-uuid message args]
   (send-chat-message! player-uuid message args true))
  ([player-uuid message args translate?]
   (send-feedback! player-uuid {:mode :chat
                                :message message
                                :args (vec (or args []))
                                :translate? (boolean translate?)})))