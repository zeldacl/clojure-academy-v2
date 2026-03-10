(ns my-mod.block.solar-gen
  "Solar Generator block (ported from AcademyCraft).

  Uses generic ScriptedBlockEntity; tick and NBT logic are registered in this
  namespace. Block metadata and right-click (open GUI) are defined here."
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.block.tile-dsl :as tdsl]
            [my-mod.platform.nbt :as nbt]
            [my-mod.platform.item :as item]
            [my-mod.util.log :as log]
            [my-mod.config.modid :as modid]))

(defn- open-solar-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-solar-gui (requiring-resolve 'my-mod.wireless.gui.registry/open-solar-gui)]
        (open-solar-gui player world pos)
        (do (log/error "SolarGen GUI open fn not found: my-mod.wireless.gui.registry/open-solar-gui") nil))
      (catch Exception e
        (log/error "Failed to open SolarGen GUI:" (.getMessage e))
        nil))))

(bdsl/defblock solar-gen
  :registry-name "solar_gen"
  :material :stone
  :hardness 1.5
  :resistance 6.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 1
  :sounds :stone
  :model-parent "minecraft:block/cube_all"
  :textures {:all (modid/asset-path "block" "solar_gen")}
  :on-right-click open-solar-gui!)

;; ---------------------------------------------------------------------------
;; Scripted tile logic (tick + NBT) for generic ScriptedBlockEntity
;; ---------------------------------------------------------------------------

(def ^:private max-energy 1000.0)

(defn- can-generate?
  "True when the level is daytime and the block above has sky access."
  [level pos]
  (when (and level pos)
    (let [time (rem (long (.getDayTime level)) 24000)
          day? (<= time 12500)]
      (and day? (.canSeeSky level (.above pos))))))

(defn- solar-tick-fn
  "Tick handler for solar generator ScriptedBlockEntity.

  All mutable state is stored in BE customState as a Clojure keyword map.
  No reflection — uses direct Java interop and getCustomState/setCustomState."
  [level pos _block-state be]
  (when (and level (not (.isClientSide level)))
    (let [state       (or (.getCustomState be) {})
          generating? (can-generate? level pos)
          raining?    (.isRaining level)
          status      (cond (not generating?) "STOPPED"
                            raining?          "WEAK"
                            :else             "STRONG")
          bright      (if generating? 1.0 0.0)
          bright*     (if (and (> bright 0) raining?) (* bright 0.2) bright)
          gen         (* bright* 3.0)
          current     (double (get state :energy 0.0))
          new-energy  (min max-energy (+ current gen))
          changed?    (and (> gen 0) (not= new-energy current))
          new-state   (cond-> (assoc state :status status :max-energy max-energy)
                        changed? (assoc :energy new-energy))]
      (when (not= new-state state)
        (.setCustomState be new-state)
        (when changed?
          (.setChanged be))))))

(defn- solar-read-nbt-fn
  "Deserialize CompoundTag → state keyword map (stored in BE customState)."
  [tag]
  {:energy     (if (nbt/nbt-has-key? tag "Energy")
                 (nbt/nbt-get-double tag "Energy")
                 0.0)
   :max-energy max-energy
   :status     "STOPPED"
   :battery    (when (nbt/nbt-has-key? tag "Battery")
                 (item/create-item-from-nbt (nbt/nbt-get-compound tag "Battery")))})

(defn- solar-write-nbt-fn
  "Serialize BE customState → CompoundTag."
  [be tag]
  (let [state (or (.getCustomState be) {})]
    (nbt/nbt-set-double! tag "Energy" (double (get state :energy 0.0)))
    (let [stack (:battery state)]
      (when (and stack (not (item/item-is-empty? stack)))
        (let [sub (nbt/create-nbt-compound)]
          (item/item-save-to-nbt stack sub)
          (nbt/nbt-set-tag! tag "Battery" sub))))))

(tdsl/deftile solar-gen-tile
  :id "solar-gen"
  :registry-name "solar_gen"
  :impl :scripted
  :blocks ["solar-gen"]
  :tick-fn solar-tick-fn
  :read-nbt-fn solar-read-nbt-fn
  :write-nbt-fn solar-write-nbt-fn)

(defn init-solar-gen!
  []
  (log/info "Initialized Solar Generator block"))
