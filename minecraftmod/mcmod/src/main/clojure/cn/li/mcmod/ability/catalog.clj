(ns cn.li.mcmod.ability.catalog
  "Compatibility shim for legacy ability catalog.

  Canonical runtime message IDs now live in cn.li.mcmod.runtime.catalog.
  Keep this namespace for backwards compatibility with existing AC code."
  (:require [cn.li.mcmod.runtime.catalog :as runtime-catalog]))

;; ============================================================================
;; Context Session Messages (client ↔ server handshake)
;; ============================================================================

(def MSG-CTX-BEGIN-LINK   runtime-catalog/MSG-CTX-BEGIN-LINK)
(def MSG-CTX-ESTABLISH    runtime-catalog/MSG-CTX-ESTABLISH)
(def MSG-CTX-KEEPALIVE    runtime-catalog/MSG-CTX-KEEPALIVE)
(def MSG-CTX-TERMINATE    runtime-catalog/MSG-CTX-TERMINATE)
(def MSG-CTX-TERMINATED   runtime-catalog/MSG-CTX-TERMINATED)
(def MSG-CTX-CHANNEL      runtime-catalog/MSG-CTX-CHANNEL)

;; ============================================================================
;; Skill Execution Messages (routed through context channel)
;; ============================================================================

(def MSG-SKILL-KEY-DOWN   runtime-catalog/MSG-SLOT-KEY-DOWN)
(def MSG-SKILL-KEY-TICK   runtime-catalog/MSG-SLOT-KEY-TICK)
(def MSG-SKILL-KEY-UP     runtime-catalog/MSG-SLOT-KEY-UP)
(def MSG-SKILL-KEY-ABORT  runtime-catalog/MSG-SLOT-KEY-ABORT)

;; ============================================================================
;; Player State Sync (server → client)
;; ============================================================================

(def MSG-SYNC-ABILITY     runtime-catalog/MSG-SYNC-RUNTIME)
(def MSG-SYNC-RESOURCE    runtime-catalog/MSG-SYNC-RESOURCE)
(def MSG-SYNC-COOLDOWN    runtime-catalog/MSG-SYNC-COOLDOWN)
(def MSG-SYNC-PRESET      runtime-catalog/MSG-SYNC-PRESET)

;; ============================================================================
;; Player Action Requests (client → server)
;; ============================================================================

(def MSG-REQ-LEARN-SKILL   runtime-catalog/MSG-REQ-LEARN-NODE)
(def MSG-REQ-LEVEL-UP      runtime-catalog/MSG-REQ-LEVEL-UP)
(def MSG-REQ-SET-PRESET    runtime-catalog/MSG-REQ-SET-PRESET)
(def MSG-REQ-SWITCH-PRESET runtime-catalog/MSG-REQ-SWITCH-PRESET)
(def MSG-REQ-SET-ACTIVATED runtime-catalog/MSG-REQ-SET-ACTIVATED)
(def MSG-REQ-LOCATION-TELEPORT-QUERY   runtime-catalog/MSG-REQ-SAVED-POS-QUERY)
(def MSG-REQ-LOCATION-TELEPORT-ADD     runtime-catalog/MSG-REQ-SAVED-POS-ADD)
(def MSG-REQ-LOCATION-TELEPORT-REMOVE  runtime-catalog/MSG-REQ-SAVED-POS-REMOVE)
(def MSG-REQ-LOCATION-TELEPORT-PERFORM runtime-catalog/MSG-REQ-SAVED-POS-PERFORM)

;; ============================================================================
;; Validation utility
;; ============================================================================

(def all-messages
  "All registered message IDs."
  runtime-catalog/all-messages)

(defn valid-msg-id? [id]
  (runtime-catalog/valid-msg-id? id))
