(ns cn.li.mcmod.runtime.catalog
	"Shared runtime network message catalog.

	Message ID strings intentionally keep existing wire values for
	protocol compatibility across old/new clients and servers.")

;; Context session messages
(def MSG-CTX-BEGIN-LINK   "ability:ctx/begin-link")
(def MSG-CTX-ESTABLISH    "ability:ctx/establish")
(def MSG-CTX-KEEPALIVE    "ability:ctx/keepalive")
(def MSG-CTX-TERMINATE    "ability:ctx/terminate")
(def MSG-CTX-TERMINATED   "ability:ctx/terminated")
(def MSG-CTX-CHANNEL      "ability:ctx/channel")

;; Runtime key execution messages
(def MSG-SLOT-KEY-DOWN    "ability:skill/key-down")
(def MSG-SLOT-KEY-TICK    "ability:skill/key-tick")
(def MSG-SLOT-KEY-UP      "ability:skill/key-up")
(def MSG-SLOT-KEY-ABORT   "ability:skill/key-abort")

;; Player state sync
(def MSG-SYNC-RUNTIME     "ability:sync/ability-data")
(def MSG-SYNC-RESOURCE    "ability:sync/resource-data")
(def MSG-SYNC-COOLDOWN    "ability:sync/cooldown-data")
(def MSG-SYNC-PRESET      "ability:sync/preset-data")

;; Runtime action requests
(def MSG-REQ-LEARN-NODE           "ability:req/learn-skill")
(def MSG-REQ-LEVEL-UP             "ability:req/level-up")
(def MSG-REQ-SET-PRESET           "ability:req/set-preset")
(def MSG-REQ-SWITCH-PRESET        "ability:req/switch-preset")
(def MSG-REQ-SET-ACTIVATED        "ability:req/set-activated")
(def MSG-REQ-SAVED-POS-QUERY      "ability:req/location-teleport/query")
(def MSG-REQ-SAVED-POS-ADD        "ability:req/location-teleport/add")
(def MSG-REQ-SAVED-POS-REMOVE     "ability:req/location-teleport/remove")
(def MSG-REQ-SAVED-POS-PERFORM    "ability:req/location-teleport/perform")

(def all-messages
	#{MSG-CTX-BEGIN-LINK MSG-CTX-ESTABLISH MSG-CTX-KEEPALIVE MSG-CTX-TERMINATE MSG-CTX-TERMINATED
		MSG-CTX-CHANNEL
		MSG-SLOT-KEY-DOWN MSG-SLOT-KEY-TICK MSG-SLOT-KEY-UP MSG-SLOT-KEY-ABORT
		MSG-SYNC-RUNTIME MSG-SYNC-RESOURCE MSG-SYNC-COOLDOWN MSG-SYNC-PRESET
		MSG-REQ-LEARN-NODE MSG-REQ-LEVEL-UP MSG-REQ-SET-PRESET MSG-REQ-SWITCH-PRESET
		MSG-REQ-SET-ACTIVATED
		MSG-REQ-SAVED-POS-QUERY MSG-REQ-SAVED-POS-ADD
		MSG-REQ-SAVED-POS-REMOVE MSG-REQ-SAVED-POS-PERFORM})

(defn valid-msg-id?
	[id]
	(contains? all-messages id))