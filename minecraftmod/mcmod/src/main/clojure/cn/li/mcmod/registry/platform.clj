;; Platform registry multimethods.
;; Loader adapters implement the actual registration and lookup strategy.
(ns cn.li.mcmod.registry.platform
  "Abstract registry API using multimethods for version-specific dispatch")

(require '[cn.li.mcmod.platform.dispatch :as platform-dispatch])

;; Multimethods for version-specific registration
(defmulti register-item
  "Register an item with version-specific registry system"
  (fn [_item-id _item-object] platform-dispatch/*platform-version*))

(defmulti register-block
  "Register a block with version-specific registry system"
  (fn [_block-id _block-object] platform-dispatch/*platform-version*))

(defmulti get-registered-item
  "Get a registered item by ID"
  (fn [_item-id] platform-dispatch/*platform-version*))

(defmulti get-registered-block
  "Get a registered block by ID"
  (fn [_block-id] platform-dispatch/*platform-version*))

;; Default implementations throw errors
(defmethod register-item :default [item-id _]
  (throw (ex-info "No registry implementation for platform"
                  {:platform platform-dispatch/*platform-version*
                   :item-id item-id})))

(defmethod register-block :default [block-id _]
  (throw (ex-info "No registry implementation for platform"
                  {:platform platform-dispatch/*platform-version*
                   :block-id block-id})))

(defmethod get-registered-item :default [_item-id]
  (throw (ex-info "No registry implementation for platform"
                  {:platform platform-dispatch/*platform-version*
                   :item-id _item-id})))

(defmethod get-registered-block :default [_block-id]
  (throw (ex-info "No registry implementation for platform"
                  {:platform platform-dispatch/*platform-version*
                   :block-id _block-id})))

