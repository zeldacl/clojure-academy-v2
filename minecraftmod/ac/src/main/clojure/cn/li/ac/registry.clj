(ns cn.li.ac.registry
  "Abstract registry API using multimethods for version-specific dispatch")

;; Version detection dynamic var - set by each Forge adapter
(def ^:dynamic *forge-version* nil)

;; Multimethods for version-specific registration
(defmulti register-item
  "Register an item with version-specific registry system"
  (fn [_item-id _item-object] *forge-version*))

(defmulti register-block
  "Register a block with version-specific registry system"
  (fn [_block-id _block-object] *forge-version*))

(defmulti get-registered-item
  "Get a registered item by ID"
  (fn [_item-id] *forge-version*))

(defmulti get-registered-block
  "Get a registered block by ID"
  (fn [_block-id] *forge-version*))

;; Default implementations throw errors
(defmethod register-item :default [item-id _]
  (throw (ex-info "No registry implementation for version"
                  {:version *forge-version* :item-id item-id})))

(defmethod register-block :default [block-id _]
  (throw (ex-info "No registry implementation for version"
                  {:version *forge-version* :block-id block-id})))
