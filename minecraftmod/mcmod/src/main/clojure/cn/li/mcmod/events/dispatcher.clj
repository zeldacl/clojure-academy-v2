(ns cn.li.mcmod.events.dispatcher
  "Platform-agnostic block event dispatchers.

   Forge/Fabric adapters should forward Forge events into these handlers.
   Actual per-block behavior is looked up via `cn.li.mcmod.events.metadata`.")

(require '[cn.li.mcmod.util.log :as log]
         '[cn.li.mcmod.events.metadata :as event-metadata]
         '[cn.li.mcmod.block.multiblock-core :as mb-core])

(defn on-block-right-click
  "Generic block right-click event handler.
   Dispatches to block-specific :on-right-click handlers registered in metadata."
  [{:keys [x y z block-id] :as ctx}]
  (let [routed-ctx (mb-core/route-to-controller-context ctx)
        routed-block-id (:block-id routed-ctx)]
    (log/info "Right-click event at (" x "," y "," z ") for block-id:" block-id
              "-> routed:" routed-block-id)
    (when-let [handler (event-metadata/get-block-event-handler routed-block-id :on-right-click)]
      (log/info "Dispatching to registered handler for block:" routed-block-id)
      (handler routed-ctx))))

(defn on-block-place
  "Generic block place event handler.
   Dispatches to block-specific :on-place handlers registered in metadata."
  [{:keys [x y z block-id] :as ctx}]
  (log/info "Place event at (" x "," y "," z ") for block-id:" block-id)
  (if-let [precheck-ret (mb-core/precheck-controller-place ctx)]
    precheck-ret
    (let [handler-ret (when-let [handler (event-metadata/get-block-event-handler block-id :on-place)]
                        (log/info "Dispatching to registered :on-place handler for block:" block-id)
                        (handler ctx))
          core-ret (mb-core/post-place-controller! ctx)]
      (or core-ret handler-ret))))

(defn on-block-break
  "Generic block break event handler.

   Routes part blocks to controller block handlers, then applies structure break cleanup."
  [{:keys [x y z block-id] :as ctx}]
  (let [routed-ctx (mb-core/route-to-controller-context ctx)
        routed-block-id (:block-id routed-ctx)]
    (log/info "Break event at (" x "," y "," z ") for block-id:" block-id
              "-> routed:" routed-block-id)
    (let [handler-ret (when-let [handler (event-metadata/get-block-event-handler routed-block-id :on-break)]
                        (log/info "Dispatching to registered :on-break handler for block:" routed-block-id)
                        (handler routed-ctx))
          core-ret (mb-core/apply-structure-break! ctx routed-ctx)]
      (merge (or handler-ret {}) (or core-ret {})))))

