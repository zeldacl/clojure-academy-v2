(ns cn.li.ac.wireless.core.scheduling
  "Zero-state tick scheduling for the wireless runtime.

  Work is paced by gameTime modulo with a per-entity phase offset derived
  from an immutable identity (position tuple), so entities stagger across
  ticks instead of spiking on the same one — no per-entity counters, no
  commits, no persistence.")

(defn due?
  "True when `game-time` hits this entity's slot in an `interval`-tick cycle.
  `seed` must be an immutable identity (matrix-pos / node-pos / keyword)."
  [^long game-time ^long interval seed]
  (if (<= interval 1)
    true
    (zero? (Math/floorMod (+ game-time (long (hash seed))) interval))))
