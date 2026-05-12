(ns cn.li.mcmod.gui.schema
  "GUI schema records and shared field access helpers.")

(defrecord SlotSpec [index x y filter on-change])
(defrecord ButtonSpec [id x y width height text on-click])
(defrecord LabelSpec [x y text color])

(defrecord RegistrationConfig
  [display-name gui-type registry-name screen-factory-fn-kw slot-layout])

(defrecord LifecycleHandlers
  [container-fn container-predicate screen-fn tick-fn])

(defrecord SyncConfig
  [sync-get sync-apply payload-sync-apply-fn])

(defrecord OperationHandlers
  [validate-fn close-fn button-click-fn text-input-fn])

(defrecord SlotOperations
  [slot-count-fn slot-get-fn slot-set-fn slot-can-place-fn slot-changed-fn])

(defrecord LegacyLayout
  [title width height slots buttons labels background])

(defrecord GuiSpec
  [id gui-id
   registration lifecycle sync operations slots legacy-layout
   ;; Flattened aliases kept for compatibility.
   display-name gui-type registry-name screen-factory-fn-kw slot-layout
   container-fn container-predicate screen-fn tick-fn
   sync-get sync-apply payload-sync-apply-fn
   validate-fn close-fn button-click-fn text-input-fn
   slot-count-fn slot-get-fn slot-set-fn slot-can-place-fn slot-changed-fn])

(defn cfg-value
  "Read config from nested group first, then legacy top-level key."
  [spec nested-path legacy-key]
  (or (get-in spec nested-path)
      (get spec legacy-key)))
