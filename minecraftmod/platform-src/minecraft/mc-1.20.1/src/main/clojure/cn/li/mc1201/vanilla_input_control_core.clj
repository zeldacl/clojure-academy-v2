(ns cn.li.mc1201.vanilla-input-control-core
  "1.20.1 ControlOverrider equivalent: clear vanilla KeyMapping pressed state
   for ability-slot key codes so Minecraft does not attack/use/swing while the
   skill system owns those keys via GLFW polling."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.blaze3d.platform InputConstants$Type]
           [net.minecraft.client KeyMapping Minecraft Options]))

(defn- get-minecraft
  []
  (Minecraft/getInstance))

(defn- get-options
  (^Options [] (get-options (get-minecraft)))
  (^Options [minecraft]
   (when minecraft
     (.options ^Minecraft minecraft))))

(defn ac-key-code
  "Map a KeyMapping's bound InputConstants.Key to the AC Settings integer
   convention (MOUSE_LEFT=-100, MOUSE_RIGHT=-99, else GLFW keysym)."
  [^KeyMapping mapping]
  (let [key (.getKey mapping)
        value (.getValue key)]
    (if (= (.getType key) InputConstants$Type/MOUSE)
      (+ -100 (int value))
      (int value))))

(defn- drain-and-release!
  "Consume pending clicks and force the mapping not-down for this frame."
  [^KeyMapping mapping]
  (while (.consumeClick mapping))
  (.setDown mapping false))

(defn suppress-key-codes!
  "For each AC key-code in `key-codes`, force matching vanilla interaction
   KeyMappings (attack/use/pick) off for this frame.

   Matching is by the mapping's *current* bound key, so rebinding vanilla
   attack away from LMB (or ability slots onto other keys) still works.
   Empty `key-codes` is a no-op."
  [key-codes]
  (let [codes (into #{} (map int) key-codes)]
    (when (seq codes)
      (try
        (when-let [^Options opts (get-options)]
          (doseq [^KeyMapping mapping [(.keyAttack opts)
                                       (.keyUse opts)
                                       (.keyPickItem opts)]]
            (when (and mapping (contains? codes (ac-key-code mapping)))
              (drain-and-release! mapping))))
        (catch Exception e
          (log/debug e "Failed to suppress vanilla KeyMappings" {:key-codes key-codes})))))
  nil)

(def ^:private impl
  {:suppress! (fn [key-codes] (suppress-key-codes! key-codes))})

(defn get-spi-implementation
  "SPI map installed by Forge/Fabric client init."
  []
  impl)
