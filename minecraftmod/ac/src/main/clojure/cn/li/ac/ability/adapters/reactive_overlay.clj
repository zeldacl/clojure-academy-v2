(ns cn.li.ac.ability.adapters.reactive-overlay
  "Reactive HUD overlay — native node tree + signals; no build-client-overlay-plan."
  (:require [cn.li.ac.ability.client.reactive-hud :as reactive-hud]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.dsl :as dsl]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.anim :as anim]
            [cn.li.mcmod.ui.node :as node])
  (:import [cn.li.mcmod.ui.node INode]
           [cn.li.mcmod.uipojo.signal ISigO]))

(defonce ^:private mode-switch-key-down?* (atom false))
(defonce ^:private showing-numbers?* (atom false))
(defonce ^:private last-show-value-change-ms* (atom 0))

(defn on-mode-switch-key-state!
  "Track V-key hold for CP/OL numeric readout fade in/out."
  [is-down]
  (let [was-down @mode-switch-key-down?*
        now (System/currentTimeMillis)]
    (cond
      (and (not was-down) is-down)
      (do
        (reset! showing-numbers?* true)
        (reset! last-show-value-change-ms* now))

      (and was-down (not is-down))
      (do
        (reset! showing-numbers?* false)
        (if (> (- now @last-show-value-change-ms*) 400)
          (reset! last-show-value-change-ms* now)
          (reset! last-show-value-change-ms* 0))))
    (reset! mode-switch-key-down?* (boolean is-down)))
  nil)

(defn- local-player-uuid [] (bridge/call-adapter :local-player-uuid))

(defn- rgba-vec->argb [[r g b a]]
  (bit-or (bit-shift-left (int (* 255.0 (double a))) 24)
          (bit-shift-left (int (* 255.0 (double r))) 16)
          (bit-shift-left (int (* 255.0 (double g))) 8)
          (int (* 255.0 (double b)))))

(defn- mask-vec [{:keys [r g b a]}]
  [(double (or r 0.0)) (double (or g 0.0)) (double (or b 0.0)) (double (or a 0.0))])

(defn- write-fill-from-rgba-o! [^INode n source]
  (let [rgba (sig/sget-o ^ISigO source)
        v (double (rgba-vec->argb rgba))]
    (when-not (== v (.getDSlot n 0))
      (.setDSlot n 0 v)
      (.setFlag n node/FLAG-RENDER-DIRTY))))

(defn- rgb-vec->argb [[r g b] alpha]
  (bit-or (bit-shift-left (int (* 255.0 (double alpha))) 24)
          (bit-shift-left (int r) 16)
          (bit-shift-left (int g) 8)
          (int b)))

(defn- rgba-map->argb [{:keys [r g b a]}]
  (bit-or (bit-shift-left (int (or a 255)) 24)
          (bit-shift-left (int (or r 0)) 16)
          (bit-shift-left (int (or g 0)) 8)
          (int (or b 0))))

(defn- set-box-node-at! [_r ^INode n x y w h rgba]
  (when n
    (.setX n (double x))
    (.setY n (double y))
    (.setW n (double w))
    (.setH n (double h))
    (.setDSlot n 0 (unchecked-int (rgba-map->argb rgba)))
    (.setFlag n node/FLAG-RENDER-DIRTY)))

(defn- set-box-rgba! [r id rgba]
  (when-let [^INode n (ui/node r id)]
    (set-box-node-at! r n (.getX n) (.getY n) (.getW n) (.getH n) rgba)))

(defn- set-box-at! [r id x y w h rgba]
  (when-let [^INode n (ui/node r id)]
    (set-box-node-at! r n x y w h rgba)))

(defn- toast-template []
  (dsl/group {:id :toast :w 200 :h 32}
    (dsl/box {:id :bg :x 0 :y 0 :w 200 :h 32 :fill 0x77272727})
    (dsl/box {:id :border-t :x 0 :y 0 :w 200 :h 1 :fill 0xAAFFFFFF})
    (dsl/box {:id :border-b :x 0 :y 31 :w 200 :h 1 :fill 0xAAFFFFFF})
    (dsl/box {:id :border-l :x 0 :y 0 :w 1 :h 32 :fill 0xAAFFFFFF})
    (dsl/box {:id :border-r :x 199 :y 0 :w 1 :h 32 :fill 0xAAFFFFFF})
    (dsl/text {:id :msg :x 8 :y 9 :text "" :color 0xFFFFFFFF})))

