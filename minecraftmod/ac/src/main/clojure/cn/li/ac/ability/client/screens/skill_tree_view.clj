(ns cn.li.ac.ability.client.screens.skill-tree-view
  "Native reactive skill tree UI — builds/refreshes UiRt nodes from render data.
   No draw-ops; shared by full-screen and developer-panel embed."
  (:require [cn.li.ac.ability.client.screens.skill-tree :as logic]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.texture-registry :as tex-reg]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.node :as node])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

(def ^:private back-scale-inv 0.9900990099009901)

(defn- clamp01 [v] (max 0.0 (min 1.0 (double v))))

(defn- tex-src [registry-key]
  (when-let [path (tex-reg/get-texture-path registry-key)]
    (modid/namespaced-path (str path))))

(defn- parallax-offset
  [mx my w h]
  (let [mx01 (clamp01 (/ (double mx) (max 1.0 (double w))))
        my01 (clamp01 (/ (double my) (max 1.0 (double h))))]
    [(* (- mx01 0.5) 10.0) (* (- my01 0.5) 10.0)]))

(defn- bg-uv [mx my w h]
  (let [mx01 (clamp01 (/ (double mx) (max 1.0 (double w))))
        my01 (clamp01 (/ (double my) (max 1.0 (double h))))
        bg-dx (* (- mx01 0.5) 0.01)
        bg-dy (* (- my01 0.5) 0.01)
        scale-fn (fn [x] (+ (* (- x 0.5) back-scale-inv) 0.5))]
    [(float (scale-fn bg-dx)) (float (scale-fn bg-dy))]))

(defn- line-color [m-alpha child-learned?]
  (let [line-alpha (* (or m-alpha 0.7) (if child-learned? 1.0 0.4))
        alpha-byte (int (* 255.0 (clamp01 line-alpha)))]
    (unchecked-int (bit-or (bit-shift-left alpha-byte 24) 0xFFFFFF))))

(defn- lerp [a b t]
  (+ a (* (- b a) (clamp01 t))))

(defn- connection-spec [{:keys [from-x from-y to-x to-y m-alpha child-learned? lb]} pdx pdy]
  (let [blend (double (if (some? lb) lb 1.0))
        x1 (lerp (+ from-x 8.0) (+ to-x 8.0) blend)
        y1 (lerp (+ from-y 8.0) (+ to-y 8.0) blend)
        x2 (+ to-x 8.0) y2 (+ to-y 8.0)]
    (when (pos? blend)
      {:kind :line
       :props {:x 0.0 :y 0.0 :w 0.0 :h 0.0
               :x1 (- x1 pdx) :y1 (- y1 pdy) :x2 (- x2 pdx) :y2 (- y2 pdy)
               :thickness 5.5
               :alpha (clamp01 (* (or m-alpha 0.7) (if child-learned? 1.0 0.4)))
               :color (line-color m-alpha child-learned?)}})))

(defn- skill-node-spec
  [{:keys [x y skill-icon learned exp m-alpha skill-id]} pdx pdy]
  (let [sx (- (double x) pdx) sy (- (double y) pdy)
        ta logic/total-size
        pa logic/prog-align
        ia logic/align
        da logic/draw-align
        alpha (float (clamp01 (or m-alpha 0.7)))
        icon-alpha (float (clamp01 (* alpha 0.9)))]
    {:kind :group
     :props {:x sx :y sy :w ta :h ta}
     :children
     (vec
       (remove nil?
         [(merge {:kind :image :props {:x da :y da :w ta :h ta :src (tex-src :skill-back) :alpha alpha}})
          (merge {:kind :image :props {:x pa :y pa :w logic/prog-size :h logic/prog-size
                                       :src (tex-src :skill-outline) :alpha (* alpha 0.6)}})
          (merge {:kind :image :props {:x ia :y ia :w logic/icon-size :h logic/icon-size
                                       :src skill-icon :alpha icon-alpha}})
          (when learned
            {:kind :shader-progress
             :props {:x pa :y pa :w logic/prog-size :h logic/prog-size
                     :progress (float (or exp 0.0))
                     :shader-props {:shader-id :ring-progbar
                                    :texture-0 (tex-src :skill-outline)
                                    :texture-1 (tex-src :skill-mask)}}})]))}))

