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
           [net.minecraft.world.level.block.entity BlockEntity]
           [net.minecraft.world.phys Vec3]))

(def ^:private ^:dynamic item-protocols-installed? false)
(def ^:private ^:dynamic pos-installed? false)
(def ^:private ^:dynamic nbt-installed? false)
(def ^:private ^:dynamic world-installed? false)
(def ^:private ^:dynamic entity-installed? false)
(def ^:private ^:dynamic resource-installed? false)

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
   :world-get-game-time (fn [^Level level] (.getGameTime level))
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
  "Install BlockState interop functions into the world-ops Framework map.
   (BlockState ops live in [:platform :world-ops] alongside world ops.)"
  [_adapter]
  (install-when! block-state-protocols-installed?
    (let [current (world/current-ops)
          bs-ops {:block-state-is-air               (fn [^BlockState this] (.isAir this))
                  :block-state-get-block            (fn [^BlockState this] (.getBlock this))
                  :block-state-get-state-definition (fn [^BlockState this] (.getStateDefinition (.getBlock this)))
                  :block-state-get-property         (fn [_this ^StateDefinition state-def prop-name]
                                                      (.getProperty state-def (str prop-name)))
                  :block-state-set-property         (fn [^BlockState this ^Property prop value]
                                                      (.setValue this prop value))}]
      (world/install-block-state-ops! bs-ops "mc1201 block-state"))
    (log/info "mc1201 block-state ops initialized")))

