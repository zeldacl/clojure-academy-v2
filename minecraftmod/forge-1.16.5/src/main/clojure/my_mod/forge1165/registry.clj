(ns my-mod.forge1165.registry
  "Forge 1.16.5 registry implementations using DeferredRegister"
  (:require [my-mod.registry :as reg]
            [my-mod.util.log :as log]))

;; Note: DeferredRegister instances are created and managed in Java
;; This namespace provides the multimethod implementations

(defmethod reg/register-item :forge-1.16.5
  [item-id item-object]
  (log/info "Registering item" item-id "via Forge 1.16.5 DeferredRegister")
  ;; Actual registration happens in Java via DeferredRegister
  ;; This is called after Java has already registered
  item-object)

(defmethod reg/register-block :forge-1.16.5
  [block-id block-object]
  (log/info "Registering block" block-id "via Forge 1.16.5 DeferredRegister")
  ;; Actual registration happens in Java
  block-object)

(defmethod reg/get-registered-item :forge-1.16.5
  [item-id]
  (log/info "Getting registered item" item-id)
  ;; Lookup via ForgeRegistries in Java if needed
  nil)

(defmethod reg/get-registered-block :forge-1.16.5
  [block-id]
  (log/info "Getting registered block" block-id)
  nil)