(defn- shell-spec [w h]
  {:kind :group :id :root
   :props {:w (double w) :h (double h) :align-w :center :align-h :center}
   :children
   [{:kind :image :id :bg :props {:x 0.0 :y 0.0 :w (double w) :h (double h)
                                  :src (tex-src :bg-area) :alpha 1.0}}
    {:kind :box :id :overlay :props {:x 0.0 :y 0.0 :w (double w) :h (double h) :fill 0xA0101010}}
    {:kind :text :id :cat-text :props {:x 12.0 :y 8.0 :w 200.0 :h 12.0 :text "" :font-size 9.0 :color 0xFFFFFFFF}}
    {:kind :text :id :lvl-text :props {:x 12.0 :y 22.0 :w 200.0 :h 12.0 :text "" :font-size 9.0 :color 0xFFE8E8E8}}
    {:kind :text :id :cp-text :props {:x 12.0 :y 36.0 :w 200.0 :h 12.0 :text "" :font-size 9.0 :color 0xFFAED7FF}}
    {:kind :text :id :ov-text :props {:x 12.0 :y 50.0 :w 200.0 :h 12.0 :text "" :font-size 9.0 :color 0xFFFFB8A6}}
    {:kind :group :id :tree-layer :props {:x 0.0 :y 0.0 :w (double w) :h (double h)}}
    {:kind :group :id :tooltip :props {:x 230.0 :y 8.0 :w 180.0 :h 68.0}
     :children
     [{:kind :box :id :tip-bg :props {:x 0.0 :y 0.0 :w 180.0 :h 68.0 :fill 0xC0202020}}
      {:kind :text :id :tip-name :props {:x 6.0 :y 6.0 :w 170.0 :h 12.0 :text "" :font-size 12.0 :color 0xFFFFFFFF}}
      {:kind :text :id :tip-desc :props {:x 6.0 :y 20.0 :w 170.0 :h 24.0 :text "" :font-size 9.0 :color 0xFFDDDDDD}}
      {:kind :text :id :tip-prog :props {:x 6.0 :y 34.0 :w 170.0 :h 12.0 :text "" :font-size 8.0 :color 0xFFDDDDDD}}
      {:kind :text :id :tip-state :props {:x 6.0 :y 48.0 :w 170.0 :h 12.0 :text "" :font-size 8.0 :color 0xFF88FF88}}]}
    {:kind :group :id :popup-layer :props {:x 0.0 :y 0.0 :w (double w) :h (double h)}}
    {:kind :box :id :level-btn :props {:x 10.0 :y 200.0 :w 80.0 :h 20.0 :fill 0xAA22AA22 :hover-tint 0x33FFFFFF}}
    {:kind :text :id :level-lbl :props {:x 18.0 :y 206.0 :w 64.0 :h 12.0 :text "Level Up"
                                        :font-size 9.0 :color 0xFFFFFFFF}}
    {:kind :box :id :input-layer :props {:x 0.0 :y 0.0 :w (double w) :h (double h) :fill 0x00000000}}]})

(defn ensure-shell!
  [^UiRt rt w h]
  (when-not (ui/node rt :root)
    (rt/build! rt (shell-spec w h))))

(defn- set-visible! [^INode n visible?]
  (when n (.setVisible n (boolean visible?))))

