(ns cn.li.presentation.compiler.render
  "Pure Clojure interpreter for compiled UI templates.

   This is deliberately a small render-IR producer: it reads numeric binding
   IDs from PresentationViewModel and emits immutable RenderCommand records.
   Minecraft APIs and host policy stay outside this namespace."
  (:import [cn.li.presentation.compiler CompiledTemplate TemplateNode]
           [cn.li.presentation.core BindingTable PresentationViewModel
            RenderCommand$GlyphRun RenderCommand$Image RenderCommand$Quad]))

(defn- binding-value [^PresentationViewModel model ^TemplateNode node attr]
  (when-let [id (get (.bindings node) (name attr))]
    (.value ^BindingTable (.bindings model) (int id))))

(defn- clamp [x lo hi]
  (max lo (min hi (double (or x 0.0)))))

(defn- ratio [value]
  (cond
    (number? value) (clamp value 0.0 1.0)
    (map? value) (clamp (or (:ratio value) (:percent value) (:value value)) 0.0 1.0)
    :else 0.0))

(defn- rect-value [value default]
  (if (map? value)
    {:x (float (or (:x value) (:left value) (:x default) 0.0))
     :y (float (or (:y value) (:top value) (:y default) 0.0))
     :w (float (or (:w value) (:width value) (:w default) 1.0))
     :h (float (or (:h value) (:height value) (:h default) 1.0))
     :rgba (int (or (:rgba value) (:color value) -1))}
    (assoc default :rgba (int (or (:rgba default) -1)))))

(declare render-node)

(defn- render-children [^TemplateNode node model x y width height]
  (let [children (vec (.children node))
        count* (max 1 (count children))]
    (case (.type node)
      "flex" (mapcat (fn [[idx child]]
                       (render-node child model
                                    (+ x (* width (/ idx count*)))
                                    y (/ width count*) height))
                     (map-indexed vector children))
      "grid" (let [columns (max 1 (int (Math/ceil (Math/sqrt count*))))
                   rows (max 1 (int (Math/ceil (/ count* columns))))]
               (mapcat (fn [[idx child]]
                         (let [col (mod idx columns) row (quot idx columns)]
                           (render-node child model
                                        (+ x (* width (/ col columns)))
                                        (+ y (* height (/ row rows)))
                                        (/ width columns) (/ height rows))))
                       (map-indexed vector children)))
      (mapcat #(render-node % model x y width height) children))))

(defn render-node [^TemplateNode node model x y width height]
  (let [value (binding-value model node :value)
        type (.type node)]
    (case type
      ("stack" "flex" "grid" "scroll" "portal")
      (render-children node model x y width height)

      "virtual-list"
      (let [items (vec (or (binding-value model node :items) value []))
            n (max 1 (count items))
            row-height 18.0
            visible-count (+ 2 (int (Math/ceil (/ height row-height))))
            visible-items (take visible-count items)]
        (mapcat (fn [[idx item]]
                  (let [label (if (map? item)
                                (or (:label item) (:name item) (:id item) (:title item))
                                item)
                        y* (+ y (* idx row-height))]
                    [(RenderCommand$Quad. (float x) (float y*) (float width)
                                           (float (max 1.0 (- row-height 1.0)))
                                           (unchecked-int 0x55203040))
                     (RenderCommand$GlyphRun. 0 (str (or label ""))
                                               (+ (float x) 4.0) (+ (float y*) 3.0)
                                               (unchecked-int 0xFFFFFFFF))]))
                (map-indexed vector visible-items)))

      "progress"
      (let [p (ratio value)
            bg (RenderCommand$Quad. (float x) (float y) (float width) (float height)
                                    (unchecked-int 0x55202020))
            fg (RenderCommand$Quad. (float x) (float y) (float (* width p)) (float height)
                                    (unchecked-int 0xFF35C7FF))]
        [bg fg])

      "text"
      [(RenderCommand$GlyphRun. 0 (str (or value "")) (float x) (float y)
                                  (unchecked-int 0xFFFFFFFF))]

      "textbox"
      (let [props (into {} (.props node))
            value (if (map? value) value {:text value})
            text (str (or (:text value) (:value value) (:text props)
                          (:placeholder props) ""))
            focused? (boolean (:focused? value))
            border (if focused? 0xFF55C7FF 0xFF405060)]
        [(RenderCommand$Quad. (float x) (float y) (float width) (float height)
                              (unchecked-int 0xCC101820))
         (RenderCommand$Quad. (float x) (float y) (float width) 1.0
                              (unchecked-int border))
         (RenderCommand$GlyphRun. 0 text (+ (float x) 4.0) (+ (float y) 4.0)
                                   (unchecked-int 0xFFFFFFFF))])

      "button"
      (let [props (into {} (.props node))
            ;; A bound button is opt-in: nil means that the host did not
            ;; expose this optional control for the current screen.  This
            ;; keeps the shared machine template from drawing phantom
            ;; controls on containers that do not declare button metadata.
            has-value? (or (map? value) (contains? props :label))
            value (if (map? value) value {})
            visible? (and has-value? (not (false? (:visible? value))))
            bx (float (or (:x value) (:x props) x))
            by (float (or (:y value) (:y props) y))
            bw (float (or (:w value) (:width value) (:w props) width))
            bh (float (or (:h value) (:height value) (:h props) height))
            rgba (unchecked-int (or (:rgba value) (:rgba props) 0xCC315A78))
            label (or (:label value) (:label props) "")]
        (if visible?
          [(RenderCommand$Quad. bx by bw bh rgba)
           (RenderCommand$GlyphRun. 0 (str label) (+ bx 4.0) (+ by 4.0)
                                      (unchecked-int 0xFFFFFFFF))]
          []))

      "image"
      (let [{:keys [rgba]} (rect-value value {:x x :y y :w width :h height})]
        [(RenderCommand$Image. 0 (float x) (float y) (float width) (float height) rgba)])

      "quad"
      (let [{:keys [x y w h rgba]} (rect-value value {:x x :y y :w width :h height})]
        [(RenderCommand$Quad. (float x) (float y) (float w) (float h) rgba)])

      "skill-wheel"
      (let [items (vec (or (binding-value model node :items) value []))
            n (max 1 (count items))]
        (mapcat (fn [[idx item]]
                  (let [angle (* 2.0 Math/PI (/ idx n))
                        cx (+ x (* 0.5 width) (* 0.35 width (Math/cos angle)))
                        cy (+ y (* 0.5 height) (* 0.35 height (Math/sin angle)))
                        label (if (map? item) (or (:label item) (:skill-id item)) item)]
                    [(RenderCommand$Quad. (float (- cx 12.0)) (float (- cy 12.0)) 24.0 24.0
                                           (unchecked-int 0xCC406080))
                     (RenderCommand$GlyphRun. 0 (str label) (float (- cx 8.0)) (float (+ cy 14.0))
                                                (unchecked-int 0xFFFFFFFF))]))
                (map-indexed vector items)))

      "modal"
      (if (or (map? value) (true? value))
        [(RenderCommand$Quad. (float x) (float y) (float width) (float height)
                              (unchecked-int 0xAA000000))]
        [])

      (render-children node model x y width height))))

(defn render-template
  "Render a compiled template into painter-order commands.

   `context` is a map with `:width` and `:height`; no mutable UI state is
   created here, which keeps the hot path suitable for later instance-buffer
   specialization."
  [^CompiledTemplate template ^PresentationViewModel model context]
  (let [width (double (max 1 (or (:width context) 1)))
        height (double (max 1 (or (:height context) 1)))]
    (vec (render-node (.root template) model 0.0 0.0 width height))))
