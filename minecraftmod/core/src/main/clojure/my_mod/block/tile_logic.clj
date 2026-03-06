(ns my-mod.block.tile-logic
  "Registry and dispatch for scripted block entity logic (tick + NBT).

  Used by generic ScriptedBlockEntity: Java calls invoke-tick, read-nbt, write-nbt;
  this namespace looks up the block-id's registered logic and runs it.
  Platform must ensure this namespace is loaded before any scripted BE ticks.")
  (:require [my-mod.util.log :as log]))

(defonce tile-logic-registry (atom {}))

(defn register-tile-logic!
  "Register tick and NBT logic for a scripted block.

  cfg: {:tick-fn (fn [level pos state be] ...)   ; side-effect: update be via setters
        :read-nbt-fn (fn [tag] -> data map)     ; tag is INBTCompound; return {:energy _ :battery _ ...}
        :write-nbt-fn (fn [be tag] ...)}       ; write be state to tag using nbt-set-*!
  be for tick-fn and write-nbt-fn is the ScriptedBlockEntity (getEnergy, setEnergy, etc.)."
  [block-id cfg]
  (when (nil? (:tick-fn cfg))
    (throw (ex-info "register-tile-logic!: :tick-fn required" {:block-id block-id})))
  (when (nil? (:read-nbt-fn cfg))
    (throw (ex-info "register-tile-logic!: :read-nbt-fn required" {:block-id block-id})))
  (when (nil? (:write-nbt-fn cfg))
    (throw (ex-info "register-tile-logic!: :write-nbt-fn required" {:block-id block-id})))
  (swap! tile-logic-registry assoc block-id cfg)
  (log/info "Registered tile logic for block" block-id)
  nil)

(defn get-tile-logic [block-id]
  (get @tile-logic-registry block-id))

(defn invoke-tick
  "Called from ScriptedBlockEntity.serverTick(level, pos, state, be).
  Invokes the registered :tick-fn for block-id with (level pos state be)."
  [block-id level pos state be]
  (when-let [cfg (get-tile-logic block-id)]
    (try
      ((:tick-fn cfg) level pos state be)
      (catch Exception e
        (log/error "Tile tick error" block-id (.getMessage e))))))

(defn read-nbt
  "Called from ScriptedBlockEntity.load(tag). Returns a data map for the BE to apply (setFromData).
  tag is the platform NBT compound (INBTCompound)."
  [block-id tag]
  (if-let [cfg (get-tile-logic block-id)]
    (try
      ((:read-nbt-fn cfg) tag)
      (catch Exception e
        (log/error "Tile read-nbt error" block-id (.getMessage e))
        {}))
    {}))

(defn write-nbt
  "Called from ScriptedBlockEntity.saveAdditional(tag). Writes BE state to tag.
  be has getEnergy(), getBatteryStack(), etc.; tag is INBTCompound."
  [block-id be tag]
  (when-let [cfg (get-tile-logic block-id)]
    (try
      ((:write-nbt-fn cfg) be tag)
      (catch Exception e
        (log/error "Tile write-nbt error" block-id (.getMessage e))))))
