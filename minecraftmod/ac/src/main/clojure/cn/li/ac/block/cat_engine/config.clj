(ns cn.li.ac.block.cat-engine.config
  "Cat Engine configuration aligned to AcademyCraft 1.12 semantics.")

(def max-energy
  "Internal energy buffer upper bound. Mirrors TileGeneratorBase ctor (max=2000)."
  2000.0)

(def generation-per-tick
  "Energy generated each server tick before transmission."
  500.0)

(def generator-bandwidth
  "Wireless generator bandwidth used by IWirelessGenerator."
  500.0)
