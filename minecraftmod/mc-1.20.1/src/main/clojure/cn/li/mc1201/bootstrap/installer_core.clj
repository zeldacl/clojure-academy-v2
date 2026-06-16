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
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.platform.capability :as platform-capability]
            [cn.li.mc1201.reflect-util :as ru]
            [cn.li.mc1201.platform.class-access :as class-access]
            [cn.li.mc1201.platform.item-ops :as item-ops]
            [cn.li.mc1201.platform.player-ops :as player-ops]
            [cn.li.mc1201.runtime.spi.network-transport :as network-transport-spi]
            [cn.li.mc1201.platform.world-block-ops :as world-block-ops]
            [cn.li.mc1201.platform.menu-inventory-ops :as menu-inventory-ops])
  (:import [net.minecraft.network.chat Component]
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

(defn- install-player-feedback! []
  (install-when! player-feedback-installed?
    (player-feedback/install-player-feedback!
      (reify player-feedback/IPlayerFeedback
        (send-player-feedback! [_ player-uuid {:keys [message args translate?]}]
          (try
            (when-let [^net.minecraft.server.level.ServerPlayer player (network-transport-spi/find-player-by-uuid player-uuid)]
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
              false))))
      "mc1201 player feedback")))

(defn install-block-state-protocol-only!
  "Install only BlockState protocol extensions (no Level extensions)."
  [adapter]
  (install-when! block-state-protocols-installed?
    (let [block-state-cls (class-access/block-state-class adapter)]
      (extend block-state-cls world/IBlockStateOps
              {:block-state-is-air (fn [this] (ru/inst this "isAir"))
               :block-state-get-block (fn [this] (ru/inst this "getBlock"))
               :block-state-get-state-definition (fn [this] (ru/inst (ru/inst this "getBlock") "getStateDefinition"))
               :block-state-get-property (fn [_this state-def prop-name] (ru/inst state-def "getProperty" prop-name))
               :block-state-set-property (fn [this prop value] (ru/inst this "setValue" prop value))}))
    (log/info "mc1201 shared block-state protocols initialized")))

(defn- install-nbt! []
  (install-when! nbt-installed?
    (let [compound-tag-cls (ru/class-noinit "net.minecraft.nbt.CompoundTag")
          list-tag-cls (ru/class-noinit "net.minecraft.nbt.ListTag")]
      (extend compound-tag-cls nbt/INBTCompound
              {:nbt-set-int! (fn [this key value] (ru/inst this "putInt" key (int value)) this)
               :nbt-get-int (fn [this key] (ru/inst this "getInt" key))
               :nbt-set-string! (fn [this key value] (ru/inst this "putString" key (str value)) this)
               :nbt-get-string (fn [this key] (ru/inst this "getString" key))
               :nbt-set-boolean! (fn [this key value] (ru/inst this "putBoolean" key (boolean value)) this)
               :nbt-get-boolean (fn [this key] (ru/inst this "getBoolean" key))
               :nbt-set-double! (fn [this key value] (ru/inst this "putDouble" key (double value)) this)
               :nbt-get-double (fn [this key] (ru/inst this "getDouble" key))
               :nbt-set-tag! (fn [this key tag] (ru/inst this "put" key tag) this)
               :nbt-get-tag (fn [this key] (ru/inst this "get" key))
               :nbt-get-compound (fn [this key] (ru/inst this "getCompound" key))
               :nbt-get-list (fn [this key] (ru/inst this "getList" key 10))
               :nbt-has-key? (fn [this key] (ru/inst this "contains" key))
               :nbt-set-float! (fn [this key value] (ru/inst this "putFloat" key (float value)) this)
               :nbt-get-float (fn [this key] (ru/inst this "getFloat" key))
               :nbt-set-long! (fn [this key value] (ru/inst this "putLong" key (long value)) this)
               :nbt-get-long (fn [this key] (ru/inst this "getLong" key))})
      (extend list-tag-cls nbt/INBTList
              {:nbt-append! (fn [this element] (ru/inst this "add" element) this)
               :nbt-list-size (fn [this] (ru/inst this "size"))
               :nbt-list-get (fn [this index]
                               (let [idx (int index)
                                     n (int (ru/inst this "size"))]
                                 (when (and (>= idx 0) (< idx n))
                                   (ru/inst this "get" idx))))
               :nbt-list-get-compound (fn [this index]
                                        (let [idx (int index)
                                              n (int (ru/inst this "size"))]
                                          (when (and (>= idx 0) (< idx n))
                                            (ru/inst this "getCompound" idx))))})
      (nbt/install-nbt-factory! {:create-compound #(ru/ctor compound-tag-cls)
                                 :create-list #(ru/ctor list-tag-cls)}
                                "mc1201")
      (nbt/install-nbt-has-key-fn! (fn [this key] (ru/inst this "contains" key))
                                   "mc1201")
      (tile-logic/install-capability-get-factory! platform-capability/get-handler-factory
                                                  "mc1201"))))

(defn- install-position! [_adapter]
  (install-when! pos-installed?
    (let [block-pos-cls (ru/class-noinit "net.minecraft.core.BlockPos")]
      (extend block-pos-cls pos/IBlockPos
              {:pos-x (fn [this] (ru/inst this "getX"))
               :pos-y (fn [this] (ru/inst this "getY"))
               :pos-z (fn [this] (ru/inst this "getZ"))})
      (pos/install-position-factory! (fn [x y z] (ru/ctor block-pos-cls (int x) (int y) (int z)))
                                     "mc1201")
      (pos/install-pos-above-fn! (fn [p] (ru/inst p "above")) "mc1201"))))

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
      (let [item-stack-cls (class-access/item-stack-class adapter)
        item-cls (class-access/item-class adapter)]
      (extend item-stack-cls item/IItemStack
              {:item-is-empty? (fn [this] (ru/inst this "isEmpty"))
               :item-get-count (fn [this] (ru/inst this "getCount"))
               :item-get-max-stack-size (fn [this] (ru/inst this "getMaxStackSize"))
               :item-is-equal? (fn [this other] (ru/static item-stack-cls "matches" this other))
               :item-save-to-nbt (fn [this n] (ru/inst this "save" n))
               :item-get-or-create-tag (fn [this] (ru/inst this "getOrCreateTag"))
               :item-get-max-damage (fn [this] (ru/inst this "getMaxDamage"))
               :item-set-damage! (fn [this dmg] (ru/inst this "setDamageValue" (int dmg)))
               :item-get-damage (fn [this] (ru/inst this "getDamageValue"))
               :item-get-item (fn [this] (ru/inst this "getItem"))
               :item-get-tag-compound (fn [this] (ru/inst this "getTag"))
               :item-split (fn [this amount] (ru/inst this "split" (int amount)))})
      (extend item-cls item/IItem
              {:item-get-description-id (fn [this] (ru/inst this "getDescriptionId"))
               :item-get-registry-name (fn [this] (item-ops/item-registry-name adapter this))})
      (log/info "mc1201 shared item protocols initialized"))))