(defn- skill-slot-template []
  (dsl/group {:id :slot :h 22 :w 120}
    (dsl/box {:id :key-bg :x 0 :y 0 :w 20 :h 20 :fill 0x80000000})
    (dsl/text {:id :key-label :x 2 :y 2 :text "" :color 0xFFFFFFFF})
    (dsl/image {:id :icon :x 25 :y 3 :w 16 :h 16 :src ""})
    (dsl/text {:id :label :x 45 :y 6 :text "" :color 0xFFFFFFFF})
    (dsl/box {:id :cd-mask :x 0 :y 0 :w 20 :h 20 :fill 0x80000000 :visible? false})
    (dsl/text {:id :cd-text :x 3 :y 10 :text "" :color 0xFFFFFFFF :visible? false})))

(defn- coin-dot-template []
  (dsl/box {:id :dot :w 6 :h 6 :fill 0x80FFD700}))

(defn- vm-wave-template []
  (dsl/image {:id :wave :w 16 :h 16 :src "my_mod:textures/effects/glow_circle.png" :alpha 0.0}))

(defn- debug-line-template []
  (dsl/text {:id :line :text "" :color 0xFFFFFFFF}))

(defn- build-overlay-spec [sw sh]
  (dsl/group {:id :root :w sw :h sh}
    (dsl/box {:id :bg-mask :x 0 :y 0 :w sw :h sh :fill 0x00000000})
    (dsl/box {:id :overload-pulse :x 0 :y 0 :w sw :h sh :fill 0x00000000 :visible? false})
    (dsl/list-node {:id :vm-waves :w sw :h sh :template (vm-wave-template)})
    (dsl/group {:id :charging-layer :w sw :h sh :visible? false}
      (dsl/box {:id :charging-dim :x 0 :y 0 :w sw :h sh :fill 0x6E081220})
      (dsl/box {:id :charging-bar-bg :w 140 :h 8 :fill 0x96081230})
      (dsl/box {:id :charging-bar-fill :w 2 :h 8 :fill 0xC85AD2FF})
      (dsl/text {:id :charging-label :text "" :color 0xFFFFFFFF})
      (dsl/box {:id :charging-mark-v :w 4 :h 16 :fill 0xC878DCFF})
      (dsl/box {:id :charging-mark-h :w 16 :h 4 :fill 0xC878DCFF}))
    (dsl/group {:id :coin-qte-layer :w sw :h sh :visible? false}
      (dsl/box {:id :coin-qte-bg :w 48 :h 48 :fill 0x6414120A})
      (dsl/list-node {:id :coin-qte-dots :w 48 :h 48 :template (coin-dot-template)})
      (dsl/box {:id :coin-qte-marker :w 4 :h 4 :fill 0xF0FFDC50})
      (dsl/text {:id :coin-qte-pct :text "" :color 0xFFFFD700}))
    (dsl/progress {:id :cp-bar :x 8 :y 8 :w 100 :h 10
                   :icon-cutout {:x-offset 84 :w 16}
                   :bg-src "my_mod:textures/guis/cpbar/back_normal.png"
                   :fg-src "my_mod:textures/guis/cpbar/cp.png"})
    (dsl/progress {:id :overload-bar :x 8 :y 22 :w 100 :h 10
                   :bg-src "my_mod:textures/guis/cpbar/back_normal.png"
                   :fg-src "my_mod:textures/guis/cpbar/front_overload.png"})
    (dsl/image {:id :cp-glow :x 8 :y 8 :w 100 :h 10
                :src "my_mod:textures/guis/cpbar/cp.png" :visible? false})
    (dsl/text {:id :activation-dot :x (int (/ sw 2)) :y 10 :text "*" :color 0xFF888888})
    (dsl/text {:id :activation-hint :x (+ (int (/ sw 2)) 12) :y 10 :text "" :color 0xFFCCCCCC :visible? false})
    (dsl/text {:id :cp-numbers :x 115 :y 14 :text "" :color 0xFFFFFFFF :visible? false})
    (dsl/text {:id :ol-numbers :x 115 :y 29 :text "" :color 0xFFFFFFFF :visible? false})
    (dsl/list-node {:id :skill-slots :spacing 2 :w 120 :h 88 :template (skill-slot-template)})
    (dsl/group {:id :preset-row :x (int (/ sw 2)) :y (- sh 45) :w 40 :h 8}
      (dsl/box {:id :preset-prev :x -18 :y 0 :w 8 :h 8 :fill 0x80666666 :visible? false})
      (dsl/box {:id :preset-curr :x -4 :y 0 :w 8 :h 8 :fill 0x80FFAA00 :visible? false}))
    (dsl/crosshair {:id :crosshair :x (int (/ sw 2)) :y (int (/ sh 2)) :visible? false})
    (dsl/list-node {:id :toasts :w sw :h 200 :template (toast-template)})
    (dsl/group {:id :tutorial-notif :w sw :h 200 :visible? false}
      (dsl/image {:id :tut-bg :x 0 :y 15 :w 129 :h 43 :src "" :alpha 0.0})
      (dsl/image {:id :tut-icon :w 83 :h 83 :src "" :alpha 0.0})
      (dsl/text {:id :tut-title :text "" :color 0xFFFFFFFF})
      (dsl/text {:id :tut-content :text "" :color 0xFFFFFFFF}))
    (dsl/list-node {:id :debug-lines :w 300 :h 200 :template (debug-line-template)})
    (dsl/group {:id :overlay-app-layer :w sw :h sh :visible? false}
      (dsl/box {:id :overlay-app-panel :fill 0xC0202020})
      (dsl/text {:id :overlay-app-title :text "" :color 0xFFFFFFFF})
      (dsl/text {:id :overlay-app-subtitle :text "" :color 0xFF888888 :visible? false}))))

