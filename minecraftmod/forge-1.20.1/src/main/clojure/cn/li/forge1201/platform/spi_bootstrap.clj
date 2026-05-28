
(ns cn.li.forge1201.platform.spi-bootstrap
  "Bridge-invoked platform initializer.

  Uses shared mc1201 installer with a Forge-specific adapter implementation."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.bootstrap.platform-init :as platform-init]
            [cn.li.mc1201.platform.class-access :as class-access]
            [cn.li.mc1201.platform.item-ops :as item-ops]
            [cn.li.mc1201.platform.player-ops :as player-ops]
            [cn.li.mc1201.platform.world-block-ops :as world-block-ops]
            [cn.li.mc1201.platform.menu-inventory-ops :as menu-inventory-ops]
            [cn.li.forge1201.integration.side :as side])
  (:import [cn.li.mc1201.runtime BlockRegistryShared ItemInventoryShared ItemPlayerOpsShared ItemRegistryShared ParticleEntityShared RuntimeAccessShared]
           [net.minecraft.core BlockPos]
           [net.minecraft.world.entity.item ItemEntity]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.level Level]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world.level.block.state BlockState]
           [net.minecraft.world.phys BlockHitResult]))


(def ^:private platform-init-guard-lock
  (Object.))

(def ^:private ^:dynamic *initialized?*
  false)

