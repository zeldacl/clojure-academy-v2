(ns cn.li.mcmod.ability.catalog
  "All network message IDs used by the ability system are defined here.
  Use these constants everywhere; never hard-code message-ID strings.

  Convention:  ability:<domain>/<action>

  This namespace belongs to mcmod (shared layer) so both ac and forge can
  reference it without creating cross-layer imports."
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Context Session Messages (client ↔ server handshake)
;; ============================================================================

(def MSG-CTX-BEGIN-LINK   "ability:ctx/begin-link")
(def MSG-CTX-ESTABLISH    "ability:ctx/establish")
(def MSG-CTX-KEEPALIVE    "ability:ctx/keepalive")
(def MSG-CTX-TERMINATE    "ability:ctx/terminate")
(def MSG-CTX-TERMINATED   "ability:ctx/terminated")

;; ============================================================================
;; Skill Execution Messages (routed through context channel)
;; ============================================================================

(def MSG-SKILL-KEY-DOWN   "ability:skill/key-down")
(def MSG-SKILL-KEY-TICK   "ability:skill/key-tick")
(def MSG-SKILL-KEY-UP     "ability:skill/key-up")
(def MSG-SKILL-KEY-ABORT  "ability:skill/key-abort")

;; ============================================================================
;; Player State Sync (server → client)
;; ============================================================================

(def MSG-SYNC-ABILITY     "ability:sync/ability-data")
(def MSG-SYNC-RESOURCE    "ability:sync/resource-data")
(def MSG-SYNC-COOLDOWN    "ability:sync/cooldown-data")
(def MSG-SYNC-PRESET      "ability:sync/preset-data")

;; ============================================================================
;; Player Action Requests (client → server)
;; ============================================================================

(def MSG-REQ-LEARN-SKILL   "ability:req/learn-skill")
(def MSG-REQ-LEVEL-UP      "ability:req/level-up")
(def MSG-REQ-SET-PRESET    "ability:req/set-preset")
(def MSG-REQ-SWITCH-PRESET "ability:req/switch-preset")
(def MSG-REQ-SET-ACTIVATED "ability:req/set-activated")

;; ============================================================================
;; Validation utility
;; ============================================================================

(def all-messages
  "All registered message IDs."
  #{MSG-CTX-BEGIN-LINK MSG-CTX-ESTABLISH MSG-CTX-KEEPALIVE MSG-CTX-TERMINATE MSG-CTX-TERMINATED
    MSG-SKILL-KEY-DOWN MSG-SKILL-KEY-TICK MSG-SKILL-KEY-UP MSG-SKILL-KEY-ABORT
    MSG-SYNC-ABILITY MSG-SYNC-RESOURCE MSG-SYNC-COOLDOWN MSG-SYNC-PRESET
    MSG-REQ-LEARN-SKILL MSG-REQ-LEVEL-UP MSG-REQ-SET-PRESET MSG-REQ-SWITCH-PRESET
    MSG-REQ-SET-ACTIVATED})

(defn valid-msg-id? [id]
  (contains? all-messages id))