(defn- attach-overlay-bindings! [r]
  (let [clock (rt/clock-ms-sig r)
        bg-target (sig/signal-o [0.0 0.0 0.0 0.0])
        cp-target (sig/signal-d 0.0)
        ol-target (sig/signal-d 0.0)
        cp-hint (sig/signal-d 0.0)
        ol-scroll (sig/signal-d 0.0)
        bg-smooth (anim/smoothed-color bg-target clock)
        cp-smooth (anim/smoothed cp-target clock 2.0)
        ol-smooth (anim/smoothed ol-target clock 2.0)
        glow-alpha (anim/breathe clock 1500.0 0.2 0.35)
        jitter-x (anim/jitter-offset clock 0)
        jitter-y (anim/jitter-offset clock 1)
        ^INode bg-mask (ui/node r :bg-mask)]
    (rt/put-user-signal! r :bg-target bg-target)
    (rt/put-user-signal! r :cp-target cp-target)
    (rt/put-user-signal! r :ol-target ol-target)
    (rt/put-user-signal! r :cp-hint cp-hint)
    (rt/put-user-signal! r :ol-scroll ol-scroll)
    (rt/put-user-signal! r :jitter-x jitter-x)
    (rt/put-user-signal! r :jitter-y jitter-y)
    (rt/put-user-signal! r :last-skill-slot-ids (atom []))
    (rt/put-user-signal! r :last-vm-wave-count (atom -1))
    (rt/put-user-signal! r :last-toast-count (atom -1))
    (rt/put-user-signal! r :last-debug-line-count (atom -1))
    (rt/put-user-signal! r :last-coin-dot-count (atom -1))
    (let [b (sig/bind! bg-smooth bg-mask write-fill-from-rgba-o! (rt/get-dirty-bindings-q r))]
      (rt/register-binding! r (.getIdx bg-mask) b))
    (ui/bind! r :cp-bar :progress cp-smooth)
    (ui/bind! r :overload-bar :progress ol-smooth)
    (ui/bind! r :cp-bar :hint cp-hint)
    (ui/bind! r :overload-bar :scroll-offset ol-scroll)
    (ui/bind! r :cp-glow :alpha glow-alpha)
    r))

