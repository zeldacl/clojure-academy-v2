(ns my-mod.block.multiblock-core
  "Platform-neutral controller+part multiblock orchestration.

  This namespace owns generic structure checks/routing. Business actions
  (GUI behavior, inventory policies, etc.) stay in ac handlers."
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.platform.position :as pos]
            [my-mod.platform.world :as world]
            [my-mod.platform.be :as platform-be]
            [my-mod.registry.metadata :as registry-metadata]
            [my-mod.util.log :as log])
  (:import [net.minecraft.world.level.block.state BlockState]))

(defn- same-pos?
  [a b]
  (and (= (pos/pos-x a) (pos/pos-x b))
       (= (pos/pos-y a) (pos/pos-y b))
       (= (pos/pos-z a) (pos/pos-z b))))

(defn- state-empty?
  [state]
  (or (nil? state)
      (try
        (.isAir ^BlockState state)
        (catch Throwable _
          false))))

(defn- be-state
  [be]
  (try
    (or (platform-be/get-custom-state be) {})
    (catch Throwable _
      {})))

(defn- set-be-state!
  [be state-map]
  (try
    (platform-be/set-custom-state! be state-map)
    (catch Throwable _
      nil)))

(defn- structure-positions
  [controller-pos controller-spec]
  (bdsl/all-multi-block-positions controller-pos controller-spec))

(defn precheck-controller-place
  "Check if controller placement has free space for all structure positions.
  Returns {:cancel-place? true} when placement should be cancelled."
  [{:keys [world pos block-id]}]
  (if-not (registry-metadata/is-controller-block? block-id)
    nil
    (let [controller-spec (registry-metadata/get-block-spec block-id)
          occupied? (some (fn [bp]
                            (if (same-pos? bp pos)
                              false
                              (let [state (world/world-get-block-state world bp)]
                                (not (state-empty? state)))))
                          (structure-positions pos controller-spec))]
      (when occupied?
        {:cancel-place? true
         :reason :multiblock-space-occupied}))))

(defn post-place-controller!
  "Place part blocks and initialize their link state to controller.
  Returns nil or {:cancel-place? true} when required data is missing." 
  [{:keys [world pos block-id]}]
  (when (registry-metadata/is-controller-block? block-id)
    (let [controller-spec (registry-metadata/get-block-spec block-id)
          part-block-id (registry-metadata/get-part-block-id block-id)
          mx (pos/pos-x pos)
          my (pos/pos-y pos)
          mz (pos/pos-z pos)]
      (when-not part-block-id
        (log/error "Missing :part-block-id for controller block" block-id)
        {:cancel-place? true})
      (doseq [bp (structure-positions pos controller-spec)
              :when (not (same-pos? bp pos))]
        (world/world-place-block-by-id world part-block-id bp 3)
        (when-let [be (world/world-get-tile-entity world bp)]
          (set-be-state! be {:controller-pos-x mx
                             :controller-pos-y my
                             :controller-pos-z mz
                             :sub-id 1}))))))

(defn resolve-controller-pos
  "Resolve controller pos for both controller/part contexts.
  Returns BlockPos or nil when unresolved." 
  [{:keys [world pos block-id]}]
  (cond
    (registry-metadata/is-controller-block? block-id)
    pos

    (registry-metadata/is-part-block? block-id)
    (let [be (world/world-get-tile-entity world pos)
          st (be-state be)
          cx (:controller-pos-x st)
          cy (:controller-pos-y st)
          cz (:controller-pos-z st)]
      (when (and (integer? cx) (integer? cy) (integer? cz))
        (pos/create-block-pos cx cy cz)))

    :else
    nil))

(defn route-to-controller-context
  "Route ctx from part to controller if possible.
  Keeps :original-block-id and :original-pos for break decisions." 
  [ctx]
  (if-let [controller-pos (resolve-controller-pos ctx)]
    (let [controller-id (or (registry-metadata/get-controller-block-id (:block-id ctx))
                            (:block-id ctx))]
      (assoc ctx
             :original-block-id (:block-id ctx)
             :original-pos (:pos ctx)
             :block-id controller-id
             :pos controller-pos))
    ctx))

(defn apply-structure-break!
  "Apply generic structure cleanup after business break logic.
  Returns {:cancel-break? bool} so platforms can cancel vanilla break when needed." 
  [{:keys [world block-id]} routed-ctx]
  (let [controller-id (:block-id routed-ctx)
        controller-pos (:pos routed-ctx)
        controller-spec (registry-metadata/get-block-spec controller-id)]
    (if-not (and (registry-metadata/is-controller-block? controller-id)
                 controller-spec)
      {:cancel-break? false}
      (let [original-id (or (:original-block-id routed-ctx) block-id)
            remove-all? (registry-metadata/is-part-block? original-id)]
        (doseq [bp (structure-positions controller-pos controller-spec)
                :when (or remove-all?
                          (not (same-pos? bp controller-pos)))]
          (world/world-remove-block world bp))
        {:cancel-break? remove-all?}))))
