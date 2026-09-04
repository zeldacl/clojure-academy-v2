(ns cn.li.ability.editor.render
  "Pure geometry helpers for drawing the node editor's canvas as a flat
   COMPOSITE-item vector (cn.li.presentation.core's UiOp/COMPOSITE, the
   same mechanism ac's existing skill_tree.clj already uses to paint a
   graph -- see the node-editor plan's §1.4 for that precedent). Only the
   DIMENSION-AGNOSTIC pieces live here: wire routing and a default
   auto-layout fallback. Full node-box rendering (label text, pin
   positions, per-:category color) needs real design-unit sizes that
   only get pinned down once Phase 3 wires up an actual .ui.edn artifact
   -- building that now, without a screen to check it against, risks
   guessing dimensions that would just be redone; this namespace ships
   what can be verified without one."
  )

(defn wire-quads
  "x1 y1 x2 y2 thickness rgba -> three axis-aligned :quad composite items
   forming a horizontal-vertical-horizontal orthogonal connector. NOT the
   containing-rectangle approach cn.li.ac.ability.client.screens.skill-
   tree's own connection-items uses (skill_tree.clj:227-235) -- that
   renders a diagonal edge as one filled bounding-box rectangle, a real
   visual bug for anything but a perfectly horizontal/vertical pair. All
   three segments here are axis-aligned by construction, so :quad (the
   only rect primitive this canvas needs) stays pixel-accurate for any
   x1/y1/x2/y2, no new render primitive required."
  [x1 y1 x2 y2 thickness rgba]
  (let [x1 (double x1) y1 (double y1) x2 (double x2) y2 (double y2) thickness (double thickness)
        mx (/ (+ x1 x2) 2.0)
        h (/ thickness 2.0)]
    [{:kind :quad :x (min x1 mx) :y (- y1 h) :w (Math/abs (- mx x1)) :h thickness :rgba rgba}
     {:kind :quad :x (- mx h) :y (min y1 y2) :w thickness :h (Math/abs (- y2 y1)) :rgba rgba}
     {:kind :quad :x (min mx x2) :y (- y2 h) :w (Math/abs (- x2 mx)) :h thickness :rgba rgba}]))

(def ^:private default-node-spacing-x 160.0)
(def ^:private default-node-spacing-y 56.0)
(def ^:private default-column-width 6)

(defn default-layout
  "nid-seq (e.g. a graph's :order, in the stable order form->graph built
   them) -> {nid {:x :y}} -- a simple grid fallback for any nid absent
   from the real layout sidecar (a brand-new node just added, or the
   file's very first open before any sidecar exists). Deterministic by
   nid order, not meant to look good -- just to guarantee EVERY node has
   SOME position so rendering never has to special-case a missing
   layout entry."
  [nid-seq]
  (into {}
        (map-indexed (fn [i nid]
                       [nid {:x (* default-node-spacing-x (double (mod i default-column-width)))
                             :y (* default-node-spacing-y (double (quot i default-column-width)))}]))
        nid-seq))

(defn resolve-layout
  "stored-layout ({nid {:x :y ...}}, the editor's layout sidecar --
   cn.li.node.nid/collect gives the nid set it should be keyed by),
   nid-seq -> a COMPLETE layout covering every nid in nid-seq, falling
   back to default-layout's grid position for any nid stored-layout does
   not cover. stored-layout's entries win verbatim where present (an
   author's own placement is never overridden by the fallback)."
  [stored-layout nid-seq]
  (let [fallback (default-layout nid-seq)]
    (into {} (map (fn [nid] [nid (merge (get fallback nid) (get stored-layout nid))])) nid-seq)))
