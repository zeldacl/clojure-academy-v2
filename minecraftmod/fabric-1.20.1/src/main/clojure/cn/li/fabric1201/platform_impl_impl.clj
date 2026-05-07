(ns cn.li.fabric1201.platform-impl-impl
  "Bridge-invoked Fabric platform initializer.

  Loaded via Java ServiceLoader provider at runtime.
  Keeps bootstrap-sensitive logic out of plain namespace loading paths."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.mcmod.platform.resource :as resource]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as be]))

(defonce ^:private initialized? (atom false))

(defn- class-noinit [^String class-name]
  (Class/forName class-name false (.getClassLoader (class *ns*))))

(defn- ctor [^Class cls & args]
  (clojure.lang.Reflector/invokeConstructor cls (to-array args)))

(defn- inst [target method-name & args]
  (clojure.lang.Reflector/invokeInstanceMethod target method-name (to-array args)))

(defn- static [^Class cls method-name & args]
  (clojure.lang.Reflector/invokeStaticMethod cls method-name (to-array args)))

(defn- field [target field-name]
  (clojure.lang.Reflector/getInstanceField target field-name))

(defn- make-rl [id]
  (let [rl-cls (class-noinit "net.minecraft.resources.ResourceLocation")]
    (ctor rl-cls (str id))))

(defn- item-key-string [item]
  (when item
    (try
      (let [builtins-cls (class-noinit "net.minecraft.core.registries.BuiltInRegistries")
            item-registry (.get (.getField builtins-cls "ITEM") nil)
            rl (inst item-registry "getKey" item)]
        (when rl (str rl)))
      (catch Throwable _ nil))))

(defn- block-key-string [block]
  (when block
    (try
      (let [builtins-cls (class-noinit "net.minecraft.core.registries.BuiltInRegistries")
            block-registry (.get (.getField builtins-cls "BLOCK") nil)
            rl (inst block-registry "getKey" block)]
        (when rl (str rl)))
      (catch Throwable _ nil))))

(defn- player-level [player]
  (try
    (inst player "level")
    (catch Throwable _
      (field player "level"))))

(defn- inventory-size [inv]
  (int
   (or
    (try (inst inv "getContainerSize") (catch Throwable _ nil))
    (try (inst inv "getContainerSize" nil) (catch Throwable _ nil))
    0)))

(defn- inventory-item [inv slot]
  (try
    (inst inv "getItem" (int slot))
    (catch Throwable _ nil)))

(defn- stack-empty? [stack]
  (or (nil? stack)
      (try (boolean (inst stack "isEmpty")) (catch Throwable _ true))))

(defn- seq-field [obj fname]
  (try
    (seq (field obj fname))
    (catch Throwable _ nil)))

(defn- inv-items-and-offhand [player]
  (let [inv (inst player "getInventory")
        items (or (seq-field inv "items") '())
        offhand (or (seq-field inv "offhand") '())]
    (concat items offhand)))

(defn- count-player-item-by-id [player item-id]
  (let [target (str item-id)]
    (reduce
      (fn [acc stack]
        (if (stack-empty? stack)
          acc
          (let [id (item-key-string (inst stack "getItem"))]
            (if (= id target)
              (+ acc (int (inst stack "getCount")))
              acc))))
      0
      (inv-items-and-offhand player))))

(defn- consume-player-item-by-id! [player item-id amount]
  (let [need0 (int (max 0 (or amount 0)))]
    (cond
      (zero? need0) true
      (boolean (inst player "isCreative")) true
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
                (let [id (item-key-string (inst stack "getItem"))]
                  (if (= id target)
                    (let [cnt (int (inst stack "getCount"))
                          take (min cnt need)]
                      (inst stack "shrink" (int take))
                      (recur (next stacks) (- need take)))
                    (recur (next stacks) need)))))))))))

