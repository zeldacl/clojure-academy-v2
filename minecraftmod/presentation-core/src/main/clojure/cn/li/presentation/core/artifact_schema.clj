(ns cn.li.presentation.core.artifact-schema
  "The schema-5 line-format encoding tables: flag bits and the small enums
   packed as ints in a compiled .uic.edn node table.

   Extracted from presentation-compiler's artifact.clj (P5), where these
   five maps were duplicated verbatim in presentation-core's own
   src/test/.../test_artifact.clj -- both real writer and test-only
   constructor need the same schema-5 line-format numbers, so this is the
   one place that owns them.

   Must match cn.li.presentation.core.engine's Java constant interfaces
   exactly (NodeFlags / SizeMode / Direction / Justify / Align) -- that
   Java package is the runtime kernel's own source of truth for these
   numbers; this namespace does not redefine them, it is the single
   Clojure-side copy of the same contract."
  )

(def flag-bits
  {:has-clip 1 :is-scroll 2 :is-collection 4 :hit-testable 8
   :has-visible-bind 16 :wrap 32 :focusable 64 :animated 128
   :opaque 256 :scrollbar 512 :has-direction 1024
   :has-transform 2048})

(defn flags->int [flag-set]
  (reduce (fn [acc f] (bit-or acc (long (get flag-bits f 0)))) 0 flag-set))

(def size-mode->int {:auto 0 :fixed 1 :pct 2 :weight 3 :fill 4})
(def direction->int {:none 0 :row 1 :column 2})
(def justify->int {:start 0 :center 1 :end 2 :space-between 3 :space-around 4})
(def align->int {:inherit -1 :start 0 :center 1 :end 2 :stretch 3})
