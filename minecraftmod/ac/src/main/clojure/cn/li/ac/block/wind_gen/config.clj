(ns cn.li.ac.block.wind-gen.config
  "Wind Generator configuration aligned with AcademyCraft 1.12 behavior.")

;; Structure
(def min-pillars
  "Minimum pillar count required for a complete wind generator tower."
  8)

(def max-pillars
  "Maximum pillar count allowed for a valid wind generator tower."
  40)

;; Generation
(def max-generation-speed
  "Maximum IF/t at full altitude factor."
  15.0)

(def base-height
  "Altitude baseline used by the original height curve."
  70.0)

(def height-range
  "Altitude range over which factor lerps from 0.5 to 1.0."
  90.0)

(def min-height-factor
  "Lower clamp of altitude generation factor."
  0.5)

(def max-height-factor
  "Upper clamp of altitude generation factor."
  1.0)

;; Energy storage
(def max-energy-base
  "Base internal buffer (same as original TileWindGenBase)."
  20000.0)

(def structure-update-interval
  "Ticks between structure scans (original: 10)."
  10)

;; Sync interval
(def sync-interval
  "Ticks between GUI sync broadcasts"
  20)

(defn calculate-height-multiplier
  "Original height curve: lerp(0.5, 1.0, clamp((y-70)/90, 0, 1))."
  [y-level]
  (let [ratio (/ (- (double y-level) base-height) height-range)
        clamped (-> ratio (max 0.0) (min 1.0))]
    (+ min-height-factor
       (* (- max-height-factor min-height-factor) clamped))))

(defn calculate-generation-rate
  "Calculate IF/t output from main block altitude."
  [y-level]
  (* max-generation-speed
     (calculate-height-multiplier y-level)))