(defn- refresh-header! [^UiRt rt ab]
  (ui/set-prop! rt :cat-text :text (str "Category: " (:category-name ab)))
  (ui/set-prop! rt :lvl-text :text (format "Level: %d" (int (or (:level ab) 0))))
  (ui/set-prop! rt :cp-text :text (format "CP: %.0f / %.0f"
                                          (double (get-in ab [:cp :cur] 0.0))
                                          (double (get-in ab [:cp :max] 0.0))))
  (ui/set-prop! rt :ov-text :text (format "Overload: %.0f / %.0f"
                                          (double (get-in ab [:overload :cur] 0.0))
                                          (double (get-in ab [:overload :max] 0.0)))))

(defn- refresh-tooltip! [^UiRt rt rd]
  (let [^INode tip (ui/node rt :tooltip)
        hover-id (:hover-skill rd)
        hover-node (when hover-id (first (filter #(= (:skill-id %) hover-id) (:skill-nodes rd))))]
    (set-visible! tip (some? hover-node))
    (when hover-node
      (ui/set-prop! rt :tip-name :text (str (:skill-name hover-node)))
      (ui/set-prop! rt :tip-desc :text (str (:skill-description hover-node)))
      (ui/set-prop! rt :tip-prog :text (format "Progress: %d%%" (int (* 100.0 (:exp hover-node)))))
      (ui/set-prop! rt :tip-state :text (if (:learned hover-node) "Learned" "Not learned"))
      (ui/set-prop! rt :tip-state :color (if (:learned hover-node) 0xFF88FF88 0xFFFF8888)))))

(defn- clear-popup! [^UiRt rt]
  (when-let [^INode layer (ui/node rt :popup-layer)]
    (rt/clear-children! rt layer)))

(defn- refresh-detail-popup! [^UiRt rt node]
  (clear-popup! rt)
  (when-let [^INode layer (ui/node rt :popup-layer)]
    (rt/build-child! rt
      {:kind :box :props {:x 0.0 :y 0.0 :w 420.0 :h 260.0 :fill 0xB3000000}}
      layer)
    (let [cx 210.0 cy 130.0
          back-sz 50.0 icon-sz 27.0
          back-x (- cx 25.0) back-y (- cy 25.0)
          icon-x (+ back-x 11.0) icon-y (+ back-y 11.0)
          {:keys [skill-name skill-icon skill-level skill-description learned exp]} node]
      (rt/build-child! rt {:kind :image :props {:x back-x :y back-y :w back-sz :h back-sz
                                                 :src (tex-src :skill-back)}} layer)
      (rt/build-child! rt {:kind :image :props {:x icon-x :y icon-y :w icon-sz :h icon-sz
                                                 :src skill-icon}} layer)
      (when learned
        (rt/build-child! rt {:kind :shader-progress
                              :props {:x back-x :y back-y :w back-sz :h back-sz
                                      :progress (float (or exp 0.0))
                                      :shader-props {:shader-id :ring-progbar
                                                     :texture-0 (tex-src :skill-view-outline)
                                                     :texture-1 (tex-src :skill-mask)}}}
          layer))
      (when-let [dev (:dev-state node)]
        (when (:is-developing? dev)
          (rt/build-child! rt {:kind :shader-progress
                                :props {:x back-x :y back-y :w back-sz :h back-sz
                                        :progress (float (:progress dev 0.0))
                                        :shader-props {:shader-id :ring-progbar
                                                       :texture-0 (tex-src :skill-view-outline)
                                                       :texture-1 (tex-src :skill-mask)}}}
            layer)))
      (rt/build-child! rt {:kind :text :props {:x cx :y (+ cy 23.0) :w 200.0 :h 14.0
                                                 :text (str skill-name " (LV " skill-level ")")
                                                 :font-size 12.0 :color 0xFFFFFFFF}} layer)
      (rt/build-child! rt {:kind :text :props {:x cx :y (+ cy 40.0) :w 280.0 :h 40.0
                                                 :text (if learned
                                                         (str (i18n/translate "skill_tree.my_mod.skill_exp") " "
                                                              (int (* 100.0 (or exp 0.0))) "%")
                                                         (i18n/translate "skill_tree.my_mod.skill_not_learned"))
                                                 :font-size (if learned 8.0 10.0)
                                                 :color (if learned 0xFFa1e1ff 0xFFff5555)}} layer)
      (when (and learned skill-description)
        (rt/build-child! rt {:kind :text :props {:x cx :y (+ cy 55.0) :w 280.0 :h 40.0
                                                   :text skill-description :font-size 9.0 :color 0xFFDDDDDD}} layer))
      (when (not learned)
        (let [btn-x (- cx 16.0) btn-y (+ cy 92.0)]
          (rt/build-child! rt {:kind :image :props {:x btn-x :y btn-y :w 32.0 :h 16.0
                                                     :src (tex-src :tex-button)}} layer)
          (rt/build-child! rt {:kind :text :props {:x cx :y (+ btn-y 4.0) :w 32.0 :h 12.0
                                                   :text "LEARN" :font-size 9.0 :color 0xFF101010}} layer))))))

(defn- refresh-levelup-popup! [^UiRt rt target-level dev-state]
  (clear-popup! rt)
  (when-let [^INode layer (ui/node rt :popup-layer)]
    (rt/build-child! rt {:kind :box :props {:x 0.0 :y 0.0 :w 420.0 :h 260.0 :fill 0xB3000000}} layer)
    (let [cx 210.0 cy 130.0
          icon-x (- cx 25.0) icon-y (- cy 25.0)
          cond-icon (modid/asset-path "textures" (str "abilities/condition/any" target-level ".png"))
          result (:result dev-state)
          progress (cond
                     (:is-developing? dev-state) (double (:progress dev-state 0.0))
                     (= result :success) 1.0
                     :else 0.0)
          hint (cond
                 (:is-developing? dev-state) (i18n/translate "skill_tree.my_mod.dev_developing")
                 (= result :success) (i18n/translate "skill_tree.my_mod.dev_successful")
                 (= result :failed) (i18n/translate "skill_tree.my_mod.dev_failed")
                 (= (:error dev-state) :low-energy) (i18n/translate "skill_tree.my_mod.noenergy")
                 :else (i18n/translate "skill_tree.my_mod.level_question"))]
      (rt/build-child! rt {:kind :image :props {:x icon-x :y icon-y :w 50.0 :h 50.0
                                                 :src (tex-src :skill-back)}} layer)
      (rt/build-child! rt {:kind :image :props {:x (+ icon-x 11.5) :y (+ icon-y 11.5) :w 27.0 :h 27.0
                                                 :src cond-icon}} layer)
      (rt/build-child! rt {:kind :shader-progress
                            :props {:x icon-x :y icon-y :w 50.0 :h 50.0 :progress (float progress)
                                    :shader-props {:shader-id :ring-progbar
                                                   :texture-0 (tex-src :skill-view-outline)
                                                   :texture-1 (tex-src :skill-mask)}}} layer)
      (rt/build-child! rt {:kind :text :props {:x cx :y (+ cy 28.0) :w 200.0 :h 14.0
                                                 :text (format (i18n/translate "skill_tree.my_mod.uplevel")
                                                               (str "Lv." target-level))
                                                 :font-size 12.0 :color 0xFFFFFFFF}} layer)
      (when hint
        (rt/build-child! rt {:kind :text :props {:x cx :y (+ cy 51.0) :w 240.0 :h 14.0
                                                   :text hint :font-size 9.0 :color 0xAAFFFFFF}} layer))
      (when (and (not (:is-developing? dev-state)) (nil? result))
        (let [btn-x (- cx 16.0) btn-y (+ cy 70.0)]
          (rt/build-child! rt {:kind :image :props {:x btn-x :y btn-y :w 32.0 :h 16.0
                                                     :src (tex-src :tex-button)}} layer)
          (rt/build-child! rt {:kind :text :props {:x cx :y (+ btn-y 4.0) :w 32.0 :h 12.0
                                                   :text "LEARN" :font-size 9.0 :color 0xFF101010}} layer))))))

(defn- rebuild-tree-layer! [^UiRt rt rd pdx pdy]
  (when-let [^INode layer (ui/node rt :tree-layer)]
    (rt/clear-children! rt layer)
    (doseq [conn (:connections rd)]
      (when-let [spec (connection-spec conn pdx pdy)]
        (rt/build-child! rt spec layer)))
    (doseq [n (:skill-nodes rd)]
      (rt/build-child! rt (skill-node-spec n pdx pdy) layer))
    (rt/mark-tree-dirty! rt)))

(defn refresh-screen!
  "Refresh full-screen skill tree (420×260)."
  [^UiRt rt owner mx my]
  (let [w 420 h 260
        _ (ensure-shell! rt w h)
        st (logic/screen-state-snapshot owner)
        rd (logic/build-screen-render-data owner)
        [pdx pdy] (parallax-offset mx my w h)
        [bu bv] (bg-uv mx my w h)]
    (when rd
      (when-let [^INode bg (ui/node rt :bg)]
        (.setDSlot bg 1 (double bu))
        (.setDSlot bg 2 (double bv))
        (.setFlag bg node/FLAG-RENDER-DIRTY))
      (refresh-header! rt (:ability-info rd))
      (rebuild-tree-layer! rt rd pdx pdy)
      (refresh-tooltip! rt rd)
      (cond
        (:showing-level-up-popup? st)
        (refresh-levelup-popup! rt (inc (int (get-in rd [:ability-info :level] 1)))
                                (:level-up-dev-state st))
        (:selected-skill st)
        (let [sel (:selected-skill st)
              node (first (filter #(= (:skill-id %) sel) (:skill-nodes rd)))]
          (if node (refresh-detail-popup! rt node) (clear-popup! rt)))
        :else (clear-popup! rt))
      (let [show-level? (and (get-in rd [:ability-info :can-level-up])
                             (not (:showing-level-up-popup? st)))]
        (set-visible! (ui/node rt :level-btn) show-level?)
        (set-visible! (ui/node rt :level-lbl) show-level?))
      (rt/mark-tree-dirty! rt))))

(defn refresh-embedded!
  "Refresh embedded skill tree (developer panel area)."
  [^UiRt rt render-data mx my w h hover-id]
  (ensure-shell! rt w h)
  (let [rd (assoc render-data :hover-skill hover-id)
        [pdx pdy] (parallax-offset mx my w h)]
    (rebuild-tree-layer! rt rd pdx pdy)
    (refresh-tooltip! rt rd)
    (clear-popup! rt)
    (set-visible! (ui/node rt :level-btn) false)
    (set-visible! (ui/node rt :level-lbl) false)
    (set-visible! (ui/node rt :overlay) false)
    (doseq [id [:cat-text :lvl-text :cp-text :ov-text]]
      (set-visible! (ui/node rt id) false))
    (rt/mark-tree-dirty! rt)))

(defn refresh-detail-overlay!
  "Standalone detail popup runtime (developer panel overlay)."
  [^UiRt rt node]
  (when-not (ui/node rt :root)
    (rt/build! rt {:kind :group :id :root :props {:w 400.0 :h 187.0}
                   :children [{:kind :group :id :popup-layer :props {:x 0.0 :y 0.0 :w 400.0 :h 187.0}}]}))
  (refresh-detail-popup! rt node)
  (rt/mark-tree-dirty! rt))

(defn refresh-levelup-overlay!
  [^UiRt rt target-level dev-state]
  (when-not (ui/node rt :root)
    (rt/build! rt {:kind :group :id :root :props {:w 400.0 :h 187.0}
                   :children [{:kind :group :id :popup-layer :props {:x 0.0 :y 0.0 :w 400.0 :h 187.0}}]}))
  (refresh-levelup-popup! rt target-level dev-state)
  (rt/mark-tree-dirty! rt))
