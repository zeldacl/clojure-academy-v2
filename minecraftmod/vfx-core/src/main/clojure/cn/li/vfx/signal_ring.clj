(ns cn.li.vfx.signal-ring
  "Bounded signal ring operations over the Java storage carrier."
  (:import [cn.li.mcmod.runtime.effect SignalRing]))

(set! *warn-on-reflection* true)

(defn offer! [^SignalRing ring signal ^long sequence]
  (let [^objects signals (.-signals ring)
        ^longs sequences (.-sequences ring)
        capacity (alength signals)
        tail (.-tail ring)]
    (when (< (- tail (.-head ring)) capacity)
      (let [index (mod tail capacity)]
        (aset signals index signal)
        (aset-long sequences index sequence)
        (set! (.-tail ring) (inc tail))
        true))))

(defn poll! [^SignalRing ring]
  (let [head (.-head ring)]
    (when (< head (.-tail ring))
      (let [^objects signals (.-signals ring)
            capacity (alength signals)
            index (mod head capacity)
            value (aget signals index)]
        (aset signals index nil)
        (set! (.-head ring) (inc head))
        value))))
