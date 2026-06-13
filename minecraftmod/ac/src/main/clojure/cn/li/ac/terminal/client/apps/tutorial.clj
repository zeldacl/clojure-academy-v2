(ns cn.li.ac.terminal.client.apps.tutorial
  "CLIENT-ONLY: Tutorial GUI."
  (:require [cn.li.ac.tutorial.content :as tut-content]
            [cn.li.ac.tutorial.registry :as tut-registry]
            [cn.li.ac.tutorial.markdown-renderer :as mr]
            [cn.li.ac.tutorial.client.preview :as preview]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.util.log :as log]))

(def gw 427.0) (def gh 240.0)
(def lw 85.0)  (def lx 7.0)  (def lix 6.6) (def liy 7.0)
(def liw 72.0) (def eh 16.0) (def cx (+ lx lw)) (def cw 172.0)
(def cox 2.0)  (def cow (- cw (* 2 cox))) (def coy 4.0)
(def coh (- gh (* 2 coy))) (def rx (+ cx cw)) (def rw 158.5)
(def rix 6.0)  (def riw (- rw (* 2 rix)))

(defn open! [player]
  (log/info "Opening tutorial GUI")
  (let [root (cgui-core/create-widget :size [gw gh])
        entries (tut-registry/all-tutorials)
        activated (into #{} (keep #(when (:default-installed? %) (:id %)) entries))
        pvs (atom (preview/create-preview-state :welcome))]
    ;; bg
    (let [bg (cgui-core/create-widget :pos [0 0] :size [gw gh])]
      (comp/add-component! bg (comp/draw-texture
                               (modid/asset-path "textures/guis" "data_terminal/app_back.png")))
      (cgui-core/add-widget! root bg))
    ;; left panel — tutorial entries
    (let [lp (cgui-core/create-widget :pos [lx 0] :size [lw gh])]
      (comp/add-component! lp (comp/draw-texture
                               (modid/asset-path "textures/guis" "window_tutorial_left.png")))
      (doseq [[idx tut] (map-indexed vector entries)]
        (let [y (+ liy (* idx eh))
              active? (contains? activated (:id tut))
              title (or (:title (tut-content/load-tutorial-content (:id tut)))
                        (name (:id tut)))
              ew (cgui-core/create-widget :pos [lix y] :size [liw eh])]
          (comp/add-component! ew (comp/text-box :text title :font-size 8.0
                                                 :color (if active? 0xFFFFFFFF 0xFF808080)
                                                 :align :left))
          (events/on-left-click ew
            (fn [_]
              (let [cd (tut-content/load-tutorial-content (:id tut))
                    segs (mr/render-segments (:content cd) nil)]
                (dotimes [n 200]
                  (when-let [w (cgui-core/find-widget root (str "ct-" n))]
                    (cgui-core/remove-widget! root w)))
                (loop [sg segs y coy n 0]
                  (when (seq sg)
                    (let [{:keys [text font-size color bold?]} (first sg)
                          w (cgui-core/create-widget
                             :pos [(+ cx cox) y] :size [cow mr/line-height])]
                      (comp/add-component! w
                        (comp/text-box :text text :font-size font-size
                                       :color color :font (when bold? :ac-bold)))
                      (cgui-core/set-name! w (str "ct-" n))
                      (cgui-core/add-widget! root w)
                      (recur (rest sg) (+ y mr/line-height) (inc n)))))
                (reset! pvs (preview/create-preview-state (:id tut)))
                (when-let [area (cgui-core/find-widget root "preview-area")]
                  (when-let [old (cgui-core/find-widget area "current-preview")]
                    (cgui-core/remove-widget! area old))
                  (when-let [spec (preview/current-preview-spec pvs)]
                    (when-let [pw (preview/build-preview-widget spec)]
                      (cgui-core/set-name! pw "current-preview")
                      (cgui-core/add-widget! area pw))))
                (when-let [bw (cgui-core/find-widget root "brief-text")]
                  (when-let [tb (comp/get-textbox-component bw)]
                    (comp/set-text! tb (or (:brief cd) ""))))
                (when-let [ti (cgui-core/find-widget root "tag-icon")]
                  (when-let [od (comp/get-drawtexture-component ti)]
                    (comp/remove-component! ti od))
                  (comp/add-component! ti
                    (comp/draw-texture
                     (get preview/tag-textures
                          (preview/current-tag pvs)
                          (preview/tag-textures :view))))))))
          (cgui-core/add-widget! lp ew)))
      (cgui-core/add-widget! root lp))
    ;; center panel
    (cgui-core/add-widget! root (cgui-core/create-widget :pos [cx 0] :size [cw gh]))
    ;; right panel — preview + brief + tag
    (let [rp (cgui-core/create-widget :pos [rx 0] :size [rw gh])]
      (comp/add-component! rp (comp/draw-texture
                               (modid/asset-path "textures/guis" "window_tutorial_left.png")))
      ;; preview-area
      (let [pa (cgui-core/create-widget :pos [8 8] :size [(- rw 16) (- gh 98)])]
        (cgui-core/set-name! pa "preview-area")
        (cgui-core/add-widget! rp pa))
      ;; nav buttons (refresh-preview helper)
      (let [refresh-preview!
            (fn []
              (when-let [area (cgui-core/find-widget root "preview-area")]
                (when-let [old (cgui-core/find-widget area "current-preview")]
                  (cgui-core/remove-widget! area old))
                (when-let [spec (preview/current-preview-spec pvs)]
                  (when-let [pw (preview/build-preview-widget spec)]
                    (cgui-core/set-name! pw "current-preview")
                    (cgui-core/add-widget! area pw))))
              (when-let [bw (cgui-core/find-widget root "brief-text")]
                (when-let [tb (comp/get-textbox-component bw)]
                  (comp/set-text! tb (preview/display-text pvs))))
              (when-let [ti (cgui-core/find-widget root "tag-icon")]
                (when-let [od (comp/get-drawtexture-component ti)]
                  (comp/remove-component! ti od))
                (comp/add-component! ti
                  (comp/draw-texture
                   (get preview/tag-textures
                        (preview/current-tag pvs)
                        (preview/tag-textures :view))))))]
        (let [bl (cgui-core/create-widget :pos [8 50] :size [16 60])]
          (comp/add-component! bl (comp/text-box :text "<" :font-size 12.0 :color 0xFFFFFFFF))
          (events/on-left-click bl (fn [_] (preview/cycle-preview! pvs :prev) (refresh-preview!)))
          (cgui-core/add-widget! rp bl))
        (let [br (cgui-core/create-widget :pos [(- rw 24) 50] :size [16 60])]
          (comp/add-component! br (comp/text-box :text ">" :font-size 12.0 :color 0xFFFFFFFF))
          (events/on-left-click br (fn [_] (preview/cycle-preview! pvs :next) (refresh-preview!)))
          (cgui-core/add-widget! rp br)))
      ;; tag-icon
      (let [ti (cgui-core/create-widget :pos [12 (- gh 94)] :size [18 18])]
        (comp/add-component! ti (comp/draw-texture (preview/tag-textures :view)))
        (cgui-core/set-name! ti "tag-icon")
        (cgui-core/add-widget! rp ti))
      ;; brief-text
      (let [bw (cgui-core/create-widget :pos [rix (- gh 82)] :size [riw 69])]
        (comp/add-component! bw (comp/text-box :text "Select a tutorial"
                                               :font-size 8.0 :color 0xFFFFFFFF))
        (cgui-core/set-name! bw "brief-text")
        (cgui-core/add-widget! rp bw))
      (cgui-core/add-widget! root rp))
    ;; Logo widgets + first-open fade-in animation (original AC GuiTutorial)
    (let [l0 (cgui-core/create-widget :pos [(- (/ gw 2) 112) 20] :size [224 137])]
      (comp/add-component! l0 (comp/draw-texture (modid/asset-path "textures/guis" "tutorial/logo0.png") [255 255 255 0]))
      (cgui-core/set-name! l0 "logo0")
      (cgui-core/add-widget! root l0))
    (let [l1 (cgui-core/create-widget :pos [(- (/ gw 2) 112) 157] :size [224 59])]
      (comp/add-component! l1 (comp/draw-texture (modid/asset-path "textures/guis" "tutorial/logo1.png") [255 255 255 0]))
      (cgui-core/set-name! l1 "logo1")
      (cgui-core/add-widget! root l1))
    (let [anim-start (atom nil)]
      (events/on-frame root
        (fn [_]
          (when-not @anim-start (reset! anim-start (System/currentTimeMillis)))
          (let [elapsed (- (System/currentTimeMillis) @anim-start)
                t (min 1.0 (/ elapsed 2000.0))
                alpha (int (* 255.0 t t (- 3.0 (* 2.0 t))))]
            (when (< t 1.0)
              (doseq [nm ["logo0" "logo1"]]
                (when-let [lw (cgui-core/find-widget root nm)]
                  (when-let [dt (comp/get-drawtexture-component lw)]
                    (swap! (:state dt) assoc :color
                           (unchecked-int (bit-or (bit-shift-left alpha 24) 0x00FFFFFF)))))))
            (when (>= t 1.0)
              (doseq [nm ["logo0" "logo1"]]
                (when-let [lw (cgui-core/find-widget root nm)]
                  (cgui-core/set-visible! lw false))))))))
    ;; 3D rotating item preview (Phase 6)
    (let [preview-item (atom nil)]
      (add-watch pvs :preview-3d
        (fn [_ _ _ st]
          (when-let [spec (preview/current-preview-spec st)]
            (reset! preview-item (or (:item-id spec) (:texture spec))))))
      (client-bridge/open-simple-gui! root "MisakaCloud Terminal"
        {:preview-item-atom preview-item}))))