(defn- install-world! [adapter]
  (install-when! world-installed?
    (let [level-cls (class-access/level-class adapter)]
      (install-block-state-protocol-only! adapter)
      (try
        (extend level-cls world/IWorldAccess
                {:world-get-tile-entity (fn [this p] (ru/inst this "getBlockEntity" p))
                 :world-get-block-state (fn [this p] (ru/inst this "getBlockState" p))
                 :world-set-block (fn [this p s flags] (ru/inst this "setBlock" p s (int flags)))
                 :world-remove-block (fn [this p] (ru/inst this "destroyBlock" p false))
                 :world-break-block (fn [this p drop?] (ru/inst this "destroyBlock" p (boolean drop?)))
                 :world-place-block-by-id (fn [this block-id p flags] (world-block-ops/world-place-block-by-id adapter this block-id p flags))
                 :world-is-chunk-loaded? (fn [this cx cz] (ru/inst this "hasChunk" (int cx) (int cz)))
                 :world-get-day-time (fn [this] (ru/inst this "getDayTime"))
                 :world-get-dimension-id (fn [this] (str (ru/inst (ru/inst this "dimension") "location")))
                 :world-get-players (fn [this] (seq (ru/inst this "players")))
                 :world-is-raining (fn [this] (ru/inst this "isRaining"))
                 :world-is-client-side (fn [this]
                                         (try
                                           (ru/inst this "isClientSide")
                                           (catch Throwable _
                                             (ru/field this "isClientSide"))))
                 :world-can-see-sky (fn [this p] (ru/inst this "canSeeSky" p))})
        (catch Throwable t
          (log/warn "Skipping IWorldAccess extension for" level-cls "during bootstrap-sensitive init:" (.getMessage t))))
      (world/install-world-ops!
        {:world-get-tile-entity (fn [level p] (ru/inst level "getBlockEntity" p))
         :world-get-block-state (fn [level p] (ru/inst level "getBlockState" p))
         :world-set-block (fn [level p s flags] (ru/inst level "setBlock" p s (int flags)))
         :world-remove-block (fn [level p] (ru/inst level "destroyBlock" p false))
         :world-break-block (fn [level p drop?] (ru/inst level "destroyBlock" p (boolean drop?)))
         :world-place-block-by-id (fn [level block-id p flags]
                                    (world-block-ops/world-place-block-by-id adapter level block-id p flags))
         :world-is-chunk-loaded? (fn [level cx cz] (ru/inst level "hasChunk" (int cx) (int cz)))
         :world-get-day-time (fn [level] (ru/inst level "getDayTime"))
         :world-get-dimension-id (fn [level] (str (ru/inst (ru/inst level "dimension") "location")))
         :world-get-players (fn [level] (seq (ru/inst level "players")))
         :world-is-raining (fn [level] (ru/inst level "isRaining"))
         :world-is-client-side (fn [level]
                                 (try
                                   (ru/inst level "isClientSide")
                                   (catch Throwable _
                                     (ru/field level "isClientSide"))))
         :world-can-see-sky (fn [level p] (ru/inst level "canSeeSky" p))}
        "mc1201"))))

