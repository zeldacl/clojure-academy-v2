(ns cn.li.forge1201.registry
  "Forge 1.20.1 registry implementations using DeferredRegister"
  (:require [cn.li.mcmod.registry.platform :as reg]
            [cn.li.mcmod.util.log :as log]))

;; DeferredRegister managed in Java for 1.20.1

(defmethod reg/register-item :forge-1.20.1
  [item-id item-object]
  (log/info "Registering item" item-id "via Forge 1.20.1 DeferredRegister")
  item-object)

(defmethod reg/register-block :forge-1.20.1
  [block-id block-object]
  (log/info "Registering block" block-id "via Forge 1.20.1 DeferredRegister")
  block-object)

(defmethod reg/get-registered-item :forge-1.20.1
  [item-id]
  (log/info "Getting registered item" item-id)
  nil)

(defmethod reg/get-registered-block :forge-1.20.1
  [block-id]
  (log/info "Getting registered block" block-id)
  nil)
