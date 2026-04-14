(ns cn.li.ac.block.ability-interferer.config
  "Ability Interferer configuration")

;; Range settings
(defonce min-range 10.0) ; Minimum interference range (blocks)

(defonce max-range 100.0) ; Maximum interference range (blocks)

(defonce default-range 10.0) ; Default interference range (blocks)

;; Energy / transfer (mirrors classic interferer behavior)
(defonce battery-pull-per-tick 50.0) ; Maximum IF pulled from battery each tick

(defonce max-energy 10000.0) ; Maximum energy storage

;; Operation
(defonce check-interval 10) ; Ticks between player detection checks

(defonce sync-interval 20) ; Ticks between GUI sync broadcasts

(defonce calculate-energy-cost
  (fn [range]
    (let [r (double range)]
      (* r r))))