(defn install-entity-protocols-only!
  "Install only entity/player/inventory/menu protocol extensions."
  [adapter]
  (install-when! entity-installed?
    (let [entity-cls (class-access/entity-class adapter)
          player-cls (class-access/player-class adapter)
          server-player-cls (class-access/server-player-class adapter)
          inventory-cls (class-access/inventory-class adapter)
          menu-cls (class-access/menu-class adapter)
          block-cls (ru/class-noinit "net.minecraft.world.level.block.Block")
          blocks-cls (ru/class-noinit "net.minecraft.world.level.block.Blocks")
          block-pos-cls (ru/class-noinit "net.minecraft.core.BlockPos")
          air-block (.get (.getField blocks-cls "AIR") nil)
          block-item-info (fn [this]
                            (try
                              (let [stack (ru/player-main-hand-stack this)]
                                (when-not (ru/stack-empty? stack)
                                  (let [item (ru/inst stack "getItem")
                                        block (ru/static block-cls "byItem" item)
                                        placeable? (boolean (and block (not= block air-block)))
                                        item-id (item-ops/item-registry-name adapter item)]
                                    {:placeable? placeable?
                                     :item-id item-id})))
                              (catch Throwable _
                                nil)))
          player-impl {:entity-distance-to-sqr (fn [this x y z]
                                                 (ru/inst this "distanceToSqr" (double x) (double y) (double z)))
                       :entity-get-x (fn [this]
                                       (let [^Vec3 pos (ru/inst this "position")]
                                         (double (.-x pos))))
                       :entity-get-y (fn [this]
                                       (let [^Vec3 pos (ru/inst this "position")]
                                         (double (.-y pos))))
                       :entity-get-z (fn [this]
                                       (let [^Vec3 pos (ru/inst this "position")]
                                         (double (.-z pos))))
                       :player-get-level (fn [this] (player-ops/player-level adapter this))
                       :player-creative? (fn [this] (ru/inst this "isCreative"))
                       :player-spectator? (fn [this] (ru/inst this "isSpectator"))
                       :player-get-name (fn [this]
                                          (let [^Component name-component (ru/inst this "getName")]
                                            (.getString name-component)))
                       :player-get-uuid (fn [this] (ru/inst this "getUUID"))
                       :player-get-main-hand-item-id (fn [this]
                                                       (let [stack (ru/player-main-hand-stack this)]
                                                         (when-not (ru/stack-empty? stack)
                                                           (item-ops/item-registry-name adapter (ru/inst stack "getItem")))))
                       :player-get-main-hand-item-count (fn [this]
                                                          (let [stack (ru/player-main-hand-stack this)]
                                                            (if (ru/stack-empty? stack)
                                                              0
                                                              (int (ru/inst stack "getCount")))))
                       :player-get-main-hand-item-stack (fn [this]
                                                          (let [stack (ru/player-main-hand-stack this)]
                                                            (when-not (ru/stack-empty? stack)
                                                              stack)))
                       :player-main-hand-placeable-block? (fn [this]
                                                           (boolean (:placeable? (block-item-info this))))
                           :player-place-main-hand-block-at-hit! (fn [this _world-id x y z face]
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
                                                                            pos (ru/ctor block-pos-cls px py pz)
                                                                            placed? (boolean (world-block-ops/world-place-block-by-id adapter level item-id pos 3))]
                                                                        (assoc base-result
                                                                               :placed? placed?
                                                                               :fallback-drop? (not placed?))))
                                                                    base-result)
                                                                  (catch Throwable _
                                                                    base-result))))
                       :player-consume-main-hand-item! (fn [this amount]
                                                         (let [n (int (max 0 (or amount 0)))]
                                                           (cond
                                                             (zero? n) true
                                                             (boolean (ru/inst this "isCreative")) true
                                                             :else
                                                             (let [stack (ru/player-main-hand-stack this)]
                                                               (if (or (ru/stack-empty? stack)
                                                                       (< (int (ru/inst stack "getCount")) n))
                                                                 false
                                                                 (do
                                                                   (ru/inst stack "shrink" n)
                                                                   true))))))
                       :player-drop-main-hand-item-at! (fn [this amount x y z]
                                                         (player-ops/drop-player-main-hand-item-at! adapter this amount x y z))
                       :player-count-item-by-id (fn [this item-id] (player-ops/count-player-item-by-id adapter this item-id))
                       :player-consume-item-by-id! (fn [this item-id amount] (player-ops/consume-player-item-by-id! adapter this item-id amount))
                       :player-give-item-stack! (fn [this stack] (player-ops/give-player-item-stack! adapter this stack))
                       :player-spawn-entity-by-id! (fn [this entity-id speed] (player-ops/spawn-entity-by-id! adapter this entity-id speed))
                       :player-raytrace-block (fn [this reach fluid-source-only?] (player-ops/raytrace-block adapter this reach fluid-source-only?))
                       :player-get-container-menu (fn [this] (player-ops/player-container-menu adapter this))}]
      (extend entity-cls entity/IEntityOps
              {:entity-distance-to-sqr (fn [this x y z]
                                         (ru/inst this "distanceToSqr" (double x) (double y) (double z)))
               :entity-get-x (fn [this]
                               (let [^Vec3 pos (ru/inst this "position")]
                                 (double (.-x pos))))
               :entity-get-y (fn [this]
                               (let [^Vec3 pos (ru/inst this "position")]
                                 (double (.-y pos))))
               :entity-get-z (fn [this]
                               (let [^Vec3 pos (ru/inst this "position")]
                                 (double (.-z pos))))})
      (extend player-cls entity/IEntityOps player-impl)
      (when server-player-cls
        (extend server-player-cls entity/IEntityOps player-impl))
            (when-let [local-player-cls (class-access/local-player-class adapter)]
        (extend local-player-cls entity/IEntityOps player-impl))
      (extend inventory-cls entity/IEntityOps
              {:inventory-get-player (fn [this] (menu-inventory-ops/inventory-owner adapter this))})
      (extend menu-cls entity/IEntityOps
              {:menu-get-container-id (fn [this] (menu-inventory-ops/menu-container-id adapter this))})
      (log/info "mc1201 shared entity protocols initialized"))))

