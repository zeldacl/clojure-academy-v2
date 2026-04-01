(ns cn.li.ac.wireless.core.interfaces
  "Input validation helpers for wireless system values.")

(defn validate-energy-value
  [value]
  (when (< value 0.0)
    (throw (IllegalArgumentException. "Energy value cannot be negative")))
  value)

(defn validate-bandwidth
  [bandwidth]
  (when (<= bandwidth 0.0)
    (throw (IllegalArgumentException. "Bandwidth must be positive")))
  bandwidth)

(defn validate-range
  [range]
  (when (<= range 0.0)
    (throw (IllegalArgumentException. "Range must be positive")))
  range)

(defn validate-capacity
  [capacity]
  (when (<= capacity 0)
    (throw (IllegalArgumentException. "Capacity must be positive")))
  capacity)

