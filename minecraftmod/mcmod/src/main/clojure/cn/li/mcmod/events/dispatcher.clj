(ns cn.li.mcmod.events.dispatcher
  "Platform-agnostic block event dispatchers.

   Forge/Fabric adapters should forward Forge events into these handlers.
   Actual per-block behavior is looked up via `cn.li.mcmod.events.metadata`."
  (:require [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.mcmod.block.multiblock-core :as mb-core]))

(defn on-block-right-click
  "Generic block right-click event handler.
   Dispatches to block-specific :on-right-click handlers registered in metadata."
  [ctx]
  (let [routed-ctx (mb-core/route-to-controller-context ctx)
        routed-block-id (:block-id routed-ctx)]
    (when-let [handler (event-metadata/get-block-event-handler routed-block-id :on-right-click)]
      (handler routed-ctx))))

(defn on-block-place
  "Generic block place event handler.
   Dispatches to block-specific :on-place handlers registered in metadata."
  [ctx]
  (if-let [precheck-ret (mb-core/precheck-controller-place ctx)]
    precheck-ret
    (let [handler-ret (when-let [handler (event-metadata/get-block-event-handler (:block-id ctx) :on-place)]
                        (handler ctx))
          core-ret (mb-core/post-place-controller! ctx)]
      (or core-ret handler-ret))))

(defn on-block-break
  "Generic block break event handler.

   Routes part blocks to controller block handlers, then applies structure break cleanup."
  [ctx]
  (let [routed-ctx (mb-core/route-to-controller-context ctx)
        routed-block-id (:block-id routed-ctx)
        handler-ret (when-let [handler (event-metadata/get-block-event-handler routed-block-id :on-break)]
                      (handler routed-ctx))
        core-ret (mb-core/apply-structure-break! ctx routed-ctx)]
    (merge (or handler-ret {}) (or core-ret {}))))

