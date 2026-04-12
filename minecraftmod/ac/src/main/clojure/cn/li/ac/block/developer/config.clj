(ns cn.li.ac.block.developer.config
  "Developer configuration — values aligned with AcademyCraft `DeveloperType`
  energy / stimulation pacing (gameplay constants, not 1:1 Java port).")

;; Structure footprint: `cn.li.ac.block.developer.block/developer-multiblock-positions`

(def validate-interval
  "Ticks between multi-block structure validation."
  100)

(def ^:private tier-table
  "Per-tier limits (NORMAL / ADVANCED from classic AC).
  - :max-energy — buffer size (IF)
  - :energy-per-stimulation — IF consumed each stimulation when developing
  - :stimulation-interval-ticks — ticks between stimulations (classic `tps`)
  - :wireless-bandwidth — IF/tick cap for `IWirelessReceiver` inject path"
  {:normal {:max-energy 50000.0
            :energy-per-stimulation 700.0
            :stimulation-interval-ticks 20
            :wireless-bandwidth 1000.0}
   :advanced {:max-energy 200000.0
              :energy-per-stimulation 600.0
              :stimulation-interval-ticks 15
              :wireless-bandwidth 1200.0}})

(defn tier-config
  "Keyword tier `:normal` / `:advanced` → config map."
  [tier]
  (get tier-table (keyword tier) (:normal tier-table)))