(defn build-overlay-runtime
  [sw sh]
  (let [r (rt/create-runtime)]
    (rt/build! r (build-overlay-spec sw sh))
    (attach-overlay-bindings! r)))

(defn- overlay-input-state [player-uuid now-ms]
  (let [owner {:player-uuid player-uuid}]
    {:activated-override (bridge/call-adapter :client-overlay-activated-override owner)
     :showing-numbers? @showing-numbers?*
     :last-show-value-change-ms @last-show-value-change-ms*
     :active-overlay-app (bridge/call-adapter :client-active-overlay-app owner)
     :now-ms now-ms}))

(defn- set-visible! [r id visible?]
  (when-let [^INode n (ui/node r id)]
    (when-not (= visible? (.isVisible n))
      (.setVisible n (boolean visible?))
      (.setFlag n node/FLAG-RENDER-DIRTY))))

(defn- update-activation-indicator! [r snapshot]
  (when-let [ind (:activation-indicator snapshot)]
    (let [activated? (:activated ind)
          hint (:hint ind)]
      (set-visible! r :cp-bar (:activated? snapshot))
      (set-visible! r :overload-bar (:activated? snapshot))
      (set-visible! r :cp-glow (and (:activated? snapshot) (:cp-full-glow? snapshot)))
      (when-let [^INode dot (ui/node r :activation-dot)]
        (ui/set-node-prop! r dot :text "*")
        (ui/set-node-prop! r dot :color (if activated? 0xFF00FF00 0xFF888888)))
      (when hint
        (set-visible! r :activation-hint true)
        (ui/set-prop! r :activation-hint :text (str hint)))
      (set-visible! r :activation-hint (boolean (and activated? hint))))))

(defn- update-numbers! [r snapshot]
  (let [texts (:numbers-texts snapshot)]
    (if (seq texts)
      (doseq [[id idx] [[:cp-numbers 0] [:ol-numbers 1]]]
        (when-let [t (nth texts idx nil)]
          (set-visible! r id true)
          (ui/set-prop! r id :text (str (:text t)))
          (when-let [c (:color t)]
            (ui/set-prop! r id :color
                          (if (map? c)
                            (rgb-vec->argb [(:r c) (:g c) (:b c)] (/ (double (:a c)) 255.0))
                            c)))))
      (do
        (set-visible! r :cp-numbers false)
        (set-visible! r :ol-numbers false)))))

(defn- update-preset-indicators! [r snapshot sw]
  (let [indicators (:preset-indicators snapshot)
        prev (first indicators)
        curr (last indicators)]
    (when-let [^INode row (ui/node r :preset-row)]
      (.setX row (double (int (/ sw 2)))))
    (if (seq indicators)
      (do
        (when prev
          (set-visible! r :preset-prev true)
          (when-let [^INode n (ui/node r :preset-prev)]
            (let [fade (double (or (:fade prev) 1.0))
                  alpha (int (* 128 fade))]
              (ui/set-node-prop! r n :fill (bit-or (bit-shift-left alpha 24) 0x00666666)))))
        (when curr
          (set-visible! r :preset-curr true)
          (when-let [^INode n (ui/node r :preset-curr)]
            (let [fade (double (or (:fade curr) 1.0))
                  alpha (int (* 255 fade))]
              (ui/set-node-prop! r n :fill (bit-or (bit-shift-left alpha 24) 0x00FFAA00))))))
      (do
        (set-visible! r :preset-prev false)
        (set-visible! r :preset-curr false)))))

