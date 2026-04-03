(ns cn.li.ac.block.energy-converter.face-config
  "Face-based I/O configuration for energy converters.

  Allows each face of the converter block to be configured as:
  - 'input' - Accept energy from this face
  - 'output' - Provide energy to this face
  - 'none' - Disabled, no energy transfer"
  (:require [cn.li.mcmod.util.log :as log]))

(set! *warn-on-reflection* true)

;; ============================================================================
;; Face Configuration
;; ============================================================================

(def valid-face-modes
  "Valid face configuration modes."
  #{"input" "output" "none"})

(def face-directions
  "All six face directions."
  [:north :south :east :west :up :down])

(defn default-face-config
  "Get default face configuration (all faces disabled)."
  []
  {:north "none"
   :south "none"
   :east "none"
   :west "none"
   :up "none"
   :down "none"})

(defn get-face-mode
  "Get the mode for a specific face.

  Args:
    face-config - Face configuration map
    direction - Face direction keyword (:north, :south, etc.)

  Returns:
    Face mode string ('input', 'output', or 'none')"
  [face-config direction]
  (get face-config direction "none"))

(defn set-face-mode
  "Set the mode for a specific face.

  Args:
    face-config - Face configuration map
    direction - Face direction keyword
    mode - New mode ('input', 'output', or 'none')

  Returns:
    Updated face configuration map"
  [face-config direction mode]
  (if (valid-face-modes mode)
    (assoc face-config direction mode)
    (do
      (log/warn (str "Invalid face mode: " mode ", using 'none'"))
      (assoc face-config direction "none"))))

(defn cycle-face-mode
  "Cycle a face through modes: none -> input -> output -> none.

  Args:
    face-config - Face configuration map
    direction - Face direction keyword

  Returns:
    Updated face configuration map"
  [face-config direction]
  (let [current-mode (get-face-mode face-config direction)
        next-mode (case current-mode
                    "none" "input"
                    "input" "output"
                    "output" "none"
                    "none")]
    (set-face-mode face-config direction next-mode)))

(defn can-receive-from-face?
  "Check if a face can receive energy (is configured as input).

  Args:
    face-config - Face configuration map
    direction - Face direction keyword

  Returns:
    true if face accepts energy input"
  [face-config direction]
  (= "input" (get-face-mode face-config direction)))

(defn can-extract-from-face?
  "Check if a face can provide energy (is configured as output).

  Args:
    face-config - Face configuration map
    direction - Face direction keyword

  Returns:
    true if face provides energy output"
  [face-config direction]
  (= "output" (get-face-mode face-config direction)))

(defn is-face-enabled?
  "Check if a face is enabled (not 'none').

  Args:
    face-config - Face configuration map
    direction - Face direction keyword

  Returns:
    true if face is enabled"
  [face-config direction]
  (not= "none" (get-face-mode face-config direction)))

(defn get-input-faces
  "Get all faces configured as input.

  Args:
    face-config - Face configuration map

  Returns:
    Collection of direction keywords"
  [face-config]
  (filter #(can-receive-from-face? face-config %) face-directions))

(defn get-output-faces
  "Get all faces configured as output.

  Args:
    face-config - Face configuration map

  Returns:
    Collection of direction keywords"
  [face-config]
  (filter #(can-extract-from-face? face-config %) face-directions))

(defn get-enabled-faces
  "Get all enabled faces (input or output).

  Args:
    face-config - Face configuration map

  Returns:
    Collection of direction keywords"
  [face-config]
  (filter #(is-face-enabled? face-config %) face-directions))

;; ============================================================================
;; Face Configuration Presets
;; ============================================================================

(defn preset-all-input
  "Configure all faces as input."
  []
  {:north "input"
   :south "input"
   :east "input"
   :west "input"
   :up "input"
   :down "input"})

(defn preset-all-output
  "Configure all faces as output."
  []
  {:north "output"
   :south "output"
   :east "output"
   :west "output"
   :up "output"
   :down "output"})

(defn preset-sides-input-top-output
  "Configure sides as input, top as output."
  []
  {:north "input"
   :south "input"
   :east "input"
   :west "input"
   :up "output"
   :down "none"})

(defn preset-bottom-input-top-output
  "Configure bottom as input, top as output."
  []
  {:north "none"
   :south "none"
   :east "none"
   :west "none"
   :up "output"
   :down "input"})

;; ============================================================================
;; Direction Conversion
;; ============================================================================

(defn direction-to-keyword
  "Convert Minecraft Direction to keyword.

  Args:
    direction - Minecraft Direction enum value

  Returns:
    Direction keyword (:north, :south, etc.) or nil"
  [direction]
  (when direction
    (let [name (str direction)]
      (case name
        "north" :north
        "south" :south
        "east" :east
        "west" :west
        "up" :up
        "down" :down
        nil))))

(defn keyword-to-direction-name
  "Convert keyword to direction name string.

  Args:
    direction-kw - Direction keyword

  Returns:
    Direction name string"
  [direction-kw]
  (name direction-kw))
