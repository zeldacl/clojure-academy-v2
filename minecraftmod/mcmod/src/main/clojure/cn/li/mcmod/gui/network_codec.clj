(ns cn.li.mcmod.gui.network-codec
  "Shared network packet encoding/decoding utilities for GUI packets.
  
  Provides platform-agnostic encoding/decoding logic. Platform layers wrap these
  functions with proper type hints for their specific buffer types.")

;; =========================================================================
;; Generic Encoding/Decoding Functions
;; 
;; Platform layers should wrap these with type-hinted versions:
;; 
;; Example (Forge 1.20.1):
;;   (defn write-value [^FriendlyByteBuf buf val]
;;     (network-codec/write-value* buf val
;;       #(.writeByte %) #(.writeInt % %2) #(.writeFloat % %2)
;;       #(.writeUtf % %2) #(.writeBoolean % %2)))
;; =========================================================================

(defn write-value*
  "Generic value writer with platform-specific write callbacks.
  
  Type tags:
  0 = integer, 1 = float, 2 = string, 3 = boolean, 4 = nil, 5 = other
  
  Args:
  - buffer: Platform buffer object
  - value: Value to write
  - write-byte-fn: (fn [buf val] ...) - writes byte
  - write-int-fn: (fn [buf val] ...) - writes int
  - write-float-fn: (fn [buf val] ...) - writes float
  - write-str-fn: (fn [buf val] ...) - writes string
  - write-bool-fn: (fn [buf val] ...) - writes boolean"
  [buffer value write-byte-fn write-int-fn write-float-fn write-str-fn write-bool-fn]
  (cond
    (integer? value) (do (write-byte-fn buffer 0) (write-int-fn buffer value))
    (float? value) (do (write-byte-fn buffer 1) (write-float-fn buffer value))
    (string? value) (do (write-byte-fn buffer 2) (write-str-fn buffer value))
    (boolean? value) (do (write-byte-fn buffer 3) (write-bool-fn buffer value))
    (nil? value) (write-byte-fn buffer 4)
    :else (do (write-byte-fn buffer 5) (write-str-fn buffer (pr-str value)))))

(defn read-value*
  "Generic value reader with platform-specific read callbacks.
  
  Args:
  - buffer: Platform buffer object
  - read-byte-fn: (fn [buf] ...) - reads byte
  - read-int-fn: (fn [buf] ...) - reads int
  - read-float-fn: (fn [buf] ...) - reads float
  - read-str-fn: (fn [buf] ...) - reads string
  - read-bool-fn: (fn [buf] ...) - reads boolean
  
  Returns: Decoded value"
  [buffer read-byte-fn read-int-fn read-float-fn read-str-fn read-bool-fn]
  (let [type-id (read-byte-fn buffer)]
    (case type-id
      0 (read-int-fn buffer)
      1 (read-float-fn buffer)
      2 (read-str-fn buffer)
      3 (read-bool-fn buffer)
      4 nil
      5 (read-str-fn buffer)
      nil)))

(defn write-data-map*
  "Generic map writer using value writer.
  
  Args:
  - buffer: Platform buffer object
  - data: Map with keyword keys
  - write-int-fn: (fn [buf val] ...) - writes int
  - write-str-fn: (fn [buf val] ...) - writes string
  - write-val-fn: (fn [buf val] ...) - writes tagged value"
  [buffer data write-int-fn write-str-fn write-val-fn]
  (write-int-fn buffer (count data))
  (doseq [[k v] data]
    (write-str-fn buffer (name k))
    (write-val-fn buffer v)))

(defn read-data-map*
  "Generic map reader using value reader.
  
  Args:
  - buffer: Platform buffer object
  - read-int-fn: (fn [buf] ...) - reads int
  - read-str-fn: (fn [buf] ...) - reads string
  - read-val-fn: (fn [buf] ...) - reads tagged value
  
  Returns: Map with keyword keys"
  [buffer read-int-fn read-str-fn read-val-fn]
  (let [count (read-int-fn buffer)]
    (into {}
          (for [_ (range count)]
            (let [key (keyword (read-str-fn buffer))
                  value (read-val-fn buffer)]
              [key value])))))
