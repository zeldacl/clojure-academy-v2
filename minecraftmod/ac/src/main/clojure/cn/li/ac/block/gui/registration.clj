(ns cn.li.ac.block.gui.registration
  "Shared registration helpers for AC block GUIs.

  Block GUI files still own their screen/container behavior; this namespace owns
  the repeated mcmod GUI spec shape and slot operation grouping."
  (:require [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]))

(defn slot-operations
  "Build the standard slot operation map used by block containers."
  [{:keys [slot-count-fn slot-get-fn slot-set-fn slot-can-place-fn slot-changed-fn]}]
  {:slot-count-fn slot-count-fn
   :slot-get-fn slot-get-fn
   :slot-set-fn slot-set-fn
   :slot-can-place-fn slot-can-place-fn
   :slot-changed-fn slot-changed-fn})

(defn create-block-gui-spec
  "Create a standard block GUI spec.

  Required option groups are flattened intentionally so individual block GUI
  namespaces do not keep copying the same nested `:registration`, `:lifecycle`,
  `:sync`, `:operations`, and `:slot-operations` maps."
  [gui-name {:keys [gui-id
                    display-name gui-type registry-name screen-factory-fn-kw
                    slot-schema-id slot-layout
                    container-predicate container-fn screen-fn tick-fn
                    sync-get sync-apply payload-sync-apply-fn
                    validate-fn close-fn button-click-fn]
             :as opts}]
  (let [layout (or slot-layout
                   (when slot-schema-id
                     (slot-schema/get-slot-layout slot-schema-id)))
        sync (cond-> {:sync-get sync-get
                      :sync-apply sync-apply}
               payload-sync-apply-fn
               (assoc :payload-sync-apply-fn payload-sync-apply-fn))]
    (gui-dsl/create-gui-spec
      gui-name
      {:gui-id gui-id
       :registration {:display-name display-name
                      :gui-type gui-type
                      :registry-name registry-name
                      :screen-factory-fn-kw screen-factory-fn-kw
                      :slot-layout layout}
       :lifecycle {:container-predicate container-predicate
                   :container-fn container-fn
                   :screen-fn screen-fn
                   :tick-fn tick-fn}
      :sync sync
       :operations {:validate-fn validate-fn
                    :close-fn close-fn
                    :button-click-fn button-click-fn}
       :slot-operations (slot-operations opts)})))

(defn register-block-gui!
  "Register a standard AC block GUI spec and return the registered spec."
  [gui-name opts]
  (gui-dsl/register-gui!
    (create-block-gui-spec gui-name opts)))
