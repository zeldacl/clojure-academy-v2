(ns cn.li.mc1201.platform.runtime-ops
  "Minecraft-touching runtime callables shared by Forge/Fabric adapter maps.

  Consumed by cn.li.platform.adapter.minecraft-ops/build-adapter-map."
  (:import [cn.li.mc1201.runtime BlockRegistry ItemInventory ItemPlayerOps
            ItemRegistry ParticleEntity RuntimeAccess]
           [net.minecraft.core BlockPos]
           [net.minecraft.world.entity.item ItemEntity]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world.level Level]
           [net.minecraft.world.level.block.state BlockState]
           [net.minecraft.world.phys BlockHitResult]))

(defn- raytrace-block
  [player reach fluid-source-only?]
  (when-let [^BlockHitResult hit (RuntimeAccess/playerRaytraceBlock
                                  player
                                  (double (or reach 5.0))
                                  (boolean fluid-source-only?))]
    (let [^BlockPos hit-pos (.getBlockPos hit)
          ^BlockPos place-pos (.relative hit-pos (.getDirection hit))
          ^Level level (RuntimeAccess/getEntityLevel player)
          ^BlockState hit-state (.getBlockState level hit-pos)]
      {:hit-pos {:x (.getX hit-pos) :y (.getY hit-pos) :z (.getZ hit-pos)}
       :place-pos {:x (.getX place-pos) :y (.getY place-pos) :z (.getZ place-pos)}
       :block-id (BlockRegistry/getBlockKey (.getBlock hit-state))})))

(defn- drop-player-main-hand-item-at!
  [player amount x y z]
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
                level ^Level (RuntimeAccess/getEntityLevel player)]
            (.setCount drop-stack n)
            (.shrink stack n)
            (if (or (nil? level) (.isClientSide level))
              true
              (boolean (.addFreshEntity level
                                        (ItemEntity. level (double x) (double y) (double z) drop-stack))))))))))

(defn standard-runtime-ops
  "Return the shared runtime callable map (without loader-specific slots)."
  []
  {:entity-class #(RuntimeAccess/getEntityClass)
   :player-class #(RuntimeAccess/getPlayerClass)
   :server-player-class #(RuntimeAccess/getServerPlayerClass)
   :inventory-class #(RuntimeAccess/getInventoryClass)
   :menu-class #(RuntimeAccess/getAbstractContainerMenuClass)
   :item-stack-class #(RuntimeAccess/getItemStackClass)
   :item-class #(RuntimeAccess/getItemClass)
   :block-state-class #(RuntimeAccess/getBlockStateClass)
   :level-class #(RuntimeAccess/getLevelClass)
   :item-registry-name (fn [item] (ItemInventory/getItemKeyString item))
   :block-registry-name (fn [block] (BlockRegistry/getBlockKey block))
   :item-stack-of (fn [nbt] (RuntimeAccess/itemStackOf nbt))
   :create-item-stack-by-id (fn [item-id count]
                              (ItemRegistry/createItemStackById (str item-id) (int count)))
   :item-stack-empty? (fn [stack] (ItemInventory/isItemStackEmpty stack))
   :player-level (fn [player] (RuntimeAccess/getEntityLevel player))
   :player-container-menu (fn [player] (RuntimeAccess/getPlayerContainerMenu player))
   :count-player-item-by-id (fn [player item-id]
                              (ItemPlayerOps/countPlayerItemById player (str item-id)))
   :consume-player-item-by-id! (fn [player item-id amount]
                                 (ItemPlayerOps/consumePlayerItemById
                                  player (str item-id) (int (or amount 0))))
   :drop-player-main-hand-item-at! drop-player-main-hand-item-at!
   :give-player-item-stack! (fn [player stack]
                              (ItemPlayerOps/givePlayerItemStack player stack))
   :spawn-entity-by-id! (fn [player entity-id speed]
                          (ParticleEntity/spawnEntityByIdFromPlayer
                           player (str entity-id) (float (or speed 1.0))))
   :spawn-tracked-entity-by-id! (fn [player entity-id speed life]
                                  (ParticleEntity/spawnTrackedEntityByIdFromPlayer
                                   player
                                   (str entity-id)
                                   (float (or speed 0.0))
                                   (when life (int life))))
   :raytrace-block raytrace-block
   :inventory-owner (fn [inventory] (RuntimeAccess/getInventoryPlayer inventory))
   :menu-container-id (fn [menu] (RuntimeAccess/getMenuContainerId menu))})
