(ns my-mod.block.solar-gen
  "Solar Generator block (ported from AcademyCraft).

  Uses generic ScriptedBlockEntity; tick and NBT logic are registered in this
  namespace. Block metadata and right-click (open GUI) are defined here."
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.block.tile-dsl :as tdsl]
            [my-mod.platform.nbt :as nbt]
            [my-mod.platform.item :as item]
            [my-mod.util.log :as log]))

(defn- open-solar-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-solar-gui (requiring-resolve 'my-mod.wireless.gui.registry/open-solar-gui)]
        (open-solar-gui player world pos)
        (log/error "SolarGen GUI open fn not found: my-mod.wireless.gui.registry/open-solar-gui"))
      (catch Exception e
        (log/error "Failed to open SolarGen GUI:" (.getMessage e))))))

(bdsl/defblock solar-gen
  :registry-name "solar_gen"
  :material :stone
  :hardness 1.5
  :resistance 6.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 1
  :sounds :stone
  ;; Datagen source of truth (models/block/solar_gen.json)
  :model-parent "minecraft:block/cube_all"
  :textures {:all "my_mod:block/solar_gen"}
  :on-right-click open-solar-gui!)

;; ---------------------------------------------------------------------------
;; Scripted tile logic (tick + NBT) for generic ScriptedBlockEntity
;; ---------------------------------------------------------------------------

(def ^:private max-energy 1000.0)

(defn- safe-invoke [obj method & args]
  (when obj
    (try
      (clojure.lang.Reflector/invokeInstanceMethod
       obj method (object-array (or args [])))
      (catch Exception _ nil))))

(defn- can-generate? [level pos]
  (when level
    (let [time (rem (long (safe-invoke level "getDayTime")) 24000)
          day? (and (>= time 0) (<= time 12500))]
      (and day? (safe-invoke level "canSeeSky" (safe-invoke pos "above"))))))

(defn- solar-tick-fn [level _pos _state be]
  (when (and level (not (safe-invoke level "isClientSide")))
    (let [pos (safe-invoke be "getBlockPos")]
      (when pos
        (let [state       (or (.getCustomState be) {})
              generating? (can-generate? level pos)
              status      (cond (not generating?)                 "STOPPED"
                                (safe-invoke level "isRaining")   "WEAK"
                                :else                             "STRONG")
              bright      (if generating? 1.0 0.0)
              bright*     (if (and (> bright 0) (safe-invoke level "isRaining")) (* bright 0.2) bright)
              gen         (* bright* 3.0)
              current     (double (get state :energy 0.0))
              new-energy  (min max-energy (+ current gen))
              changed?    (and (> gen 0) (not= new-energy current))
              new-state   (cond-> (assoc state :status status :max-energy max-energy)
                            changed? (assoc :energy new-energy))]
          (when (not= new-state state)
            (.setCustomState be new-state)
            (when changed?
              (safe-invoke be "setChanged"))))))))

(defn- solar-read-nbt-fn [tag]
  {:energy     (if (nbt/nbt-has-key? tag "Energy")
                 (nbt/nbt-get-double tag "Energy")
                 0.0)
   :max-energy max-energy
   :status     "STOPPED"
   :battery    (when (nbt/nbt-has-key? tag "Battery")
                 (item/create-item-from-nbt (nbt/nbt-get-compound tag "Battery")))})

(defn- solar-write-nbt-fn [be tag]
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

