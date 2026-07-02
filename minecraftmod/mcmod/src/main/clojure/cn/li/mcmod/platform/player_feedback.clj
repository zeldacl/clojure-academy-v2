(ns cn.li.mcmod.platform.player-feedback
  "Platform-neutral player feedback bridge for runtime gameplay messages.

  Protocol kept as pure interface contract (AOT-safe).
  Implementation stored in Framework [:platform :player-feedback]
  instead of ^:dynamic *runtime* (eliminates ThreadLocal risk)."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Protocol (pure interface — AOT-safe, kept for contract definition)
;; ============================================================================

(defprotocol IPlayerFeedback
  (send-player-feedback! [this player-uuid payload]))

;; ============================================================================
;; Installation — writes to Framework [:platform :player-feedback]
;; ============================================================================

(defn install-player-feedback!
  "Install player feedback implementation. Backward compatible with
   old (impl label) signature; stores in Framework atom."
  ([impl]
   (when-let [fw-atom fw/*framework*]
     (swap! fw-atom assoc-in [:platform :player-feedback] impl))
   nil)
  ([impl label]
   (log/info (or label "player-feedback") "installed")
   (install-player-feedback! impl)))

;; ============================================================================
;; Queries
;; ============================================================================

(defn available? []
  (boolean (get-in @fw/*framework* [:platform :player-feedback])))

(defn current []
  (get-in @fw/*framework* [:platform :player-feedback]))

;; ============================================================================
;; Operations — read impl from Framework atom (no ThreadLocal dependency)
;; ============================================================================

(defn send-feedback!*
  "Send feedback to a player. Returns false if adapter not installed."
  [player-uuid payload]
  (if-let [rt (get-in @fw/*framework* [:platform :player-feedback])]
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