(defn- resolve-binding! [binding-name]
  (require 'cn.li.forge1201.platform.bindings)
  (or (ns-resolve 'cn.li.forge1201.platform.bindings binding-name)
      (throw (ex-info "Missing Forge platform binding"
                      {:binding binding-name
                       :available (->> (ns-publics 'cn.li.forge1201.platform.bindings)
                                       keys
                                       sort
                                       vec)}))))

(defn- resolve-local-player-class []
  (when (side/client-side?)
    (-> (Class/forName "cn.li.forge1201.bridge.ClientPlatformBridge")
        (.getMethod "getLocalPlayerClass" (into-array Class []))
        (.invoke nil (object-array [])))))

(defn- raytrace-block-map [player reach fluid-source-only?]
  (when-let [^BlockHitResult hit (RuntimeAccessShared/playerRaytraceBlock player (double (or reach 5.0)) (boolean fluid-source-only?))]
    (let [^BlockPos hit-pos (.getBlockPos hit)
          ^BlockPos place-pos (.relative hit-pos (.getDirection hit))
          ^Level level (RuntimeAccessShared/getEntityLevel player)
          ^BlockState hit-state (.getBlockState level hit-pos)]
      {:hit-pos {:x (.getX hit-pos) :y (.getY hit-pos) :z (.getZ hit-pos)}
       :place-pos {:x (.getX place-pos) :y (.getY place-pos) :z (.getZ place-pos)}
       :block-id (BlockRegistryShared/getBlockKey (.getBlock hit-state))})))

(def ^:private forge-adapter
  (reify class-access/ClassAccess
    (entity-class [_] (RuntimeAccessShared/getEntityClass))
    (player-class [_] (RuntimeAccessShared/getPlayerClass))
    (server-player-class [_] (RuntimeAccessShared/getServerPlayerClass))
    (local-player-class [_] (resolve-local-player-class))
    (inventory-class [_] (RuntimeAccessShared/getInventoryClass))
    (menu-class [_] (RuntimeAccessShared/getAbstractContainerMenuClass))
    (item-stack-class [_] (RuntimeAccessShared/getItemStackClass))
    (item-class [_] (RuntimeAccessShared/getItemClass))
    (block-state-class [_] (RuntimeAccessShared/getBlockStateClass))
    (level-class [_] (Class/forName "net.minecraft.world.level.Level"))
    (scripted-be-class [_] nil)

    item-ops/ItemOps
    (item-registry-name [_ item] (ItemInventoryShared/getItemKeyString item))
    (block-registry-name [_ block] (BlockRegistryShared/getBlockKey block))
    (item-stack-of [_ nbt] (RuntimeAccessShared/itemStackOf nbt))
    (create-item-stack-by-id [_ item-id count] (ItemRegistryShared/createItemStackById (str item-id) (int count)))
    (item-stack-empty? [_ stack] (ItemInventoryShared/isItemStackEmpty stack))

    player-ops/PlayerOps
    (player-level [_ player] (RuntimeAccessShared/getEntityLevel player))
    (player-container-menu [_ player] (RuntimeAccessShared/getPlayerContainerMenu player))
    (count-player-item-by-id [_ player item-id] (ItemPlayerOpsShared/countPlayerItemById player (str item-id)))
    (consume-player-item-by-id! [_ player item-id amount] (ItemPlayerOpsShared/consumePlayerItemById player (str item-id) (int (or amount 0))))
    (drop-player-main-hand-item-at! [_ player amount x y z]
      (let [n (int (max 0 (or amount 0)))]
        (cond
          (nil? player) false
          (zero? n) true
          (boolean (.isCreative ^Player player)) true
          :else
          (let [^ItemStack stack (.getMainHandItem ^Player player)]
            (if (or (nil? stack)
                    (.isEmpty stack)
                    (< (int (.getCount stack)) n))
              false
              (let [^ItemStack drop-stack (.copy stack)
                    level ^Level (RuntimeAccessShared/getEntityLevel player)]
                (.setCount drop-stack n)
                (.shrink stack n)
                (if (or (nil? level) (.isClientSide level))
                  true
                  (boolean (.addFreshEntity level (ItemEntity. level (double x) (double y) (double z) drop-stack))))))))))
    (give-player-item-stack! [_ player stack] (ItemPlayerOpsShared/givePlayerItemStack player stack))
    (spawn-entity-by-id! [_ player entity-id speed] (ParticleEntityShared/spawnEntityByIdFromPlayer player (str entity-id) (float (or speed 1.0))))
    (raytrace-block [_ player reach fluid-source-only?] (raytrace-block-map player reach fluid-source-only?))

    menu-inventory-ops/MenuInventoryOps
    (inventory-owner [_ inventory] (RuntimeAccessShared/getInventoryPlayer inventory))
    (menu-container-id [_ menu] (RuntimeAccessShared/getMenuContainerId menu))

    world-block-ops/WorldBlockOps
    (world-place-block-by-id [_ level block-id pos flags]
      ((resolve-binding! 'world-place-block-by-id) level block-id pos flags))))

(defn init-platform!
  "Initialize Forge 1.20.1 platform implementations.

  NOTE: Protocol extension migration is in progress. This function is kept as the
  stable entrypoint for Java SPI provider invocation."
  []
  (when-not (var-get #'*initialized?*)
    (locking platform-init-guard-lock
      (when-not (var-get #'*initialized?*)
        (platform-init/install-platform-foundation+hooks!
          forge-adapter
          {:world-get-tile-entity (resolve-binding! 'world-get-tile-entity)
           :world-get-block-state (resolve-binding! 'world-get-block-state)
           :world-set-block (resolve-binding! 'world-set-block)
           :world-remove-block (resolve-binding! 'world-remove-block)
           :world-break-block (resolve-binding! 'world-break-block)
           :world-place-block-by-id (resolve-binding! 'world-place-block-by-id)
           :world-is-chunk-loaded? (resolve-binding! 'world-is-chunk-loaded?)
           :world-get-day-time (resolve-binding! 'world-get-day-time)
           :world-get-dimension-id (resolve-binding! 'world-get-dimension-id)
           :world-get-players (resolve-binding! 'world-get-players)
           :world-is-raining (resolve-binding! 'world-is-raining)
           :world-is-client-side (resolve-binding! 'world-is-client-side)
           :world-can-see-sky (resolve-binding! 'world-can-see-sky)}
          {:be-get-level (resolve-binding! 'be-get-level)
           :be-get-world (resolve-binding! 'be-get-world)
           :be-get-custom-state (resolve-binding! 'be-get-custom-state)
           :be-set-custom-state! (resolve-binding! 'be-set-custom-state!)
           :be-get-block-id (resolve-binding! 'be-get-block-id)
           :be-set-changed! (resolve-binding! 'be-set-changed!)})
        (alter-var-root #'*initialized?* (constantly true))
        (log/info "forge platform SPI bootstrap initialized via ServiceLoader entrypoint"))))
  nil)