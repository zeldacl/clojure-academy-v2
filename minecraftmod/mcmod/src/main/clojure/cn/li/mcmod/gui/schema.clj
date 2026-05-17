(ns cn.li.mcmod.gui.schema
  "GUI schema records.")

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

(defrecord Layout
  [title width height slots buttons labels background])

(defrecord GuiSpec
  [id gui-id
  registration lifecycle sync operations slot-operations layout])
