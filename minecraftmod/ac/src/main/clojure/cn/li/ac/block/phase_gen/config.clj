(ns cn.li.ac.block.phase-gen.config
  "Phase Generator configuration aligned with AcademyCraft TilePhaseGen.")

;; Energy generation parity:
;; CONSUME_PER_TICK = 100 mB, GEN_PER_MB = 0.5 IF
(def liquid-consume-per-tick
  "Maximum internal phase-liquid drain per tick (mB)."
  100)

(def gen-per-mb
  "Generated energy per drained mB of phase liquid."
  0.5)

;; Energy storage parity:
;; TilePhaseGen -> TileGeneratorBase(..., 6000, ...)
(def max-energy
  "Maximum energy storage."
  6000.0)

;; Internal tank parity:
;; TANK_SIZE = 8000, PER_UNIT = 1000
(def tank-size
  "Internal phase-liquid tank size (mB)."
  8000)

(def liquid-per-unit
  "Liquid added when consuming one phase-liquid matter unit."
  1000)

;; Inventory layout parity (ContainerPhaseGen):
(def liquid-in-slot 0)
(def liquid-out-slot 1)
(def output-slot 2)
(def total-slots 3)

;; Matter unit mapping reused by parity logic.
(def matter-unit-item-id "my_mod:matter_unit")
(def matter-unit-none-meta 0)
(def matter-unit-phase-liquid-meta 1)
