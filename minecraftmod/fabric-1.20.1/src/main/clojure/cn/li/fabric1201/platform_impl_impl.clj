(ns cn.li.fabric1201.platform-impl-impl
  "Bridge-invoked Fabric platform initializer.

  Loaded via Java ServiceLoader provider at runtime.
  Keeps bootstrap-sensitive logic out of plain namespace loading paths."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.be :as be]
            [cn.li.mc1201.platform-adapter :as pa]
            [cn.li.mc1201.installer :as installer]
            [cn.li.mc1201.reflect-util :as ru]))

(defonce ^:private initialized? (atom false))

(defn- item-key-string [item]
  (when item
    (try
      (let [builtins-cls (ru/class-noinit "net.minecraft.core.registries.BuiltInRegistries")
            item-registry (.get (.getField builtins-cls "ITEM") nil)
            rl (ru/inst item-registry "getKey" item)]
        (when rl (str rl)))
      (catch Throwable _ nil))))

(defn- block-key-string [block]
  (when block
    (try
      (let [builtins-cls (ru/class-noinit "net.minecraft.core.registries.BuiltInRegistries")
            block-registry (.get (.getField builtins-cls "BLOCK") nil)
            rl (ru/inst block-registry "getKey" block)]
        (when rl (str rl)))
      (catch Throwable _ nil))))

(defn- player-level [player]
  (try
    (ru/inst player "level")
    (catch Throwable _
      (ru/field player "level"))))

(defn- stack-empty? [stack]
  (ru/stack-empty? stack))

(defn- seq-field [obj fname]
  (try
    (seq (ru/field obj fname))
    (catch Throwable _ nil)))