(defn- update-skill-slot-item! [r item slot]
  (let [visual (:visual-state slot :idle)
        alpha (double (or (:alpha slot) 1.0))
        glow (:glow-color slot)
        bg (case visual
             :charge (rgb-vec->argb (or glow [255 173 55]) (* 0.4 alpha))
             :active (rgb-vec->argb (or glow [70 179 255]) (* 0.4 alpha))
             (rgb-vec->argb [0 0 0] (* 0.5 alpha)))
        key-bg (ui/item-node item :key-bg)
        cd-mask (ui/item-node item :cd-mask)
        cd-text (ui/item-node item :cd-text)
        in-cd? (:in-cooldown slot)]
    (ui/set-node-prop! r key-bg :fill bg)
    (ui/set-node-prop! r (ui/item-node item :key-label) :text (str (:key-label slot)))
    (when-let [icon (:skill-icon slot)]
      (ui/set-node-prop! r (ui/item-node item :icon) :src icon))
    (ui/set-node-prop! r (ui/item-node item :label) :text (str (:skill-name slot)))
    (when cd-mask (.setVisible cd-mask (boolean in-cd?)))
    (when cd-text
      (if in-cd?
        (do (.setVisible cd-text true)
            (ui/set-node-prop! r cd-text :text (format "%.1fs" (double (:cooldown-seconds slot 0.0)))))
        (.setVisible cd-text false)))))

(defn- update-skill-slots! [r snapshot]
  (let [slots (:skill-slots snapshot)
        slot-ids (mapv :skill-id slots)
        last-ids* (rt/user-signal r :last-skill-slot-ids)]
    (when (not= @last-ids* slot-ids)
      (reset! last-ids* slot-ids)
      (ui/list-set! r :skill-slots slots
                    (fn [rt item slot-data]
                      (update-skill-slot-item! rt item slot-data))))
    (when-let [^INode list-node (ui/node r :skill-slots)]
      (let [^objects cs (.getChildrenArr list-node)
            n (.getChildCount list-node)]
        (dotimes [i n]
          (when-let [slot (nth slots i nil)]
            (when-let [^INode item (aget cs i)]
              (update-skill-slot-item! r item slot)))))))
    (set-visible! r :skill-slots (seq (:skill-slots snapshot))))

(defn- update-crosshair! [r snapshot]
  (if-let [ch (:crosshair snapshot)]
    (do
      (set-visible! r :crosshair true)
      (when-let [^INode n (ui/node r :crosshair)]
        (.setDSlot n 0 (double (:phase ch)))
        (.setDSlot n 1 (double (:intensity ch)))
        (.setFlag n node/FLAG-RENDER-DIRTY)))
    (set-visible! r :crosshair false)))

(defn- update-overload-pulse! [r snapshot]
  (if-let [intensity (:overload-pulse-intensity snapshot)]
    (when-let [^INode n (ui/node r :overload-pulse)]
      (let [alpha (int (* 40.0 (double intensity)))]
        (set-visible! r :overload-pulse (pos? alpha))
        (ui/set-node-prop! r n :fill (bit-or (bit-shift-left alpha 24) 0x00FF2200))))
    (set-visible! r :overload-pulse false)))

(defn- update-toast-item! [r item toast]
  (let [^INode grp item
        {:keys [x y w h bg borders text]} toast]
    (.setX grp (double x))
    (.setY grp (double y))
    (.setW grp (double w))
    (.setH grp (double h))
    (set-box-node-at! r (ui/item-node item :bg) 0 0 w h bg)
    (doseq [[bid border] [[:border-t (first borders)]
                         [:border-b (second borders)]
                         [:border-l (nth borders 2 nil)]
                         [:border-r (nth borders 3 nil)]]]
      (when border
        (set-box-node-at! r (ui/item-node item bid)
                          (- (:x border) x) (- (:y border) y)
                          (:w border) (:h border)
                          {:r 255 :g 255 :b 255 :a (:a border)})))
    (when-let [^INode msg (ui/item-node item :msg)]
      (.setX msg (double (- (:x text) x)))
      (.setY msg (double (- (:y text) y)))
      (ui/set-node-prop! r msg :text (str (:text text)))
      (ui/set-node-prop! r msg :color (rgba-map->argb (:color text))))))

(defn- update-toasts! [r snapshot]
  (let [toasts (:toasts snapshot)
        count* (rt/user-signal r :last-toast-count)]
    (when (not= @count* (count toasts))
      (reset! count* (count toasts))
      (ui/list-set! r :toasts toasts
                    (fn [rt item data] (update-toast-item! rt item data))))
    (when-let [^INode list-node (ui/node r :toasts)]
      (let [^objects cs (.getChildrenArr list-node)
            n (.getChildCount list-node)]
        (dotimes [i n]
          (when-let [toast (nth toasts i nil)]
            (when-let [^INode item (aget cs i)]
              (update-toast-item! r item toast))))))))

