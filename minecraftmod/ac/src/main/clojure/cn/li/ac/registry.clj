(ns cn.li.ac.registry
  "Backwards-compatible shim for the old registry API.

   The actual platform registry multimethods now live in
   `cn.li.mcmod.registry.platform` and dispatch via
   `cn.li.mcmod.platform.dispatch/*platform-version*`.")

;; Deprecated: kept only so older platform init code can still compile.
;; It is no longer used for dispatch.
(def ^:dynamic *forge-version* nil)

(require '[cn.li.mcmod.registry.platform :as platform-reg])

;; Re-export multimethod vars
(def register-item platform-reg/register-item)
(def register-block platform-reg/register-block)
(def get-registered-item platform-reg/get-registered-item)
(def get-registered-block platform-reg/get-registered-block)
