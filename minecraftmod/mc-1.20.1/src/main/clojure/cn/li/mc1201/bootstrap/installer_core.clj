(ns cn.li.mc1201.bootstrap.installer-core
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.player-feedback :as player-feedback]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.mcmod.platform.resource :as resource]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as be]
            [cn.li.mcmod.platform.capability :as platform-capability]
            [cn.li.mc1201.platform.class-access :as class-access]
            [cn.li.mc1201.platform.item-ops :as item-ops]
            [cn.li.mc1201.platform.player-ops :as player-ops]
            [cn.li.mc1201.runtime.spi.network-transport :as network-transport-spi]
            [cn.li.mc1201.platform.world-block-ops :as world-block-ops]
            [cn.li.mc1201.platform.menu-inventory-ops :as menu-inventory-ops])
  (:import [cn.li.mc1201.runtime BlockRegistryShared RuntimeAccessShared]
           [net.minecraft.core BlockPos]
           [net.minecraft.nbt CompoundTag ListTag]
           [net.minecraft.network.chat Component]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.entity Entity]
           [net.minecraft.world.entity.player Inventory Player]
           [net.minecraft.world.inventory AbstractContainerMenu]
           [net.minecraft.world.item Item ItemStack]
           [net.minecraft.world.level Level]
           [net.minecraft.world.level.block.state BlockState StateDefinition]
           [net.minecraft.world.level.block.state.properties Property]
           [net.minecraft.world.phys Vec3]))

(def ^:private ^:dynamic item-protocols-installed? false)
(def ^:private ^:dynamic pos-installed? false)
(def ^:private ^:dynamic nbt-installed? false)
(def ^:private ^:dynamic world-installed? false)
(def ^:private ^:dynamic entity-installed? false)
(def ^:private ^:dynamic resource-installed? false)
(def ^:private ^:dynamic item-factories-installed? false)
(def ^:private ^:dynamic be-fns-installed? false)
(def ^:private ^:dynamic world-fns-installed? false)
(def ^:private ^:dynamic block-state-protocols-installed? false)
(def ^:private ^:dynamic player-feedback-installed? false)

(def ^:private install-guard-lock
  (Object.))

(defn- install-once!
  [guard-var f]
  (locking install-guard-lock
    (when-not (true? (var-get guard-var))
      (alter-var-root guard-var (constantly true))
      (f)
      true)))