(defn- update-vm-wave-item! [r item wave]
  (let [^INode img (ui/item-node item :wave)]
    (.setX img (double (:x wave)))
    (.setY img (double (:y wave)))
    (.setW img (double (:w wave)))
    (.setH img (double (:h wave)))
    (ui/set-node-prop! r img :src (:src wave))
    (ui/set-node-prop! r img :tint (:tint wave))
    (.setDSlot img 0 (double (:alpha wave)))
    (.setFlag img node/FLAG-RENDER-DIRTY)))

(defn- update-vm-waves! [r snapshot]
  (let [waves (or (:vm-waves snapshot) [])
        count* (rt/user-signal r :last-vm-wave-count)]
    (when (not= @count* (count waves))
      (reset! count* (count waves))
      (ui/list-set! r :vm-waves waves
                    (fn [rt item data] (update-vm-wave-item! rt item data))))
    (when-let [^INode list-node (ui/node r :vm-waves)]
      (let [^objects cs (.getChildrenArr list-node)
            n (.getChildCount list-node)]
        (dotimes [i n]
          (when-let [wave (nth waves i nil)]
            (when-let [^INode item (aget cs i)]
              (update-vm-wave-item! r item wave))))))))

(defn- update-charging-layer! [r snapshot sw sh]
  (if-let [ch (:charging snapshot)]
    (let [{:keys [dim-a bar label crosshair]} ch
          {:keys [x y w h fill-w backdrop accent]} bar
          {:keys [cx cy active?]} crosshair
          mark-a (if active? 200 120)]
      (set-visible! r :charging-layer true)
      (set-box-rgba! r :charging-dim {:r 8 :g 18 :b 32 :a dim-a})
      (set-box-at! r :charging-bar-bg x y w h backdrop)
      (set-box-at! r :charging-bar-fill x y fill-w h accent)
      (when-let [^INode lbl (ui/node r :charging-label)]
        (.setX lbl (double (:x label)))
        (.setY lbl (double (:y label)))
        (ui/set-node-prop! r lbl :text (str (:text label)))
        (ui/set-node-prop! r lbl :color (rgba-map->argb (:color label))))
      (set-box-at! r :charging-mark-v (- cx 2) (- cy 8) 4 16 {:r 120 :g 220 :b 255 :a mark-a})
      (set-box-at! r :charging-mark-h (- cx 8) (- cy 2) 16 4 {:r 120 :g 220 :b 255 :a mark-a}))
    (set-visible! r :charging-layer false)))

(defn- update-coin-qte-dot! [r item dot]
  (set-box-node-at! r (ui/item-node item :dot) (:x dot) (:y dot) (:w dot) (:h dot) (:color dot)))

(defn- update-coin-qte-layer! [r snapshot]
  (if-let [qte (:coin-qte snapshot)]
    (do
      (set-visible! r :coin-qte-layer true)
      (let [{:keys [bg-disc dots marker pct-text]} qte
            dot-count* (rt/user-signal r :last-coin-dot-count)]
        (set-box-node-at! r (ui/node r :coin-qte-bg)
                          (:x bg-disc) (:y bg-disc) (:w bg-disc) (:h bg-disc) (:color bg-disc))
        (when (not= @dot-count* (count dots))
          (reset! dot-count* (count dots))
          (ui/list-set! r :coin-qte-dots dots
                        (fn [rt item dot] (update-coin-qte-dot! rt item dot))))
        (when-let [^INode list-node (ui/node r :coin-qte-dots)]
          (let [^objects cs (.getChildrenArr list-node)
                n (.getChildCount list-node)]
            (dotimes [i n]
              (when-let [dot (nth dots i nil)]
                (when-let [^INode item (aget cs i)]
                  (update-coin-qte-dot! r item dot))))))
        (set-box-node-at! r (ui/node r :coin-qte-marker)
                          (:x marker) (:y marker) (:w marker) (:h marker) (:color marker))
        (when-let [^INode pct (ui/node r :coin-qte-pct)]
          (.setX pct (double (:x pct-text)))
          (.setY pct (double (:y pct-text)))
          (ui/set-node-prop! r pct :text (str (:text pct-text)))
          (ui/set-node-prop! r pct :color (rgba-map->argb (:color pct-text))))))
    (set-visible! r :coin-qte-layer false)))

