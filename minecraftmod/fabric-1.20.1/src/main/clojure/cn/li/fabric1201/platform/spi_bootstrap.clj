(ns cn.li.fabric1201.platform.spi-bootstrap
  "Bridge-invoked Fabric platform initializer.

  Loaded via Java ServiceLoader provider at runtime.
  Keeps bootstrap-sensitive logic out of plain namespace loading paths."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.be :as be]
            [cn.li.mc1201.platform.class-access :as class-access]
            [cn.li.mc1201.platform.item-ops :as item-ops]
            [cn.li.mc1201.platform.player-ops :as player-ops]
            [cn.li.mc1201.platform.world-block-ops :as world-block-ops]
            [cn.li.mc1201.platform.menu-inventory-ops :as menu-inventory-ops]
            [cn.li.mc1201.bootstrap.platform-init :as platform-init]
            [cn.li.mc1201.bootstrap.installer-core :as core]
            [cn.li.mc1201.runtime.bootstrap-interop-core :as bootstrap-core]
            [cn.li.mc1201.reflect-util :as ru]))

(def ^:private platform-init-guard-lock
  (Object.))

(def ^:private ^:dynamic *initialized?*
  false)

(defn- install-be-ops! []
  (let [scripted-be-cls (ru/class-noinit "cn.li.fabric1201.block.entity.ScriptedBlockEntity")]
    (extend scripted-be-cls be/IBlockEntity
            {:be-get-level (fn [this] (ru/inst this "getLevel"))
             :be-get-world (fn [this] (ru/inst this "getLevel"))
             :be-get-custom-state (fn [this] (ru/inst this "getCustomState"))
             :be-set-custom-state! (fn [this state] (ru/inst this "setCustomState" state))
             :be-get-block-id (fn [this] (ru/inst this "getBlockId"))
             :be-set-changed! (fn [this] (ru/inst this "setChanged"))})

    (extend scripted-be-cls pos/IHasPosition
            {:position-get-block-pos (fn [this] (ru/inst this "getBlockPos"))
             :position-get-pos (fn [this] (ru/inst this "getBlockPos"))})

    (core/install-be-fns-only!
      {:be-get-level     (fn [x] (ru/inst x "getLevel"))
       :be-get-world     (fn [x] (ru/inst x "getLevel"))
       :be-get-custom-state  (fn [x] (ru/inst x "getCustomState"))
       :be-set-custom-state! (fn [x s] (ru/inst x "setCustomState" s))
       :be-get-block-id  (fn [x] (ru/inst x "getBlockId"))
       :be-set-changed!  (fn [x] (ru/inst x "setChanged"))})))

(def ^:private fabric-adapter
  (reify class-access/ClassAccess
    (entity-class [_] (ru/class-noinit "net.minecraft.world.entity.Entity"))
    (player-class [_] (ru/class-noinit "net.minecraft.world.entity.player.Player"))
    (server-player-class [_] (ru/class-noinit "net.minecraft.server.level.ServerPlayer"))
    (local-player-class [_] nil)
    (inventory-class [_] (ru/class-noinit "net.minecraft.world.entity.player.Inventory"))
    (menu-class [_] (ru/class-noinit "net.minecraft.world.inventory.AbstractContainerMenu"))
    (item-stack-class [_] (ru/class-noinit "net.minecraft.world.item.ItemStack"))
    (item-class [_] (ru/class-noinit "net.minecraft.world.item.Item"))
    (block-state-class [_] (ru/class-noinit "net.minecraft.world.level.block.state.BlockState"))
    (level-class [_] (ru/class-noinit "net.minecraft.world.level.Level"))
    (scripted-be-class [_]
      (try
        (ru/class-noinit "cn.li.fabric1201.block.entity.ScriptedBlockEntity")
        (catch Throwable _ nil)))

    item-ops/ItemOps
    (item-registry-name [_ item] (bootstrap-core/item-key-string item))
    (block-registry-name [_ block] (bootstrap-core/block-key-string block))
    (item-stack-of [_ nbt-tag]
      (let [item-stack-cls (ru/class-noinit "net.minecraft.world.item.ItemStack")]
        (ru/static item-stack-cls "of" nbt-tag)))
    (create-item-stack-by-id [_ item-id count]
      (try
        (let [builtins-cls (ru/class-noinit "net.minecraft.core.registries.BuiltInRegistries")
              item-registry (.get (.getField builtins-cls "ITEM") nil)
              rl (ru/make-rl item-id)
              item (ru/inst item-registry "get" rl)
              item-stack-cls (ru/class-noinit "net.minecraft.world.item.ItemStack")]
          (when item
            (ru/ctor item-stack-cls item (int count))))
        (catch Throwable _ nil)))
    (item-stack-empty? [_ stack] (bootstrap-core/stack-empty? stack))

    player-ops/PlayerOps
    (player-level [_ player] (bootstrap-core/player-level player))
    (player-container-menu [_ player] (ru/field player "containerMenu"))
    (count-player-item-by-id [_ player item-id] (bootstrap-core/count-player-item-by-id player item-id))
    (consume-player-item-by-id! [_ player item-id amount] (bootstrap-core/consume-player-item-by-id! player item-id amount))
    (drop-player-main-hand-item-at! [_ player amount x y z]
      (bootstrap-core/drop-player-main-hand-item-at player amount x y z))
    (give-player-item-stack! [_ player stack] (bootstrap-core/give-player-item-stack player stack))
    (spawn-entity-by-id! [_ player entity-id speed] (bootstrap-core/spawn-entity-by-id-from-player player entity-id speed))
    (raytrace-block [_ player reach fluid-source-only?] (bootstrap-core/raytrace-block-map player reach fluid-source-only?))

    menu-inventory-ops/MenuInventoryOps
    (inventory-owner [_ inventory] (bootstrap-core/inventory-owner inventory))
    (menu-container-id [_ menu] (ru/field menu "containerId"))

    world-block-ops/WorldBlockOps
    (world-place-block-by-id [_ level block-id pos flags]
      (try
        (let [builtins-cls (ru/class-noinit "net.minecraft.core.registries.BuiltInRegistries")
              block-registry (.get (.getField builtins-cls "BLOCK") nil)
              rl (ru/make-rl block-id)
              block (ru/inst block-registry "get" rl)]
          (if (nil? block)
            false
            (boolean (ru/inst level "setBlock" pos (ru/inst block "defaultBlockState") (int flags)))))
        (catch Throwable _ false)))))

(defn init-platform!
  "Initialize Fabric 1.20.1 platform implementations via SPI entrypoint."
  []
  (when-not (var-get #'*initialized?*)
    (locking platform-init-guard-lock
      (when-not (var-get #'*initialized?*)
        (platform-init/install-platform-core! fabric-adapter)
        (install-be-ops!)
        (alter-var-root #'entity/*player-spawn-entity-by-id-with-options-fn*
                        (constantly
                         (fn [player entity-id speed options]
                           (bootstrap-core/spawn-entity-by-id-from-player-with-options
                             player entity-id speed options))))
        (alter-var-root #'*initialized?* (constantly true))
        (log/info "fabric platform SPI bootstrap initialized via ServiceLoader entrypoint"))))
  nil)