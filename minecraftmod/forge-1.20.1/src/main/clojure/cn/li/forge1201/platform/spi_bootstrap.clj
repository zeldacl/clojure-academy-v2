
(ns cn.li.forge1201.platform.spi-bootstrap
  "Bridge-invoked platform initializer.

  Uses shared mc1201 installer with a Forge-specific adapter implementation."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.installer :as shared-installer]
            [cn.li.mc1201.platform-adapter :as pa]
             [cn.li.forge1201.platform.bindings :as bindings]
             [cn.li.forge1201.integration.side :as side])
  (:import [cn.li.forge1201.bridge ForgeRuntimeBridge]
           [net.minecraft.core BlockPos]
           [net.minecraft.world.level Level]
           [net.minecraft.world.level.block.state BlockState]
           [net.minecraft.world.phys BlockHitResult]))


(defonce ^:private initialized? (atom false))

(defn- resolve-local-player-class []
  (when (side/client-side?)
    (-> (Class/forName "cn.li.forge1201.bridge.ClientPlatformBridge")
        (.getMethod "getLocalPlayerClass" (into-array Class []))
        (.invoke nil (object-array [])))))

(defn- raytrace-block-map [player reach fluid-source-only?]
  (when-let [^BlockHitResult hit (ForgeRuntimeBridge/playerRaytraceBlock player (double (or reach 5.0)) (boolean fluid-source-only?))]
    (let [^BlockPos hit-pos (.getBlockPos hit)
          ^BlockPos place-pos (.relative hit-pos (.getDirection hit))
          ^Level level (ForgeRuntimeBridge/getEntityLevel player)
          ^BlockState hit-state (.getBlockState level hit-pos)]
      {:hit-pos {:x (.getX hit-pos) :y (.getY hit-pos) :z (.getZ hit-pos)}
       :place-pos {:x (.getX place-pos) :y (.getY place-pos) :z (.getZ place-pos)}
       :block-id (ForgeRuntimeBridge/getBlockKey (.getBlock hit-state))})))

(def ^:private forge-adapter
  (reify pa/PlatformAdapter
    (entity-class [_] (ForgeRuntimeBridge/getEntityClass))
    (player-class [_] (ForgeRuntimeBridge/getPlayerClass))
    (server-player-class [_] (ForgeRuntimeBridge/getServerPlayerClass))
    (local-player-class [_] (resolve-local-player-class))
    (inventory-class [_] (ForgeRuntimeBridge/getInventoryClass))
    (menu-class [_] (ForgeRuntimeBridge/getAbstractContainerMenuClass))
    (item-stack-class [_] (ForgeRuntimeBridge/getItemStackClass))
    (item-class [_] (ForgeRuntimeBridge/getItemClass))
    (block-state-class [_] (ForgeRuntimeBridge/getBlockStateClass))
    (level-class [_] (Class/forName "net.minecraft.world.level.Level"))
    (scripted-be-class [_] nil)

    (item-registry-name [_ item] (ForgeRuntimeBridge/getItemKeyString item))
    (block-registry-name [_ block] (ForgeRuntimeBridge/getBlockKey block))

    (player-level [_ player] (ForgeRuntimeBridge/getEntityLevel player))
    (player-container-menu [_ player] (ForgeRuntimeBridge/getPlayerContainerMenu player))
    (inventory-owner [_ inventory] (ForgeRuntimeBridge/getInventoryPlayer inventory))
    (menu-container-id [_ menu] (ForgeRuntimeBridge/getMenuContainerId menu))

    (count-player-item-by-id [_ player item-id] (ForgeRuntimeBridge/countPlayerItemById player (str item-id)))
    (consume-player-item-by-id! [_ player item-id amount] (ForgeRuntimeBridge/consumePlayerItemById player (str item-id) (int (or amount 0))))
    (give-player-item-stack! [_ player stack] (ForgeRuntimeBridge/givePlayerItemStack player stack))
    (spawn-entity-by-id! [_ player entity-id speed] (ForgeRuntimeBridge/spawnEntityByIdFromPlayer player (str entity-id) (float (or speed 1.0))))
    (raytrace-block [_ player reach fluid-source-only?] (raytrace-block-map player reach fluid-source-only?))

    (item-stack-of [_ nbt] (ForgeRuntimeBridge/itemStackOf nbt))
    (create-item-stack-by-id [_ item-id count] (ForgeRuntimeBridge/createItemStackById (str item-id) (int count)))
    (item-stack-empty? [_ stack] (ForgeRuntimeBridge/isItemStackEmpty stack))

    (world-place-block-by-id [_ level block-id pos flags] (bindings/world-place-block-by-id level block-id pos flags))))

(defn init-platform!
  "Initialize Forge 1.20.1 platform implementations.

  NOTE: Protocol extension migration is in progress. This function is kept as the
  stable entrypoint for Java SPI provider invocation."
  []
  (when (compare-and-set! initialized? false true)
    (shared-installer/install-foundation!)
    (shared-installer/install-entity-protocols-only! forge-adapter)
    (shared-installer/install-item-protocols-only! forge-adapter)
    (shared-installer/install-block-state-protocol-only! forge-adapter)
    (shared-installer/install-resource-factory-only!)
    (shared-installer/install-item-factories-only!
      (fn [nbt] (pa/item-stack-of forge-adapter nbt))
      (fn [item-id count] (pa/create-item-stack-by-id forge-adapter item-id count))
      (fn [stack] (pa/item-stack-empty? forge-adapter stack)))
    (shared-installer/install-world-fns-only!
      {:world-get-tile-entity bindings/world-get-tile-entity
       :world-get-block-state bindings/world-get-block-state
       :world-set-block bindings/world-set-block
       :world-remove-block bindings/world-remove-block
       :world-break-block bindings/world-break-block
       :world-place-block-by-id bindings/world-place-block-by-id
       :world-is-chunk-loaded? bindings/world-is-chunk-loaded?
       :world-get-day-time bindings/world-get-day-time
       :world-get-dimension-id bindings/world-get-dimension-id
       :world-get-players bindings/world-get-players
       :world-is-raining bindings/world-is-raining
       :world-is-client-side bindings/world-is-client-side
       :world-can-see-sky bindings/world-can-see-sky})
    (shared-installer/install-be-fns-only!
      {:be-get-level bindings/be-get-level
       :be-get-world bindings/be-get-world
       :be-get-custom-state bindings/be-get-custom-state
       :be-set-custom-state! bindings/be-set-custom-state!
       :be-get-block-id bindings/be-get-block-id
       :be-set-changed! bindings/be-set-changed!})
    (log/info "forge platform SPI bootstrap initialized via ServiceLoader entrypoint"))
  nil)