(defn- update-tutorial-notif! [r snapshot]
  (if-let [n (:tutorial-notification snapshot)]
    (let [{:keys [bg icon title content]} n]
      (set-visible! r :tutorial-notif true)
      (when-let [^INode bg-n (ui/node r :tut-bg)]
        (.setX bg-n (double (:x bg)))
        (.setY bg-n (double (:y bg)))
        (.setW bg-n (double (:w bg)))
        (.setH bg-n (double (:h bg)))
        (ui/set-node-prop! r bg-n :src (:src bg))
        (.setDSlot bg-n 0 (double (:alpha bg))))
      (when-let [^INode icon-n (ui/node r :tut-icon)]
        (.setX icon-n (double (:x icon)))
        (.setY icon-n (double (:y icon)))
        (.setW icon-n (double (:w icon)))
        (.setH icon-n (double (:h icon)))
        (ui/set-node-prop! r icon-n :src (:src icon))
        (.setDSlot icon-n 0 (double (:alpha icon))))
      (when-let [^INode t (ui/node r :tut-title)]
        (.setX t (double (:x title)))
        (.setY t (double (:y title)))
        (ui/set-node-prop! r t :text (str (:text title)))
        (ui/set-node-prop! r t :color (rgba-map->argb (:color title))))
      (when-let [^INode c (ui/node r :tut-content)]
        (.setX c (double (:x content)))
        (.setY c (double (:y content)))
        (ui/set-node-prop! r c :text (str (:text content)))
        (ui/set-node-prop! r c :color (rgba-map->argb (:color content)))))
    (set-visible! r :tutorial-notif false)))

(defn- update-debug-lines! [r snapshot]
  (let [lines (:debug-lines snapshot)
        count* (rt/user-signal r :last-debug-line-count)]
    (when (not= @count* (count lines))
      (reset! count* (count lines))
      (ui/list-set! r :debug-lines lines
                    (fn [rt item line]
                      (let [^INode n (ui/item-node item :line)]
                        (.setX n (double (:x line)))
                        (.setY n (double (:y line)))
                        (ui/set-node-prop! r n :text (str (:text line)))
                        (ui/set-node-prop! r n :color (long (:color line)))))))
    (when-let [^INode list-node (ui/node r :debug-lines)]
      (let [^objects cs (.getChildrenArr list-node)
            n (.getChildCount list-node)]
        (dotimes [i n]
          (when-let [line (nth lines i nil)]
            (when-let [^INode item (aget cs i)]
              (let [^INode txt (ui/item-node item :line)]
                (.setX txt (double (:x line)))
                (.setY txt (double (:y line)))
                (ui/set-node-prop! r txt :text (str (:text line)))
                (ui/set-node-prop! r txt :color (long (:color line)))))))))
    (set-visible! r :debug-lines (seq lines))))

(defn- update-overlay-app! [r snapshot]
  (if-let [app-ui (:overlay-app-ui snapshot)]
    (let [{:keys [panel title subtitle]} app-ui]
      (set-visible! r :overlay-app-layer true)
      (set-box-at! r :overlay-app-panel (:x panel) (:y panel) (:w panel) (:h panel) (:color panel))
      (when-let [^INode t (ui/node r :overlay-app-title)]
        (.setX t (double (:x title)))
        (.setY t (double (:y title)))
        (ui/set-node-prop! r t :text (str (:text title)))
        (ui/set-node-prop! r t :color (long (:color title))))
      (if subtitle
        (do
          (set-visible! r :overlay-app-subtitle true)
          (when-let [^INode s (ui/node r :overlay-app-subtitle)]
            (.setX s (double (:x subtitle)))
            (.setY s (double (:y subtitle)))
            (ui/set-node-prop! r s :text (str (:text subtitle)))
            (ui/set-node-prop! r s :color (long (:color subtitle)))))
        (set-visible! r :overlay-app-subtitle false))
      (set-visible! r :cp-bar false)
      (set-visible! r :overload-bar false)
      (set-visible! r :skill-slots false))
    (set-visible! r :overlay-app-layer false)))