(defn- raytrace-block-map [player reach fluid-source-only?]
  (let [r (double (or reach 5.0))]
    (try
      ;; Forge-equivalent path: Level#clip(ClipContext)
      (let [eye (inst player "getEyePosition")
            view (inst player "getViewVector" (float 1.0))
            end (inst eye "add" (inst view "scale" r))
            clip-ctx-cls (class-noinit "net.minecraft.world.level.ClipContext")
            block-mode-cls (class-noinit "net.minecraft.world.level.ClipContext$Block")
            fluid-mode-cls (class-noinit "net.minecraft.world.level.ClipContext$Fluid")
            hit-type-cls (class-noinit "net.minecraft.world.phys.HitResult$Type")
            block-outline (.get (.getField block-mode-cls "OUTLINE") nil)
            fluid-mode (.get (.getField fluid-mode-cls (if (boolean fluid-source-only?) "SOURCE_ONLY" "NONE")) nil)
            block-type (.get (.getField hit-type-cls "BLOCK") nil)
            ctx (ctor clip-ctx-cls eye end block-outline fluid-mode player)
            lvl (player-level player)
            hit (inst lvl "clip" ctx)]
        (when (and hit (= (inst hit "getType") block-type))
          (let [hit-pos (inst hit "getBlockPos")
                dir (inst hit "getDirection")
                place-pos (inst hit-pos "relative" dir)
                hit-state (inst lvl "getBlockState" hit-pos)
                block-id (block-key-string (inst hit-state "getBlock"))]
            {:hit-pos {:x (inst hit-pos "getX") :y (inst hit-pos "getY") :z (inst hit-pos "getZ")}
             :place-pos {:x (inst place-pos "getX") :y (inst place-pos "getY") :z (inst place-pos "getZ")}
             :block-id block-id})))
      (catch Throwable _
        ;; Fallback path: Player#pick
        (try
          (let [hit (inst player "pick" r 0.0 (boolean fluid-source-only?))
                block-hit-result-cls (class-noinit "net.minecraft.world.phys.BlockHitResult")]
            (when (and hit (instance? block-hit-result-cls hit))
              (let [hit-pos (inst hit "getBlockPos")
                    dir (inst hit "getDirection")
                    place-pos (inst hit-pos "relative" dir)
                    lvl (player-level player)
                    hit-state (inst lvl "getBlockState" hit-pos)
                    block-id (block-key-string (inst hit-state "getBlock"))]
                {:hit-pos {:x (inst hit-pos "getX") :y (inst hit-pos "getY") :z (inst hit-pos "getZ")}
                 :place-pos {:x (inst place-pos "getX") :y (inst place-pos "getY") :z (inst place-pos "getZ")}
                 :block-id block-id})))
          (catch Throwable _ nil))))))

(defn- inventory-owner [inventory]
  (try
    (field inventory "player")
    (catch Throwable _ nil)))

(defn- world-client-side? [level]
  (try
    (boolean (inst level "isClientSide"))
    (catch Throwable _
      (boolean (field level "isClientSide")))))

(defn- give-player-item-stack [player stack]
  (if (stack-empty? stack)
    false
    (try
      (let [copy (inst stack "copy")
            inv (inst player "getInventory")
            inserted? (boolean (inst inv "add" copy))]
        (when-not inserted?
          (inst player "drop" copy false))
        true)
      (catch Throwable _ false))))

(defn- spawn-entity-by-id-from-player [player entity-id speed]
  (if (or (nil? player) (nil? entity-id) (= "" (str entity-id)))
    false
    (try
      (let [level (player-level player)]
        (if (world-client-side? level)
          true
          (let [builtins-cls (class-noinit "net.minecraft.core.registries.BuiltInRegistries")
                entity-registry (.get (.getField builtins-cls "ENTITY_TYPE") nil)
                type (inst entity-registry "get" (make-rl entity-id))]
            (if (nil? type)
              false
              (let [entity (inst type "create" level)]
                (if (nil? entity)
                  false
                  (let [x (double (inst player "getX"))
                        y (double (inst player "getEyeY"))
                        z (double (inst player "getZ"))
                        yrot (float (inst player "getYRot"))
                        xrot (float (inst player "getXRot"))
                        s (double (or speed 1.0))
                        look (inst (inst (inst player "getLookAngle") "normalize") "scale" s)]
                    (inst entity "moveTo" x (- y 0.1) z yrot xrot)
                    (inst entity "setDeltaMovement" look)
                    (try
                      (when (instance? (class-noinit "net.minecraft.world.entity.projectile.Projectile") entity)
                        (inst entity "setOwner" player))
                      (catch Throwable _ nil))
                    (try
                      (inst entity "setOwnerPlayer" player)
                      (inst entity "setPos" x (+ (double (inst player "getY")) 1.0) z)
                      (catch Throwable _ nil))
                    (boolean (inst level "addFreshEntity" entity)))))))))
      (catch Throwable _ false))))