(defn- install-resource-factory! []
  (install-when! resource-installed?
    (let [rl-cls (ru/class-noinit "net.minecraft.resources.ResourceLocation")]
      (resource/install-resource-factory!
        (fn [namespace path]
          (if namespace
            (ru/ctor rl-cls (str namespace) (str path))
            (ru/ctor rl-cls (str path))))
        "mc1201"))))

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
                                   rl (net.minecraft.resources.ResourceLocation. ns path)
                                   tag-key (net.minecraft.tags.TagKey/create
                                            net.minecraft.core.registries.Registries/ITEM rl)]
                               (boolean (.is ^net.minecraft.world.item.ItemStack stack tag-key)))
                             (catch Throwable _ false)))
       :tag-item-resolver (fn [tag-str count]
                           (try
                             (let [tag-str (str tag-str)
                                   colon (.indexOf tag-str ":")
                                   ns (if (pos? colon) (.substring tag-str 0 (int colon)) "minecraft")
                                   path (if (pos? colon) (.substring tag-str (inc (int colon))) tag-str)
                                   rl (net.minecraft.resources.ResourceLocation. ns path)
                                   tag-key (net.minecraft.tags.TagKey/create
                                            net.minecraft.core.registries.Registries/ITEM rl)
                                   items (.iterator (.getTagOrEmpty net.minecraft.core.registries.BuiltInRegistries/ITEM tag-key))]
                               (when (.hasNext items)
                                 (let [^net.minecraft.world.item.Item item (.value ^net.minecraft.core.Holder (.next items))
                                       stack (net.minecraft.world.item.ItemStack. item (int count))]
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
  :world-get-players, :world-is-raining, :world-is-client-side, :world-can-see-sky"
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