(defn- apply-jitter! [r interfered?]
  (when-let [^INode root (ui/node r :root)]
    (if interfered?
      (let [jx (sig/sget-d (rt/user-signal r :jitter-x))
            jy (sig/sget-d (rt/user-signal r :jitter-y))]
        (when (or (not= jx (.getX root)) (not= jy (.getY root)))
          (.setX root jx)
          (.setY root jy)
          (.setFlag root node/FLAG-LAYOUT-DIRTY)))
      (when (or (not= 0.0 (.getX root)) (not= 0.0 (.getY root)))
        (.setX root 0.0)
        (.setY root 0.0)
        (.setFlag root node/FLAG-LAYOUT-DIRTY)))))

(defn- apply-screen-size! [r sw sh]
  (doseq [id [:root :bg-mask :overload-pulse]]
    (when-let [^INode n (ui/node r id)]
      (when (or (not= (double sw) (.getW n)) (not= (double sh) (.getH n)))
        (.setW n (double sw))
        (.setH n (double sh))
        (.setFlag n node/FLAG-LAYOUT-DIRTY)))))

(defn update-overlay-signals!
  "Per-frame update. Called from overlay-host update-fn."
  [r]
  (when-let [player-uuid (local-player-uuid)]
    (let [now-ms (sig/sget-l (rt/clock-ms-sig r))
          sw (int (rt/screen-w r))
          sh (int (rt/screen-h r))
          snapshot (reactive-hud/build-snapshot player-uuid sw sh (overlay-input-state player-uuid now-ms))]
      (apply-screen-size! r sw sh)
      (when-let [bg-target (rt/user-signal r :bg-target)]
        (sig/sset-o! bg-target (mask-vec (:background-mask snapshot {:r 0.0 :g 0.0 :b 0.0 :a 0.0}))))
      (if (:overlay-app snapshot)
        (update-overlay-app! r snapshot)
        (do
          (when-let [cp-target (rt/user-signal r :cp-target)]
            (sig/sset-d! cp-target (if (:activated? snapshot)
                                     (double (or (:percent (:cp-bar snapshot)) 0.0))
                                     0.0)))
          (when-let [ol-target (rt/user-signal r :ol-target)]
            (sig/sset-d! ol-target (if (:activated? snapshot)
                                     (double (or (:percent (:overload-bar snapshot)) 0.0))
                                     0.0)))
          (when-let [cp-hint (rt/user-signal r :cp-hint)]
            (sig/sset-d! cp-hint (double (or (:hint-percent (:cp-bar snapshot)) 0.0))))
          (when-let [ol-scroll (rt/user-signal r :ol-scroll)]
            (sig/sset-d! ol-scroll (double (or (:scroll-offset (:overload-bar snapshot)) 0.0))))
          (when-let [^INode ol-bar (ui/node r :overload-bar)]
            (let [ol (:overload-bar snapshot)
                  overloaded? (boolean (:overloaded ol))]
              (ui/set-prop! r :overload-bar :bg-src
                            (if overloaded?
                              "my_mod:textures/guis/cpbar/back_overload.png"
                              "my_mod:textures/guis/cpbar/back_normal.png"))))
          (set-visible! r :cp-glow (and (:activated? snapshot) (:cp-full-glow? snapshot)))
          (update-activation-indicator! r snapshot)
          (update-numbers! r snapshot)
          (update-preset-indicators! r snapshot sw)
          (update-skill-slots! r snapshot)
          (update-crosshair! r snapshot)
          (update-overload-pulse! r snapshot)))
      (update-vm-waves! r snapshot)
      (update-charging-layer! r snapshot sw sh)
      (update-coin-qte-layer! r snapshot)
      (update-toasts! r snapshot)
      (update-tutorial-notif! r snapshot)
      (update-debug-lines! r snapshot)
      (apply-jitter! r (:interfered? snapshot)))))
