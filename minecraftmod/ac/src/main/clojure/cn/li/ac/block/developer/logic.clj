(ns cn.li.ac.block.developer.logic
  "Server-side developer helpers (ability / gameplay hooks).

  Mirrors `IDeveloper.tryPullEnergy` / energy query from classic AcademyCraft."
  (:require [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.ac.block.developer.schema :as dev-schema]))

(defn try-pull-energy!
  "If the tile has at least `amount` IF stored, deduct and return true; else false."
  [tile ^double amount]
  (boolean
    (try
      (when tile
        (let [state (or (platform-be/get-custom-state tile)
                        (state-schema/schema->default-state dev-schema/developer-schema))
              e (double (get state :energy 0.0))]
          (when (>= e amount)
            (platform-be/set-custom-state! tile (assoc state :energy (- e amount)))
            (platform-be/set-changed! tile)
            true)))
      (catch Exception _
        false))))

(defn get-energy
  [tile]
  (double (get (or (platform-be/get-custom-state tile) {}) :energy 0.0)))

(defn get-max-energy
  [tile]
  (double (get (or (platform-be/get-custom-state tile) {}) :max-energy 50000.0)))