(defn- install-nbt! []
  (install-when! nbt-installed?
    (nbt/install-nbt-ops!
      {:nbt-set-int!      (fn [^CompoundTag this key value] (.putInt this (str key) (int value)) this)
       :nbt-get-int       (fn [^CompoundTag this key] (.getInt this (str key)))
       :nbt-set-string!   (fn [^CompoundTag this key value] (.putString this (str key) (str value)) this)
       :nbt-get-string    (fn [^CompoundTag this key] (.getString this (str key)))
       :nbt-set-boolean!  (fn [^CompoundTag this key value] (.putBoolean this (str key) (boolean value)) this)
       :nbt-get-boolean   (fn [^CompoundTag this key] (.getBoolean this (str key)))
       :nbt-set-double!   (fn [^CompoundTag this key value] (.putDouble this (str key) (double value)) this)
       :nbt-get-double    (fn [^CompoundTag this key] (.getDouble this (str key)))
       :nbt-set-tag!      (fn [^CompoundTag this key tag] (.put this (str key) tag) this)
       :nbt-get-tag       (fn [^CompoundTag this key] (.get this (str key)))
       :nbt-get-compound  (fn [^CompoundTag this key] (.getCompound this (str key)))
       :nbt-get-list      (fn [^CompoundTag this key] (.getList this (str key) 10))
       :nbt-has-key?      (fn [^CompoundTag this key] (.contains this (str key)))
       :nbt-set-float!    (fn [^CompoundTag this key value] (.putFloat this (str key) (float value)) this)
       :nbt-get-float     (fn [^CompoundTag this key] (.getFloat this (str key)))
       :nbt-set-long!     (fn [^CompoundTag this key value] (.putLong this (str key) (long value)) this)
       :nbt-get-long      (fn [^CompoundTag this key] (.getLong this (str key)))
       :nbt-append!       (fn [^ListTag this el] (.add this el) this)
       :nbt-list-size     (fn [^ListTag this] (.size this))
       :nbt-list-get      (fn [^ListTag this idx]
                            (let [i (int idx) n (int (.size this))]
                              (when (and (>= i 0) (< i n)) (.get this i))))
       :nbt-list-get-compound (fn [^ListTag this idx]
                                 (let [i (int idx) n (int (.size this))]
                                   (when (and (>= i 0) (< i n)) (.getCompound this i))))
       :create-compound   #(CompoundTag.)
       :create-list       #(ListTag.)}
      "mc1201")))

(defn- install-position! [_adapter]
  (install-when! pos-installed?
    (pos/install-position-ops!
      {:pos-x (fn [^BlockPos this] (.getX this))
       :pos-y (fn [^BlockPos this] (.getY this))
       :pos-z (fn [^BlockPos this] (.getZ this))
       :create-block-pos (fn [x y z] (BlockPos. (int x) (int y) (int z)))
       :pos-above (fn [^BlockPos p] (.above p))
       :position-get-block-pos (fn [^BlockEntity be] (.getBlockPos be))
       :position-get-pos (fn [^BlockEntity be] (.getBlockPos be))}
      "mc1201")))

(defn- install-item! [adapter]
  (install-item-protocols-only! adapter))

(defn install-item-factories-only!
  "Backward-compatible no-op. Item factories are now installed by install-item-protocols-only!."
  [_item-stack-of-fn _create-item-stack-by-id-fn _item-stack-empty?-fn]
  ;; Factories are already installed via install-item-protocols-only!
  ;; which is called before this by install-platform-core! and platform-init.
  nil)

(defn install-item-protocols-only!
  "Install ItemStack/Item interop functions into Framework [:platform :item-ops]."
  [adapter]
  (install-when! item-protocols-installed?
    (item/install-item-ops!
     {:item-is-empty?          (fn [^ItemStack this] (.isEmpty this))
      :item-get-count          (fn [^ItemStack this] (.getCount this))
      :item-get-max-stack-size (fn [^ItemStack this] (.getMaxStackSize this))
      :item-is-equal?          (fn [^ItemStack this ^ItemStack other]
                                (.is this (.getItem other)))
      :item-save-to-nbt        (fn [^ItemStack this nbt] (.save this nbt))
      :item-get-or-create-tag  (fn [^ItemStack this] (.getOrCreateTag this))
      :item-get-max-damage     (fn [^ItemStack this] (.getMaxDamage this))
      :item-set-damage!        (fn [^ItemStack this dmg] (.setDamageValue this (int dmg)))
      :item-get-damage         (fn [^ItemStack this] (.getDamageValue this))
      :item-get-item           (fn [^ItemStack this] (.getItem this))
      :item-get-tag-compound   (fn [^ItemStack this] (.getTag this))
      :item-split              (fn [^ItemStack this amount] (.split this (int amount)))
      :item-get-description-id (fn [^Item this] (.getDescriptionId this))
      :item-get-registry-name  (fn [^Item this] (item-ops/item-registry-name adapter this))
      :create-item-from-nbt    (fn [nbt-tag] (item-ops/item-stack-of adapter nbt-tag))
      :create-item-stack-by-id (fn [item-id count] (item-ops/create-item-stack-by-id adapter (str item-id) (int count)))
      :item-stack-empty?       (fn [stack] (item-ops/item-stack-empty? adapter stack))
      :item-tag-checker        (fn [^ItemStack stack tag-str]
                                 (try
                                   (let [tag-str (str tag-str)
                                         colon (.indexOf tag-str ":")
                                         ns (if (pos? colon) (.substring tag-str 0 (int colon)) "minecraft")
                                         path (if (pos? colon) (.substring tag-str (inc (int colon))) tag-str)
                                         rl (ResourceLocation. ns path)
                                         tag-key (net.minecraft.tags.TagKey/create
                                                  net.minecraft.core.registries.Registries/ITEM rl)]
                                     (boolean (.is stack tag-key)))
                                   (catch Throwable _ false)))
      :tag-item-resolver       (fn [tag-str count]
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
    (log/info "mc1201 shared item ops initialized")))

(defn- install-world! [adapter]
  (install-when! world-installed?
    (world/install-world-ops! (world-ops-map adapter) "mc1201")))

(defn install-entity-protocols-only!
  "Install entity/player/inventory/menu operation fns into Framework [:platform :entity-ops].
  Simple interop methods (entity-get-x, player-creative?, etc.) are now plain defn in entity.clj."
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
          player-impl {:entity-distance-to-sqr (fn [^Entity this x y z] (.distanceToSqr this (double x) (double y) (double z)))
                       :entity-get-x           (fn [^Entity this] (let [^Vec3 pos (.position this)] (double (.x pos))))
                       :entity-get-y           (fn [^Entity this] (let [^Vec3 pos (.position this)] (double (.y pos))))
                       :entity-get-z           (fn [^Entity this] (let [^Vec3 pos (.position this)] (double (.z pos))))
                       :player-creative?       (fn [^Player this] (.isCreative this))
                       :player-spectator?      (fn [^Player this] (.isSpectator this))
                       :player-get-name        (fn [^Player this] (let [^Component nc (.getName this)] (.getString nc)))
                       :player-get-uuid        (fn [^Entity this] (.getUUID this))
                       :player-get-main-hand-item-count (fn [^Player this]
                                                          (let [^ItemStack stack (.getMainHandItem this)]
                                                            (if (.isEmpty stack) 0 (int (.getCount stack)))))
                       :player-get-main-hand-item-stack (fn [^Player this]
                                                          (let [^ItemStack stack (.getMainHandItem this)]
                                                            (when-not (.isEmpty stack) stack)))
                       :player-consume-main-hand-item! (fn [^Player this amount]
                                                         (let [n (int (max 0 (or amount 0)))]
                                                           (cond (zero? n) true
                                                                 (.isCreative this) true
                                                                 :else
                                                                 (let [^ItemStack stack (.getMainHandItem this)]
                                                                   (if (or (.isEmpty stack) (< (int (.getCount stack)) n))
                                                                     false
                                                                     (do (.shrink stack n) true))))))
                       :player-get-level (fn [this] (player-ops/player-level adapter this))
                       :player-get-main-hand-item-id (fn [^Player this]
                                                       (let [^ItemStack stack (.getMainHandItem this)]
                                                         (when-not (.isEmpty stack)
                                                           (item-ops/item-registry-name adapter (.getItem stack)))))
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
                       :player-drop-main-hand-item-at! (fn [this amount x y z]
                                                         (player-ops/drop-player-main-hand-item-at! adapter this amount x y z))
                       :player-count-item-by-id (fn [this item-id] (player-ops/count-player-item-by-id adapter this item-id))
                       :player-consume-item-by-id! (fn [this item-id amount] (player-ops/consume-player-item-by-id! adapter this item-id amount))
                       :player-give-item-stack! (fn [this stack] (player-ops/give-player-item-stack! adapter this stack))
                       :player-spawn-entity-by-id! (fn [this entity-id speed] (player-ops/spawn-entity-by-id! adapter this entity-id speed))
                       :player-raytrace-block (fn [this reach fluid-source-only?] (player-ops/raytrace-block adapter this reach fluid-source-only?))
                       :player-get-container-menu (fn [this] (player-ops/player-container-menu adapter this))
                       :inventory-get-player (fn [this] (menu-inventory-ops/inventory-owner adapter this))
                       :menu-get-container-id (fn [this] (menu-inventory-ops/menu-container-id adapter this))}]
      (entity/install-entity-ops! player-impl "mc1201")
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

(defn install-be-fns-only!
  "Install only block-entity related var-root function hooks.

  `fns-map` keys:
  :be-get-level, :be-get-world, :be-get-custom-state,
  :be-set-custom-state!, :be-get-block-id, :be-set-changed!, :be-sync-to-client!"
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
