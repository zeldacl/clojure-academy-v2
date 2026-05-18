(ns cn.li.ac.block.developer.config
  "Developer configuration — values aligned with AcademyCraft `DeveloperType`
  energy / stimulation pacing (gameplay constants, not 1:1 Java port)."
  (:require [cn.li.ac.ability.domain.developer :as developer]))

;; Structure footprint: `cn.li.ac.block.developer.block/developer-multiblock-positions`

(def validate-interval
  "Ticks between multi-block structure validation."
  100)

(def ^:private wireless-bandwidth-by-tier
  {:normal 1000.0
   :advanced 1200.0})

(defn- tier-spec->config
  [tier]
  (let [{:keys [energy cps tps]} (developer/developer-spec tier)]
    {:max-energy energy
     :energy-per-stimulation cps
     :stimulation-interval-ticks tps
     :wireless-bandwidth (get wireless-bandwidth-by-tier tier 1000.0)}))

(def ^:private tier-table
  "Per-tier limits (NORMAL / ADVANCED from classic AC).
  - :max-energy — buffer size (IF)
  - :energy-per-stimulation — IF consumed each stimulation when developing
  - :stimulation-interval-ticks — ticks between stimulations (classic `tps`)
  - :wireless-bandwidth — IF/tick cap for `IWirelessReceiver` inject path"
  {:normal (tier-spec->config :normal)
   :advanced (tier-spec->config :advanced)})

(defn tier-config
  "Keyword tier `:normal` / `:advanced` → config map."
  [tier]
  (get tier-table (keyword tier) (:normal tier-table)))
