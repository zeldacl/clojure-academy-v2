(ns my-mod.block.solar-gen
  "Solar Generator block (ported from AcademyCraft).

  Note: The actual ticking + energy storage is implemented in platform-specific
  Java BlockEntity classes. This namespace only defines the block metadata and
  interaction handlers (right-click opens GUI)."
  (:require [my-mod.block.dsl :as bdsl]
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
  :textures {:all "my_mod:blocks/solar_gen"}
  :on-right-click open-solar-gui!)

(defn init-solar-gen!
  []
  (log/info "Initialized Solar Generator block"))