(defmacro ^:private install-when!
  [guard & body]
  `(install-once! (var ~guard) (fn [] ~@body)))

(declare install-item-protocols-only!)
(declare install-item-factories-only!)

(defn- world-server-session-id-value
  [^Level level]
  (when-let [sid (RuntimeAccessShared/getWorldServerSessionId level)]
    [:server sid]))

(defn- world-ops-map
  [adapter]
  {:world-get-tile-entity (fn [^Level level p] (.getBlockEntity level p))
   :world-get-block-state (fn [^Level level p] (.getBlockState level p))
   :world-set-block (fn [^Level level p s flags] (.setBlock level p s (int flags)))
   :world-remove-block (fn [^Level level p] (.destroyBlock level p false))
   :world-break-block (fn [^Level level p drop?] (.destroyBlock level p (boolean drop?)))
   :world-place-block-by-id (fn [^Level level block-id p flags]
                              (world-block-ops/world-place-block-by-id adapter level block-id p flags))
   :world-is-chunk-loaded? (fn [^Level level cx cz] (.hasChunk level (int cx) (int cz)))
   :world-get-day-time (fn [^Level level] (.getDayTime level))
   :world-get-dimension-id (fn [^Level level] (str (.location (.dimension level))))
   :world-server-session-id world-server-session-id-value
   :world-get-players (fn [^Level level] (seq (.players level)))
   :world-is-raining (fn [^Level level] (.isRaining level))
   :world-is-client-side (fn [^Level level] (.isClientSide level))
   :world-can-see-sky (fn [^Level level p] (.canSeeSky level p))})

(defn- install-player-feedback! []
  (install-when! player-feedback-installed?
    (player-feedback/install-player-feedback!
      {:send-player-feedback! (fn [player-uuid {:keys [message args translate?]}]
                                (try
                                  (when-let [^ServerPlayer player (network-transport-spi/find-player-by-uuid player-uuid)]
                                    (let [argv (object-array (mapv str (or args [])))
                                          component (if translate?
                                                      (Component/translatable (str message) argv)
                                                      (Component/literal (if (seq args)
                                                                           (apply format (str message) args)
                                                                           (str message))))]
                                      (.sendSystemMessage player component)
                                      true))
                                  (catch Throwable t
                                    (log/warn "Failed to send player feedback" player-uuid (ex-message t))
                                    false)))}
      "mc1201 player feedback")))

(defn install-block-state-protocol-only!
  "Install only BlockState protocol extensions (no Level extensions)."
  [_adapter]
  (install-when! block-state-protocols-installed?
    (extend-type BlockState
      world/IBlockStateOps
      (block-state-is-air [this] (.isAir this))
      (block-state-get-block [this] (.getBlock this))
      (block-state-get-state-definition [this]
        (.getStateDefinition (.getBlock this)))
      (block-state-get-property [_this ^StateDefinition state-def prop-name]
        (.getProperty state-def (str prop-name)))
      (block-state-set-property [this ^Property prop value]
        (.setValue this prop value)))
    (log/info "mc1201 shared block-state protocols initialized")))

(defn- install-nbt! []
  (install-when! nbt-installed?
    (extend-type CompoundTag
      nbt/INBTCompound
      (nbt-set-int! [this key value] (.putInt this (str key) (int value)) this)
      (nbt-get-int [this key] (.getInt this (str key)))
      (nbt-set-string! [this key value] (.putString this (str key) (str value)) this)
      (nbt-get-string [this key] (.getString this (str key)))
      (nbt-set-boolean! [this key value] (.putBoolean this (str key) (boolean value)) this)
      (nbt-get-boolean [this key] (.getBoolean this (str key)))
      (nbt-set-double! [this key value] (.putDouble this (str key) (double value)) this)
      (nbt-get-double [this key] (.getDouble this (str key)))
      (nbt-set-tag! [this key tag] (.put this (str key) tag) this)
      (nbt-get-tag [this key] (.get this (str key)))
      (nbt-get-compound [this key] (.getCompound this (str key)))
      (nbt-get-list [this key] (.getList this (str key) 10))
      (nbt-has-key? [this key] (.contains this (str key)))
      (nbt-set-float! [this key value] (.putFloat this (str key) (float value)) this)
      (nbt-get-float [this key] (.getFloat this (str key)))
      (nbt-set-long! [this key value] (.putLong this (str key) (long value)) this)
      (nbt-get-long [this key] (.getLong this (str key))))
    (extend-type ListTag
      nbt/INBTList
      (nbt-append! [this element] (.add this element) this)
      (nbt-list-size [this] (.size this))
      (nbt-list-get [this index]
        (let [idx (int index)
              n (int (.size this))]
          (when (and (>= idx 0) (< idx n))
            (.get this idx))))
      (nbt-list-get-compound [this index]
        (let [idx (int index)
              n (int (.size this))]
          (when (and (>= idx 0) (< idx n))
            (.getCompound this idx)))))
    (nbt/install-nbt-factory! {:create-compound #(CompoundTag.)
                               :create-list #(ListTag.)}
                              "mc1201")
    (nbt/install-nbt-has-key-fn! (fn [^CompoundTag this key] (.contains this (str key)))
                                 "mc1201")))

(defn- install-position! [_adapter]
  (install-when! pos-installed?
    (extend-type BlockPos
      pos/IBlockPos
      (pos-x [this] (.getX this))
      (pos-y [this] (.getY this))
      (pos-z [this] (.getZ this)))
    (pos/install-position-factory! (fn [x y z] (BlockPos. (int x) (int y) (int z)))
                                   "mc1201")
    (pos/install-pos-above-fn! (fn [^BlockPos p] (.above p)) "mc1201")))

(defn- install-item! [adapter]
  (install-item-protocols-only! adapter)
  (install-item-factories-only!
    (fn [nbt-tag] (item-ops/item-stack-of adapter nbt-tag))
    (fn [item-id count] (item-ops/create-item-stack-by-id adapter (str item-id) (int count)))
    (fn [stack] (item-ops/item-stack-empty? adapter stack))))

(defn install-item-protocols-only!
  "Install only item protocols for ItemStack/Item classes."
  [adapter]
  (install-when! item-protocols-installed?
    (extend-type ItemStack
      item/IItemStack
      (item-is-empty? [this] (.isEmpty this))
      (item-get-count [this] (.getCount this))
      (item-get-max-stack-size [this] (.getMaxStackSize this))
      (item-is-equal? [this other] (ItemStack/matches this other))
      (item-save-to-nbt [this n] (.save this n))
      (item-get-or-create-tag [this] (.getOrCreateTag this))
      (item-get-max-damage [this] (.getMaxDamage this))
      (item-set-damage! [this dmg] (.setDamageValue this (int dmg)))
      (item-get-damage [this] (.getDamageValue this))
      (item-get-item [this] (.getItem this))
      (item-get-tag-compound [this] (.getTag this))
      (item-split [this amount] (.split this (int amount))))
    (extend-type Item
      item/IItem
      (item-get-description-id [this] (.getDescriptionId this))
      (item-get-registry-name [this] (item-ops/item-registry-name adapter this)))
    (log/info "mc1201 shared item protocols initialized")))

(defn- install-world! [adapter]
  (install-when! world-installed?
    (install-block-state-protocol-only! adapter)
  (try
    (extend-type Level
      world/IWorldAccess
      (world-get-tile-entity [this p] (.getBlockEntity this p))
      (world-get-block-state [this p] (.getBlockState this p))
      (world-set-block [this p s flags] (.setBlock this p s (int flags)))
      (world-remove-block [this p] (.destroyBlock this p false))
      (world-break-block [this p drop?] (.destroyBlock this p (boolean drop?)))
      (world-place-block-by-id [this block-id p flags]
        (world-block-ops/world-place-block-by-id adapter this block-id p flags))
      (world-is-chunk-loaded? [this cx cz] (.hasChunk this (int cx) (int cz)))
      (world-get-day-time [this] (.getDayTime this))
      (world-get-dimension-id [this] (str (.location (.dimension this))))
      (world-server-session-id [this] (world-server-session-id-value this))
      (world-get-players [this] (seq (.players this)))
      (world-is-raining [this] (.isRaining this))
      (world-is-client-side [this] (.isClientSide this))
      (world-can-see-sky [this p] (.canSeeSky this p)))
    (catch Throwable t
      (log/warn "Skipping IWorldAccess extension for Level during bootstrap-sensitive init:" (.getMessage t))))
    (world/install-world-ops! (world-ops-map adapter) "mc1201")))

(defn install-entity-protocols-only!
  "Install only entity/player/inventory/menu protocol extensions."
  [adapter]
  (install-when! entity-installed?
    (let [block-item-info (fn [^Player this]
                            (try
                              (let [^ItemStack stack (.getMainHandItem this)]
                                (when-not (.isEmpty stack)
                                  (let [^Item item (.getItem stack)
                                        placeable? (BlockRegistryShared/isPlaceableBlockItem item)
                                        item-id (item-ops/item-registry-name adapter item)]
                                    {:placeable? placeable?
                                     :item-id item-id})))
                              (catch Throwable _
                                nil)))
          player-impl {:entity-distance-to-sqr (fn [^Entity this x y z]
                                                 (.distanceToSqr this (double x) (double y) (double z)))
                       :entity-get-x (fn [^Entity this]
                                       (let [^Vec3 pos (.position this)]
                                         (double (.x pos))))
                       :entity-get-y (fn [^Entity this]
                                       (let [^Vec3 pos (.position this)]
                                         (double (.y pos))))
                       :entity-get-z (fn [^Entity this]
                                       (let [^Vec3 pos (.position this)]
                                         (double (.z pos))))
                       :player-get-level (fn [this] (player-ops/player-level adapter this))
                       :player-creative? (fn [^Player this] (.isCreative this))
                       :player-spectator? (fn [^Player this] (.isSpectator this))
                       :player-get-name (fn [^Player this]
                                          (let [^Component name-component (.getName this)]
                                            (.getString name-component)))
                       :player-get-uuid (fn [^Entity this] (.getUUID this))
                       :player-get-main-hand-item-id (fn [^Player this]
                                                       (let [^ItemStack stack (.getMainHandItem this)]
                                                         (when-not (.isEmpty stack)
                                                           (item-ops/item-registry-name adapter (.getItem stack)))))
                       :player-get-main-hand-item-count (fn [^Player this]
                                                          (let [^ItemStack stack (.getMainHandItem this)]
                                                            (if (.isEmpty stack)
                                                              0
                                                              (int (.getCount stack)))))
                       :player-get-main-hand-item-stack (fn [^Player this]
                                                          (let [^ItemStack stack (.getMainHandItem this)]
                                                            (when-not (.isEmpty stack)
                                                              stack)))
                       :player-main-hand-placeable-block? (fn [this]
                                                           (boolean (:placeable? (block-item-info this))))
                       :player-place-main-hand-block-at-hit! (fn [^Player this _world-id x y z face]
                                                              (let [px (int x)
                                                                    py (int y)
                                                                    pz (int z)
                                                                    base-result {:placed? false
                                                                                 :fallback-drop? true
                                                                                 :pos {:x px :y py :z pz}
                                                                                 :face face}]
                                                                (try
                                                                  (if-let [{:keys [placeable? item-id]} (block-item-info this)]
                                                                    (if-not (and placeable? (seq item-id))
                                                                      base-result
                                                                      (let [level (player-ops/player-level adapter this)
                                                                            ^BlockPos pos (BlockPos. px py pz)
                                                                            placed? (boolean (world-block-ops/world-place-block-by-id adapter level item-id pos 3))]
                                                                        (assoc base-result
                                                                               :placed? placed?
                                                                               :fallback-drop? (not placed?))))
                                                                    base-result)
                                                                  (catch Throwable _
                                                                    base-result))))
                       :player-consume-main-hand-item! (fn [^Player this amount]
                                                         (let [n (int (max 0 (or amount 0)))]
                                                           (cond
                                                             (zero? n) true
                                                             (.isCreative this) true
                                                             :else
                                                             (let [^ItemStack stack (.getMainHandItem this)]
                                                               (if (or (.isEmpty stack)
                                                                       (< (int (.getCount stack)) n))
                                                                 false
                                                                 (do
                                                                   (.shrink stack n)
                                                                   true))))))
                       :player-drop-main-hand-item-at! (fn [this amount x y z]
                                                         (player-ops/drop-player-main-hand-item-at! adapter this amount x y z))
                       :player-count-item-by-id (fn [this item-id] (player-ops/count-player-item-by-id adapter this item-id))
                       :player-consume-item-by-id! (fn [this item-id amount] (player-ops/consume-player-item-by-id! adapter this item-id amount))
                       :player-give-item-stack! (fn [this stack] (player-ops/give-player-item-stack! adapter this stack))
                       :player-spawn-entity-by-id! (fn [this entity-id speed] (player-ops/spawn-entity-by-id! adapter this entity-id speed))
                       :player-raytrace-block (fn [this reach fluid-source-only?] (player-ops/raytrace-block adapter this reach fluid-source-only?))
                       :player-get-container-menu (fn [this] (player-ops/player-container-menu adapter this))}]
      (extend-type Entity
        entity/IEntityOps
        (entity-distance-to-sqr [this x y z]
          (.distanceToSqr ^Entity this (double x) (double y) (double z)))
        (entity-get-x [this]
          (let [^Vec3 pos (.position ^Entity this)]
            (double (.x pos))))
        (entity-get-y [this]
          (let [^Vec3 pos (.position ^Entity this)]
            (double (.y pos))))
        (entity-get-z [this]
          (let [^Vec3 pos (.position ^Entity this)]
            (double (.z pos)))))
      (extend Player entity/IEntityOps player-impl)
      (extend ServerPlayer entity/IEntityOps player-impl)
      (when-let [local-player-cls (class-access/local-player-class adapter)]
        (extend local-player-cls entity/IEntityOps player-impl))
      (extend Inventory entity/IEntityOps
        {:inventory-get-player (fn [this] (menu-inventory-ops/inventory-owner adapter this))})
      (extend AbstractContainerMenu entity/IEntityOps
        {:menu-get-container-id (fn [this] (menu-inventory-ops/menu-container-id adapter this))})
      (log/info "mc1201 shared entity protocols initialized"))))

(defn- install-resource-factory! []
  (install-when! resource-installed?
    (resource/install-resource-factory!
      (fn [namespace path]
        (if namespace
          (ResourceLocation. (str namespace) (str path))
          (ResourceLocation. (str path))))
      "mc1201")))

(defn install-resource-factory-only!
  "Install only the shared resource location factory.

  Kept separate for incremental platform migration."
  []
  (install-resource-factory!)
  (log/info "mc1201 shared resource factory initialized"))

(defn install-item-factories-only!
  "Install only item var-root factories, without protocol extensions.

  This is bootstrap-safe and useful for incremental migration on sensitive loaders."
  [item-stack-of-fn create-item-stack-by-id-fn item-stack-empty?-fn]
  (install-when! item-factories-installed?
    (item/install-item-factories!
      {:item-factory (fn [nbt-tag] (item-stack-of-fn nbt-tag))
       :item-stack-resolver (fn [item-id count]
                              (let [stack (create-item-stack-by-id-fn (str item-id) (int count))]
                                (when (and stack (not (item-stack-empty?-fn stack)))
                                  stack)))
       :item-tag-checker (fn [stack tag-str]
                           (try
                             (let [tag-str (str tag-str)
                                   colon (.indexOf tag-str ":")
                                   ns (if (pos? colon) (.substring tag-str 0 (int colon)) "minecraft")
                                   path (if (pos? colon) (.substring tag-str (inc (int colon))) tag-str)
                                   rl (ResourceLocation. ns path)
                                   tag-key (net.minecraft.tags.TagKey/create
                                            net.minecraft.core.registries.Registries/ITEM rl)]
                               (boolean (.is ^ItemStack stack tag-key)))
                             (catch Throwable _ false)))
       :tag-item-resolver (fn [tag-str count]
                           (try
                             (let [tag-str (str tag-str)
                                   colon (.indexOf tag-str ":")
                                   ns (if (pos? colon) (.substring tag-str 0 (int colon)) "minecraft")
                                   path (if (pos? colon) (.substring tag-str (inc (int colon))) tag-str)
                                   rl (ResourceLocation. ns path)
                                   tag-key (net.minecraft.tags.TagKey/create
                                            net.minecraft.core.registries.Registries/ITEM rl)
                                   items (.iterator (.getTagOrEmpty net.minecraft.core.registries.BuiltInRegistries/ITEM tag-key))]
                               (when (.hasNext items)
                                 (let [^Item item (.value ^net.minecraft.core.Holder (.next items))
                                       stack (ItemStack. item (int count))]
                                   stack)))
                             (catch Throwable _ nil)))}
      "mc1201")
    (log/info "mc1201 shared item factories initialized")))

(defn install-be-fns-only!
  "Install only block-entity related var-root function hooks.

  `fns-map` keys:
  :be-get-level, :be-get-world, :be-get-custom-state,
  :be-set-custom-state!, :be-get-block-id, :be-set-changed!"
  [fns-map]
  (install-when! be-fns-installed?
    (be/install-be-ops! fns-map "mc1201")
    (log/info "mc1201 shared block-entity function hooks initialized")))

(defn install-world-fns-only!
  "Install only world-related var-root function hooks.

  `fns-map` keys:
  :world-get-tile-entity, :world-get-block-state, :world-set-block,
  :world-remove-block, :world-break-block, :world-place-block-by-id,
  :world-is-chunk-loaded?, :world-get-day-time, :world-get-dimension-id,
  :world-server-session-id, :world-get-players, :world-is-raining,
  :world-is-client-side, :world-can-see-sky"
  [fns-map]
  (install-when! world-fns-installed?
    (world/install-world-ops! fns-map "mc1201")
    (log/info "mc1201 shared world function hooks initialized")))

(defn install-platform-core!
  [adapter]
  (install-nbt!)
  (install-position! adapter)
  (install-item! adapter)
  (install-world! adapter)
  (install-entity-protocols-only! adapter)
  (install-player-feedback!)
  (install-resource-factory!)
  (log/info "mc1201 shared installer initialized"))

(defn install-foundation!
  "Install bootstrap-safe shared foundations only (NBT + position).

  Useful for incremental platform migration where entity/world/item logic
  still stays in platform-specific code."
  ([] (install-foundation! nil))
  ([adapter]
   (install-nbt!)
   (install-position! adapter)
   (log/info "mc1201 foundation installer initialized")))
