(ns cn.li.mcmod.platform.player-feedback
  "Player feedback (chat messages, action bar, sounds) via Framework function map.

   Impl stored at [:platform :player-feedback]."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

(def player-feedback-keys
  #{:send-player-feedback!})

(defn install-player-feedback!
  "Install player feedback implementation as a function map."
  ([impl]
   (when-let [fw-atom (fw/fw-atom)]
     (swap! fw-atom assoc-in [:platform :player-feedback] impl))
   nil)
  ([impl label]
   (log/info (or label "player-feedback") "installed")
   (install-player-feedback! impl)))

(defn available? []
  (boolean (get-in @(fw/fw-atom) [:platform :player-feedback])))

(defn current []
  (get-in @(fw/fw-atom) [:platform :player-feedback]))

;; ============================================================================
;; Operations — read impl from Framework atom (no ThreadLocal dependency)
;; ============================================================================

(defn send-feedback!*
  "Send feedback to a player. Returns false if adapter not installed."
  [player-uuid payload]
  (if-let [f (get-in @(fw/fw-atom) [:platform :player-feedback :send-player-feedback!])]
    (boolean (f player-uuid payload))
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
