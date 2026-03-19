(ns my-mod.block.solar-gen
  "Solar Generator block (ported from AcademyCraft).

  Uses generic ScriptedBlockEntity; tick and NBT logic are registered in this
  namespace. Block metadata and right-click (open GUI) are defined here."
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.block.tile-dsl :as tdsl]
            [my-mod.platform.capability :as platform-cap]
            [my-mod.block.tile-logic :as tile-logic]
            [my-mod.block.role-impls :as impls]
            [my-mod.platform.nbt :as nbt]
            [my-mod.platform.item :as item]
            [my-mod.platform.be :as platform-be]
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

(declare solar-gen)

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
    (let [time (rem (long (my-mod.platform.world/world-get-day-time level)) 24000)
          day? (<= time 12500)]
      (and day? (my-mod.platform.world/world-can-see-sky level
                  (clojure.lang.Reflector/invokeInstanceMethod pos "above" (object-array [])))))))

(defn- solar-tick-fn
  "Tick handler for solar generator ScriptedBlockEntity.

  All mutable state is stored in BE customState as a Clojure keyword map.
  No reflection — uses direct Java interop and getCustomState/setCustomState."
  [level pos _block-state be]
  (when (and level (not (my-mod.platform.world/world-is-client-side level)))
    (let [state       (or (platform-be/get-custom-state be) {})
          generating? (can-generate? level pos)
          raining?    (my-mod.platform.world/world-is-raining level)
          status      (cond (not generating?) "STOPPED"
                            raining?          "WEAK"
                            :else             "STRONG")
          bright      (if generating? 1.0 0.0)
          bright*     (if (and (> bright 0) raining?) (* bright 0.2) bright)
          gen         (* bright* 3.0)
          current     (double (get state :energy 0.0))
          new-energy  (min max-energy (+ current gen))
          changed?    (and (> gen 0) (not= new-energy current))
          new-state   (cond-> (assoc state
                               :status status
                               :max-energy max-energy
                               :gen-speed (double gen))
                        changed? (assoc :energy new-energy))]
      (when (not= new-state state)
        (platform-be/set-custom-state! be new-state)
        (when changed?
          (platform-be/set-changed! be))))))

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
  (let [state (or (platform-be/get-custom-state be) {})]
    (nbt/nbt-set-double! tag "Energy" (double (get state :energy 0.0)))
    (let [stack (:battery state)]
      (when (and stack (not (item/item-is-empty? stack)))
        (let [sub (nbt/create-nbt-compound)]
          (item/item-save-to-nbt stack sub)
          (nbt/nbt-set-tag! tag "Battery" sub))))))

(declare solar-gen-tile)

(tdsl/deftile solar-gen-tile
  :id "solar-gen"
  :registry-name "solar_gen"
  :impl :scripted
  :blocks ["solar-gen"]
  :tick-fn solar-tick-fn
  :read-nbt-fn solar-read-nbt-fn
  :write-nbt-fn solar-write-nbt-fn)

;; Register capability so wireless system can treat SolarGen as a generator.
(platform-cap/declare-capability! :wireless-generator cn.li.ac.api.wireless.IWirelessGenerator
  (fn [be _side] (impls/->WirelessGeneratorImpl be)))

(tile-logic/register-tile-capability! "solar-gen" :wireless-generator)

(defn init-solar-gen!
  []
  (log/info "Initialized Solar Generator block"))