(defn- install-nbt-ops! []
  (let [compound-tag-cls (class-noinit "net.minecraft.nbt.CompoundTag")
        list-tag-cls (class-noinit "net.minecraft.nbt.ListTag")]
    (extend compound-tag-cls nbt/INBTCompound
            {:nbt-set-int! (fn [this key value] (inst this "putInt" key (int value)) this)
             :nbt-get-int (fn [this key] (inst this "getInt" key))
             :nbt-set-string! (fn [this key value] (inst this "putString" key (str value)) this)
             :nbt-get-string (fn [this key] (inst this "getString" key))
             :nbt-set-boolean! (fn [this key value] (inst this "putBoolean" key (boolean value)) this)
             :nbt-get-boolean (fn [this key] (inst this "getBoolean" key))
             :nbt-set-double! (fn [this key value] (inst this "putDouble" key (double value)) this)
             :nbt-get-double (fn [this key] (inst this "getDouble" key))
             :nbt-set-tag! (fn [this key tag] (inst this "put" key tag) this)
             :nbt-get-tag (fn [this key] (inst this "get" key))
             :nbt-get-compound (fn [this key] (inst this "getCompound" key))
             :nbt-get-list (fn [this key] (inst this "getList" key 10))
             :nbt-has-key? (fn [this key] (inst this "contains" key))
             :nbt-set-float! (fn [this key value] (inst this "putFloat" key (float value)) this)
             :nbt-get-float (fn [this key] (inst this "getFloat" key))
             :nbt-set-long! (fn [this key value] (inst this "putLong" key (long value)) this)
             :nbt-get-long (fn [this key] (inst this "getLong" key))})

    (extend list-tag-cls nbt/INBTList
            {:nbt-append! (fn [this element] (inst this "add" element) this)
             :nbt-list-size (fn [this] (inst this "size"))
             :nbt-list-get (fn [this index]
                             (let [idx (int index)
                                   n (int (inst this "size"))]
                               (when (and (>= idx 0) (< idx n))
                                 (inst this "get" idx))))
             :nbt-list-get-compound (fn [this index]
                                      (let [idx (int index)
                                            n (int (inst this "size"))]
                                        (when (and (>= idx 0) (< idx n))
                                          (inst this "getCompound" idx))))})

    (alter-var-root #'nbt/*nbt-factory*
                    (constantly {:create-compound #(ctor compound-tag-cls)
                                 :create-list #(ctor list-tag-cls)}))
    (alter-var-root #'nbt/*nbt-has-key-fn*
                    (constantly (fn [this key] (inst this "contains" key))))))

(defn- install-position-ops! []
  (let [block-pos-cls (class-noinit "net.minecraft.core.BlockPos")
  scripted-be-cls (class-noinit "cn.li.fabric1201.block.entity.ScriptedBlockEntity")]
    (extend block-pos-cls pos/IBlockPos
      {:pos-x (fn [this] (inst this "getX"))
       :pos-y (fn [this] (inst this "getY"))
       :pos-z (fn [this] (inst this "getZ"))})

    (extend scripted-be-cls pos/IHasPosition
      {:position-get-block-pos (fn [this] (inst this "getBlockPos"))
       :position-get-pos (fn [this] (inst this "getBlockPos"))})

    (alter-var-root #'pos/*position-factory*
        (constantly (fn [x y z] (ctor block-pos-cls (int x) (int y) (int z)))))
    (alter-var-root #'pos/*pos-above-fn*
        (constantly (fn [p] (inst p "above"))))))

(defn- install-item-ops! []
  (let [item-stack-cls (class-noinit "net.minecraft.world.item.ItemStack")
        item-cls (class-noinit "net.minecraft.world.item.Item")]
    (extend item-stack-cls item/IItemStack
            {:item-is-empty? (fn [this] (inst this "isEmpty"))
             :item-get-count (fn [this] (inst this "getCount"))
             :item-get-max-stack-size (fn [this] (inst this "getMaxStackSize"))
             :item-is-equal? (fn [this other] (static item-stack-cls "matches" this other))
             :item-save-to-nbt (fn [this n] (inst this "save" n))
             :item-get-or-create-tag (fn [this] (inst this "getOrCreateTag"))
             :item-get-max-damage (fn [this] (inst this "getMaxDamage"))
             :item-set-damage! (fn [this dmg] (inst this "setDamageValue" (int dmg)))
             :item-get-damage (fn [this] (inst this "getDamageValue"))
             :item-get-item (fn [this] (inst this "getItem"))
             :item-get-tag-compound (fn [this] (inst this "getTag"))
             :item-split (fn [this amount] (inst this "split" (int amount)))})

        (extend item-cls item/IItem
            {:item-get-description-id (fn [this] (inst this "getDescriptionId"))
             :item-get-registry-name (fn [this] (item-key-string this))})

    (alter-var-root #'item/*item-factory*
                    (constantly (fn [nbt-tag] (static item-stack-cls "of" nbt-tag))))
    (alter-var-root #'item/*item-stack-resolver*
                    (constantly
                      (fn [item-id count]
                        (try
                          (let [builtins-cls (class-noinit "net.minecraft.core.registries.BuiltInRegistries")
                                rl-cls (class-noinit "net.minecraft.resources.ResourceLocation")
                                item-registry (.get (.getField builtins-cls "ITEM") nil)
                                rl (ctor rl-cls (str item-id))
                                it (inst item-registry "get" rl)]
                            (when it
                              (ctor item-stack-cls it (int count))))
                          (catch Throwable _ nil)))))))

(defn- install-world-ops! []
  (let [block-state-cls (class-noinit "net.minecraft.world.level.block.state.BlockState")
        level-cls (class-noinit "net.minecraft.world.level.Level")]
    (extend block-state-cls world/IBlockStateOps
            {:block-state-is-air (fn [this] (inst this "isAir"))
             :block-state-get-block (fn [this] (inst this "getBlock"))
             :block-state-get-state-definition (fn [this] (inst (inst this "getBlock") "getStateDefinition"))
             :block-state-get-property (fn [_this state-def prop-name] (inst state-def "getProperty" prop-name))
             :block-state-set-property (fn [this prop value] (inst this "setValue" prop value))})

    (extend level-cls world/IWorldAccess
            {:world-get-tile-entity (fn [this p] (inst this "getBlockEntity" p))
             :world-get-block-state (fn [this p] (inst this "getBlockState" p))
             :world-set-block (fn [this p s flags] (inst this "setBlock" p s (int flags)))
             :world-remove-block (fn [this p] (inst this "destroyBlock" p false))
             :world-break-block (fn [this p drop?] (inst this "destroyBlock" p (boolean drop?)))
             :world-place-block-by-id (fn [this block-id p flags]
                                        (try
                                          (let [builtins-cls (class-noinit "net.minecraft.core.registries.BuiltInRegistries")
                                                block-registry (.get (.getField builtins-cls "BLOCK") nil)
                                                rl (make-rl block-id)
                                                block (inst block-registry "get" rl)]
                                            (if (nil? block)
                                              false
                                              (inst this "setBlock" p (inst block "defaultBlockState") (int flags))))
                                          (catch Throwable _ false)))
             :world-is-chunk-loaded? (fn [this cx cz] (inst this "hasChunk" (int cx) (int cz)))
             :world-get-day-time (fn [this] (inst this "getDayTime"))
             :world-get-dimension-id (fn [this] (str (inst (inst this "dimension") "location")))
             :world-get-players (fn [this] (seq (inst this "players")))
             :world-is-raining (fn [this] (inst this "isRaining"))
             :world-is-client-side (fn [this]
                                     (try
                                       (inst this "isClientSide")
                                       (catch Throwable _
                                         (field this "isClientSide"))))
             :world-can-see-sky (fn [this p] (inst this "canSeeSky" p))})

    (alter-var-root #'world/*world-get-day-time-fn* (constantly (fn [level] (inst level "getDayTime"))))
    (alter-var-root #'world/*world-get-dimension-id-fn* (constantly (fn [level] (str (inst (inst level "dimension") "location")))))
    (alter-var-root #'world/*world-get-players-fn* (constantly (fn [level] (seq (inst level "players")))))
    (alter-var-root #'world/*world-is-raining-fn* (constantly (fn [level] (inst level "isRaining"))))
    (alter-var-root #'world/*world-is-client-side-fn* (constantly (fn [level]
                                                                     (try
                                                                       (inst level "isClientSide")
                                                                       (catch Throwable _
                                                                         (field level "isClientSide"))))))
    (alter-var-root #'world/*world-can-see-sky-fn* (constantly (fn [level p] (inst level "canSeeSky" p))))))

(defn- install-be-ops! []
  (let [scripted-be-cls (class-noinit "cn.li.fabric1201.block.entity.ScriptedBlockEntity")]
    (extend scripted-be-cls be/IBlockEntity
            {:be-get-level (fn [this] (inst this "getLevel"))
             :be-get-world (fn [this] (inst this "getLevel"))
             :be-get-custom-state (fn [this] (inst this "getCustomState"))
             :be-set-custom-state! (fn [this state] (inst this "setCustomState" state))
             :be-get-block-id (fn [this] (inst this "getBlockId"))
             :be-set-changed! (fn [this] (inst this "setChanged"))})

    (alter-var-root #'be/*be-get-level-fn* (constantly (fn [x] (inst x "getLevel"))))
    (alter-var-root #'be/*be-get-world-fn* (constantly (fn [x] (inst x "getLevel"))))
    (alter-var-root #'be/*be-get-custom-state-fn* (constantly (fn [x] (inst x "getCustomState"))))
    (alter-var-root #'be/*be-set-custom-state-fn* (constantly (fn [x s] (inst x "setCustomState" s))))
    (alter-var-root #'be/*be-get-block-id-fn* (constantly (fn [x] (inst x "getBlockId"))))
    (alter-var-root #'be/*be-set-changed-fn* (constantly (fn [x] (inst x "setChanged"))))))

(defn- install-entity-ops! []
  (let [entity-cls (class-noinit "net.minecraft.world.entity.Entity")
  player-cls (class-noinit "net.minecraft.world.entity.player.Player")
  inventory-cls (class-noinit "net.minecraft.world.entity.player.Inventory")
  menu-cls (class-noinit "net.minecraft.world.inventory.AbstractContainerMenu")]
    (extend entity-cls entity/IEntityOps
      {:entity-distance-to-sqr (fn [this x y z] (inst this "distanceToSqr" (double x) (double y) (double z)))})

    (extend player-cls entity/IEntityOps
      {:entity-distance-to-sqr (fn [this x y z] (inst this "distanceToSqr" (double x) (double y) (double z)))
       :player-get-level (fn [this] (player-level this))
       :player-creative? (fn [this] (inst this "isCreative"))
       :player-spectator? (fn [this] (inst this "isSpectator"))
       :player-get-name (fn [this] (str (inst this "getName")))
       :player-get-uuid (fn [this] (inst this "getUUID"))
       :player-get-main-hand-item-id (fn [this]
                                      (let [stack (inst this "getMainHandItem")]
                                        (when-not (stack-empty? stack)
                                          (item-key-string (inst stack "getItem")))))
       :player-get-main-hand-item-count (fn [this]
                                         (let [stack (inst this "getMainHandItem")]
                                           (if (stack-empty? stack)
                                             0
                                             (int (inst stack "getCount")))))
       :player-consume-main-hand-item! (fn [this amount]
                                         (let [n (int (max 0 (or amount 0)))]
                                           (cond
                                             (zero? n) true
                                             (boolean (inst this "isCreative")) true
                                             :else
                                             (let [stack (inst this "getMainHandItem")]
                                               (if (or (stack-empty? stack)
                                                       (< (int (inst stack "getCount")) n))
                                                 false
                                                 (do
                                                   (inst stack "shrink" n)
                                                   true))))))
       :player-count-item-by-id (fn [this item-id]
                                  (count-player-item-by-id this item-id))
       :player-consume-item-by-id! (fn [this item-id amount]
                                     (consume-player-item-by-id! this item-id amount))
       :player-give-item-stack! (fn [this stack]
                                  (give-player-item-stack this stack))
       :player-spawn-entity-by-id! (fn [this entity-id speed]
                                     (spawn-entity-by-id-from-player this entity-id speed))
       :player-raytrace-block (fn [this reach fluid-source-only?]
                                (raytrace-block-map this reach fluid-source-only?))
       :player-get-container-menu (fn [this] (field this "containerMenu"))})

    (extend inventory-cls entity/IEntityOps
      {:inventory-get-player (fn [this] (inventory-owner this))})

    (extend menu-cls entity/IEntityOps
      {:menu-get-container-id (fn [this] (field this "containerId"))})))

(defn- install-resource-factory! []
  (let [rl-cls (class-noinit "net.minecraft.resources.ResourceLocation")]
    (alter-var-root #'resource/*resource-factory*
                    (constantly (fn [namespace path]
                                  (if namespace
                                    (ctor rl-cls (str namespace) (str path))
                                    (ctor rl-cls (str path))))))))

(defn init-platform!
  "Initialize Fabric 1.20.1 platform implementations via SPI entrypoint."
  []
  (when (compare-and-set! initialized? false true)
    (install-nbt-ops!)
    (install-position-ops!)
    (install-item-ops!)
    (install-world-ops!)
    (install-be-ops!)
    (install-entity-ops!)
    (install-resource-factory!)
    (log/info "platform-impl-impl initialized via SPI entrypoint"))
  nil)
