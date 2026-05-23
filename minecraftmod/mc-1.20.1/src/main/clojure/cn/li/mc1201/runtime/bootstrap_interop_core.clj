(ns cn.li.mc1201.runtime.bootstrap-interop-core
  "Shared reflection-based Minecraft object helpers used during bootstrap.

  These functions intentionally operate on already-resolved Minecraft objects and
  use the shared reflect util. Loader-specific bootstrap shells (ServiceLoader,
  no-init timing, platform adapter assembly) stay in platform modules."
  (:require [cn.li.mc1201.reflect-util :as ru]))

(defn item-key-string [item]
  (when item
    (try
      (let [builtins-cls (ru/class-noinit "net.minecraft.core.registries.BuiltInRegistries")
            item-registry (.get (.getField builtins-cls "ITEM") nil)
            rl (ru/inst item-registry "getKey" item)]
        (when rl (str rl)))
      (catch Throwable _ nil))))

(defn block-key-string [block]
  (when block
    (try
      (let [builtins-cls (ru/class-noinit "net.minecraft.core.registries.BuiltInRegistries")
            block-registry (.get (.getField builtins-cls "BLOCK") nil)
            rl (ru/inst block-registry "getKey" block)]
        (when rl (str rl)))
      (catch Throwable _ nil))))

(defn player-level [player]
  (try
    (ru/inst player "level")
    (catch Throwable _
      (ru/field player "level"))))

(defn stack-empty? [stack]
  (ru/stack-empty? stack))

(defn- seq-field [obj fname]
  (try
    (seq (ru/field obj fname))
    (catch Throwable _ nil)))

(defn inv-items-and-offhand [player]
  (let [inv (ru/inst player "getInventory")
        items (or (seq-field inv "items") '())
        offhand (or (seq-field inv "offhand") '())]
    (concat items offhand)))

(defn count-player-item-by-id [player item-id]
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

(defn consume-player-item-by-id! [player item-id amount]
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

(defn- pos->map [pos]
  {:x (ru/inst pos "getX")
   :y (ru/inst pos "getY")
   :z (ru/inst pos "getZ")})

(defn raytrace-block-map [player reach fluid-source-only?]
  (let [r (double (or reach 5.0))]
    (try
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
            {:hit-pos (pos->map hit-pos)
             :place-pos (pos->map place-pos)
             :block-id block-id})))
      (catch Throwable _
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
                {:hit-pos (pos->map hit-pos)
                 :place-pos (pos->map place-pos)
                 :block-id block-id})))
          (catch Throwable _ nil))))))

(defn inventory-owner [inventory]
  (try
    (ru/field inventory "player")
    (catch Throwable _ nil)))

(defn world-client-side? [level]
  (try
    (boolean (ru/inst level "isClientSide"))
    (catch Throwable _
      (boolean (ru/field level "isClientSide")))))

(defn give-player-item-stack [player stack]
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

(defn drop-player-main-hand-item-at
  [player amount x y z]
  (let [n (int (max 0 (or amount 0)))]
    (cond
      (nil? player) false
      (zero? n) true
      (boolean (ru/inst player "isCreative")) true
      :else
      (try
        (let [stack (ru/player-main-hand-stack player)]
          (if (or (stack-empty? stack)
                  (< (int (ru/inst stack "getCount")) n))
            false
            (let [drop-stack (ru/inst stack "copy")
                  level (player-level player)]
              (ru/inst drop-stack "setCount" n)
              (ru/inst stack "shrink" n)
              (if (or (nil? level) (world-client-side? level))
                true
                (let [item-entity-cls (ru/class-noinit "net.minecraft.world.entity.item.ItemEntity")
                      entity (ru/ctor item-entity-cls level (double x) (double y) (double z) drop-stack)]
                  (boolean (ru/inst level "addFreshEntity" entity)))))))
        (catch Throwable _ false)))))

(defn spawn-entity-by-id-from-player [player entity-id speed]
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