(defn- inv-items-and-offhand [player]
  (let [inv (ru/inst player "getInventory")
        items (or (seq-field inv "items") '())
        offhand (or (seq-field inv "offhand") '())]
    (concat items offhand)))

(defn- count-player-item-by-id [player item-id]
  (let [target (str item-id)]
    (reduce
      (fn [acc stack]
        (if (stack-empty? stack)
          acc
          (let [id (item-key-string (ru/inst stack "getItem"))]
            (if (= id target)
              (+ acc (int (ru/inst stack "getCount")))
              acc))))
      0
      (inv-items-and-offhand player))))

(defn- consume-player-item-by-id! [player item-id amount]
  (let [need0 (int (max 0 (or amount 0)))]
    (cond
      (zero? need0) true
      (boolean (ru/inst player "isCreative")) true
      :else
      (let [target (str item-id)]
        (loop [stacks (seq (inv-items-and-offhand player))
               need need0]
          (cond
            (<= need 0) true
            (nil? stacks) false
            :else
            (let [stack (first stacks)]
              (if (stack-empty? stack)
                (recur (next stacks) need)
                (let [id (item-key-string (ru/inst stack "getItem"))]
                  (if (= id target)
                    (let [cnt (int (ru/inst stack "getCount"))
                          take (min cnt need)]
                      (ru/inst stack "shrink" (int take))
                      (recur (next stacks) (- need take)))
                    (recur (next stacks) need)))))))))))

(defn- raytrace-block-map [player reach fluid-source-only?]
  (let [r (double (or reach 5.0))]
    (try
      ;; Forge-equivalent path: Level#clip(ClipContext)
      (let [eye (ru/inst player "getEyePosition")
        view (ru/inst player "getViewVector" (float 1.0))
        end (ru/inst eye "add" (ru/inst view "scale" r))
        clip-ctx-cls (ru/class-noinit "net.minecraft.world.level.ClipContext")
        block-mode-cls (ru/class-noinit "net.minecraft.world.level.ClipContext$Block")
        fluid-mode-cls (ru/class-noinit "net.minecraft.world.level.ClipContext$Fluid")
        hit-type-cls (ru/class-noinit "net.minecraft.world.phys.HitResult$Type")
            block-outline (.get (.getField block-mode-cls "OUTLINE") nil)
            fluid-mode (.get (.getField fluid-mode-cls (if (boolean fluid-source-only?) "SOURCE_ONLY" "NONE")) nil)
            block-type (.get (.getField hit-type-cls "BLOCK") nil)
        ctx (ru/ctor clip-ctx-cls eye end block-outline fluid-mode player)
            lvl (player-level player)
        hit (ru/inst lvl "clip" ctx)]
        (when (and hit (= (ru/inst hit "getType") block-type))
          (let [hit-pos (ru/inst hit "getBlockPos")
            dir (ru/inst hit "getDirection")
            place-pos (ru/inst hit-pos "relative" dir)
            hit-state (ru/inst lvl "getBlockState" hit-pos)
            block-id (block-key-string (ru/inst hit-state "getBlock"))]
        {:hit-pos {:x (ru/inst hit-pos "getX") :y (ru/inst hit-pos "getY") :z (ru/inst hit-pos "getZ")}
         :place-pos {:x (ru/inst place-pos "getX") :y (ru/inst place-pos "getY") :z (ru/inst place-pos "getZ")}
             :block-id block-id})))
      (catch Throwable _
        ;; Fallback path: Player#pick
        (try
          (let [hit (ru/inst player "pick" r 0.0 (boolean fluid-source-only?))
            block-hit-result-cls (ru/class-noinit "net.minecraft.world.phys.BlockHitResult")]
            (when (and hit (instance? block-hit-result-cls hit))
          (let [hit-pos (ru/inst hit "getBlockPos")
            dir (ru/inst hit "getDirection")
            place-pos (ru/inst hit-pos "relative" dir)
                    lvl (player-level player)
            hit-state (ru/inst lvl "getBlockState" hit-pos)
            block-id (block-key-string (ru/inst hit-state "getBlock"))]
            {:hit-pos {:x (ru/inst hit-pos "getX") :y (ru/inst hit-pos "getY") :z (ru/inst hit-pos "getZ")}
             :place-pos {:x (ru/inst place-pos "getX") :y (ru/inst place-pos "getY") :z (ru/inst place-pos "getZ")}
                 :block-id block-id})))
          (catch Throwable _ nil))))))

(defn- inventory-owner [inventory]
  (try
    (ru/field inventory "player")
    (catch Throwable _ nil)))

(defn- world-client-side? [level]
  (try
    (boolean (ru/inst level "isClientSide"))
    (catch Throwable _
      (boolean (ru/field level "isClientSide")))))

(defn- give-player-item-stack [player stack]
  (if (stack-empty? stack)
    false
    (try
      (let [copy (ru/inst stack "copy")
            inv (ru/inst player "getInventory")
            inserted? (boolean (ru/inst inv "add" copy))]
        (when-not inserted?
          (ru/inst player "drop" copy false))
        true)
      (catch Throwable _ false))))

(defn- spawn-entity-by-id-from-player [player entity-id speed]
  (if (or (nil? player) (nil? entity-id) (= "" (str entity-id)))
    false
    (try
      (let [level (player-level player)]
        (if (world-client-side? level)
          true
              (let [builtins-cls (ru/class-noinit "net.minecraft.core.registries.BuiltInRegistries")
                entity-registry (.get (.getField builtins-cls "ENTITY_TYPE") nil)
                type (ru/inst entity-registry "get" (ru/make-rl entity-id))]
            (if (nil? type)
              false
              (let [entity (ru/inst type "create" level)]
                (if (nil? entity)
                  false
                  (let [x (double (ru/inst player "getX"))
                        y (double (ru/inst player "getEyeY"))
                        z (double (ru/inst player "getZ"))
                        yrot (float (ru/inst player "getYRot"))
                        xrot (float (ru/inst player "getXRot"))
                        s (double (or speed 1.0))
                        look (ru/inst (ru/inst (ru/inst player "getLookAngle") "normalize") "scale" s)]
                    (ru/inst entity "moveTo" x (- y 0.1) z yrot xrot)
                    (ru/inst entity "setDeltaMovement" look)
                    (try
                      (when (instance? (ru/class-noinit "net.minecraft.world.entity.projectile.Projectile") entity)
                        (ru/inst entity "setOwner" player))
                      (catch Throwable _ nil))
                    (try
                      (ru/inst entity "setOwnerPlayer" player)
                      (ru/inst entity "setPos" x (+ (double (ru/inst player "getY")) 1.0) z)
                      (catch Throwable _ nil))
                    (boolean (ru/inst level "addFreshEntity" entity)))))))))
      (catch Throwable _ false))))
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

    (alter-var-root #'be/*be-get-level-fn* (constantly (fn [x] (ru/inst x "getLevel"))))
    (alter-var-root #'be/*be-get-world-fn* (constantly (fn [x] (ru/inst x "getLevel"))))
    (alter-var-root #'be/*be-get-custom-state-fn* (constantly (fn [x] (ru/inst x "getCustomState"))))
    (alter-var-root #'be/*be-set-custom-state-fn* (constantly (fn [x s] (ru/inst x "setCustomState" s))))
    (alter-var-root #'be/*be-get-block-id-fn* (constantly (fn [x] (ru/inst x "getBlockId"))))
    (alter-var-root #'be/*be-set-changed-fn* (constantly (fn [x] (ru/inst x "setChanged"))))))

(def ^:private fabric-adapter
  (reify pa/PlatformAdapter
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

    (item-registry-name [_ item] (item-key-string item))
    (block-registry-name [_ block] (block-key-string block))

    (player-level [_ player] (player-level player))
    (player-container-menu [_ player] (ru/field player "containerMenu"))
    (inventory-owner [_ inventory] (inventory-owner inventory))
    (menu-container-id [_ menu] (ru/field menu "containerId"))

    (count-player-item-by-id [_ player item-id] (count-player-item-by-id player item-id))
    (consume-player-item-by-id! [_ player item-id amount] (consume-player-item-by-id! player item-id amount))
    (give-player-item-stack! [_ player stack] (give-player-item-stack player stack))
    (spawn-entity-by-id! [_ player entity-id speed] (spawn-entity-by-id-from-player player entity-id speed))
    (raytrace-block [_ player reach fluid-source-only?] (raytrace-block-map player reach fluid-source-only?))

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
    (item-stack-empty? [_ stack] (stack-empty? stack))

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
  (when (compare-and-set! initialized? false true)
    (installer/install-platform-core! fabric-adapter)
    (install-be-ops!)
    (log/info "platform-impl-impl initialized via SPI entrypoint"))
  nil)
