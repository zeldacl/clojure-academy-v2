(ns cn.li.vfx.layout
  "Particle attribute set -> structure-of-arrays column layout.

   Dead-strip is automatic, not a separate pass: an attribute never
   requested by any module simply never appears in the attrs map `build`
   is given -- cn.li.vfx.compile collects that set from the effect's own
   :particle-spawn/:particle-update/:render module lists before calling
   build, so an unused attribute costs zero columns, not a column that
   goes unwritten.

   Column-major, one array per numeric KIND (float vs int), not one array
   per attribute: attribute :position (a :vec3, width 3) occupies 3
   consecutive float columns; a scalar :float occupies 1. Column index c,
   particle index i in a buffer of `capacity` -> flat offset c*capacity+i
   (cn.li.vfx.compile's compiled module closures close over these offsets
   directly, never doing an attribute->column lookup per particle).

   Deliberately its own width/kind rules, NOT cn.li.node.types/width --
   that lattice serves the general DSL register-bank model, where e.g. a
   :color's 4 channels would each want their own register; a particle
   buffer packs :color into a single int column instead, matching the
   pre-existing cn.li.mcmod.runtime.vfx.ParticleBuffer convention this
   layout is meant to feed. Conflating the two type systems would be
   conflating two genuinely different packing concerns.")

(def ^:private widths {:vec3 3 :float 1 :long 1 :color 1 :boolean 1})
(def ^:private integral-types #{:long :color :boolean})

(defn attribute-width [t] (get widths t 1))
(defn integral-attribute? [t] (contains? integral-types t))

(defn build
  "attrs: {attr-name particle-type}. Returns
     {:capacity n :float-cols n :int-cols n
      :cols {attr-name [col-index ...]}}
   :cols values are always a vector even for width-1 attributes, so
   callers never special-case scalar vs vec3 access."
  [attrs capacity]
  (reduce (fn [acc [attr-name t]]
            (let [w (attribute-width t)
                  ints? (integral-attribute? t)
                  base (if ints? (:int-cols acc) (:float-cols acc))]
              (-> acc
                  (assoc-in [:cols attr-name] (vec (range base (+ base w))))
                  (update (if ints? :int-cols :float-cols) + w))))
          {:capacity capacity :float-cols 0 :int-cols 0 :cols {}}
          (sort-by key attrs)))

(defn offset
  "Flat array offset for column `col` of particle `index`, in a buffer
   sized for `capacity` (whichever of :floats/:ints array `col` belongs to
   -- the caller already knows that from which of :float-cols/:int-cols
   range `col` fell in when `build` assigned it)."
  ^long [layout ^long col ^long index]
  (+ (* col (long (:capacity layout))) index))

(defn column
  "The (possibly multi-slot) column vector build assigned attr-name, or
   nil if it was never requested (dead-stripped)."
  [layout attr-name]
  (get-in layout [:cols attr-name]))
