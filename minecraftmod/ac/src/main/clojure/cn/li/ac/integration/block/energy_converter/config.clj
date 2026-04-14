(ns cn.li.ac.integration.block.energy-converter.config
  "Config for upstream-style 4 converter blocks.")

(def energy-capacity 2000.0)
(def transfer-bandwidth 100.0)

;; Upstream parity: 1 IF = 4 RF
(def rf-conversion-ratio 4.0)
(def eu-conversion-ratio 1.0)

(def supported-blocks
  #{"rf-input" "rf-output" "eu-input" "eu-output"})

(defn input-block?
  [block-id]
  (contains? #{"rf-input" "eu-input"} (str block-id)))

(defn output-block?
  [block-id]
  (contains? #{"rf-output" "eu-output"} (str block-id)))

(defn conversion-ratio
  [block-id]
  (case (str block-id)
    "rf-input" rf-conversion-ratio
    "rf-output" rf-conversion-ratio
    "eu-input" eu-conversion-ratio
    "eu-output" eu-conversion-ratio
    1.0))