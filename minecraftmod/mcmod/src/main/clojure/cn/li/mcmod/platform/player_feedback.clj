(ns cn.li.mcmod.platform.player-feedback
  "Platform-neutral player feedback bridge for runtime gameplay messages."
  (:require [cn.li.mcmod.platform.runtime :as prt]
            [cn.li.mcmod.util.log :as log]))

(defprotocol IPlayerFeedback
  (send-player-feedback! [this player-uuid payload]))

(def ^:private ^:dynamic *runtime* nil)

(defn install-player-feedback!
  [impl label]
  (prt/install-impl! #'*runtime* impl (or label "player-feedback")))

(defn available? [] (prt/impl-available? #'*runtime*))
(defn current [] (prt/impl-current #'*runtime*))
(defn call-with-runtime [rt f] (binding [*runtime* rt] (f)))

(defn send-feedback!*
  [player-uuid payload]
  (if-let [rt *runtime*]
    (boolean (send-player-feedback! rt player-uuid payload))
    (do
      (log/debug "Player feedback unavailable; dropping payload" payload)
      false)))

(defn send-feedback!
  [player-uuid payload]
  (send-feedback!* player-uuid payload))

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
