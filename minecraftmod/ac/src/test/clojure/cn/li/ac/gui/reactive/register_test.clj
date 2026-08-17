(ns cn.li.ac.gui.reactive.register-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.gui.reactive.register :as register]
            [cn.li.ac.ability.client.presentation-hud :as hud]
            [cn.li.ac.client.effect-controller :as effect-controller]
            [cn.li.presentation.compiler.render :as presentation-render])
  (:import [cn.li.mcmod.runtime FramePacket RenderPass RenderStage RenderCommand$Quad]))

(defn- empty-ui-packet []
  (FramePacket. 1 [(RenderPass. RenderStage/HUD [(RenderCommand$Quad. 0.0 0.0 8.0 8.0 -1)])]))

(deftest merge-vfx-passes-is-a-noop-without-a-vfx-context
  (testing "HUD/Screen calls never carry a :presentation-context, so the UI packet passes through untouched"
    (let [packet (empty-ui-packet)]
      (is (identical? packet (#'register/merge-vfx-passes packet nil 1 0.0))))))

(deftest merge-vfx-passes-folds-sampled-world-batches-into-the-ui-packet
  (effect-controller/reset-for-test!)
  (try
    (effect-controller/register-effect!
      :register-test-effect
      {:level {:initial-state (fn [] {})
               :build-plan-fn (fn [_cam _hand _tick _query-fn]
                                {:ops [{:kind :line}]})}})
    (let [packet (empty-ui-packet)
          vfx-context {:camera-pos {:x 0.0 :y 0.0 :z 0.0}
                       :hand-center-pos {}
                       :tick 0
                       :query-nearby-blocks-fn (fn [& _] [])}
          merged (#'register/merge-vfx-passes packet vfx-context 1 0.0)]
      (testing "the original UI pass survives untouched"
        (is (= 2 (count (.passes merged))))
        (is (some #(= RenderStage/HUD (.stage ^RenderPass %)) (.passes merged))))
      (testing "a world-after-translucent pass was appended with the sampled batch"
        (let [world-pass (first (filter #(= RenderStage/WORLD_AFTER_TRANSLUCENT (.stage ^RenderPass %))
                                        (.passes merged)))]
          (is (some? world-pass))
          (is (= 1 (count (.commands ^RenderPass world-pass))))
          (is (= "mesh" (.primitive ^cn.li.mcmod.runtime.RenderCommand$Batch
                                    (first (.commands ^RenderPass world-pass))))))))
    (finally
      (effect-controller/reset-for-test!))))

(deftest merge-vfx-passes-degrades-to-the-ui-packet-on-sampling-failure
  (testing "an unmapped VFX stage must not take down the whole frame merge"
    (let [packet (empty-ui-packet)]
      (with-redefs [effect-controller/sample-frame!
                    (fn [_context] {:stages {:not-a-mapped-stage [{:stage :not-a-mapped-stage
                                                                    :primitive :mesh :count 1
                                                                    :payload []}]}})]
        (is (identical? packet (#'register/merge-vfx-passes packet {} 1 0.0)))))))

(def ^:private full-combat-hud-snapshot
  "One value for every reactive-hud/build-snapshot field, shaped the way the
   real builders shape them — exercises every presentation-hud binding adapter
   at once so a destructuring mistake fails here instead of silently
   rendering nothing in-game."
  {:background-mask {:r 0.1 :g 0.2 :b 0.3 :a 0.4}
   :interfered? true
   :activated? true
   :cp-bar {:x 8 :y 8 :width 100 :height 10 :percent 0.5 :full-glow? true}
   :overload-bar {:x 8 :y 22 :width 100 :height 10 :percent 0.3}
   :cp-full-glow? true
   :skill-slots [{:skill-id :a :label "A" :cooldown-remaining 20 :cooldown-total 100}
                 {:skill-id :b :label "B" :cooldown-remaining 0 :cooldown-total 0}]
   :selected-skill 0
   :movement-hints {:x 10 :y 10 :skill-icon "textures/a.png"
                    :items [{:key-label "W" :label "up" :skill-icon "textures/a.png" :active? true}]}
   :activation-indicator {:y 10 :activated true :hint "hint"}
   :preset-indicators [{:type :preset-indicator :current 0 :total 4 :fade 1.0}]
   :numbers-texts [{:kind :text :text "CP 1/2" :x 1 :y 2 :color {:r 255 :g 255 :b 255 :a 255}}]
   :crosshair {:phase 0.2 :intensity 0.5 :x 160 :y 90}
   :vm-waves [{:src "textures/b.png" :tint [70 179 255] :x 1 :y 2 :w 3 :h 4 :alpha 0.5}]
   :charging {:dim-a 10 :mask-alpha 0.4}
   :charging-arcs [{:src "textures/c.png" :tint 0xFFFFFFFF :x 1 :y 2 :w 3 :h 4 :alpha 0.5}]
   :coin-qte {:cx 1 :cy 2
              :bg-disc {:x 1 :y 2 :w 3 :h 4 :color {:r 20 :g 18 :b 10 :a 150}}
              :dots [{:x 1 :y 2 :w 3 :h 4 :src "textures/d.png" :tint [255 215 0] :alpha 1.0}]
              :marker {:x 1 :y 2 :w 3 :h 4 :color {:r 255 :g 235 :b 120 :a 255}}
              :pct-text {:x 1 :y 2 :text "50%" :color {:r 255 :g 215 :b 0 :a 255}}}
   :toasts [{:x 1 :y 2 :w 3 :h 4 :bg {:r 39 :g 39 :b 39 :a 100}
             :borders [{:x 1 :y 2 :w 3 :h 1 :a 100}]
             :text {:x 1 :y 2 :text "hi" :color {:r 255 :g 255 :b 255 :a 255}}}]
   :tutorial-notification {:bg {:src "textures/e.png" :x 0 :y 15 :w 10 :h 10 :alpha 0.5}
                           :icon {:src "textures/f.png" :x 1 :y 2 :w 3 :h 4 :alpha 0.5}
                           :title {:x 1 :y 2 :text "t" :color {:r 255 :g 255 :b 255 :a 255}}
                           :content {:x 1 :y 2 :text "c" :color {:r 255 :g 255 :b 255 :a 255}}}
   :debug-lines [{:x 1 :y 2 :text "dbg" :color 0x00FFFFFF}]
   :screen-flash-alpha 0.5
   :overload-pulse-intensity 0.5
   :screen-w 320
   :screen-h 180})

(deftest every-registered-template-compiles
  (testing "every .ui.edn's [:bind ...]/[:action ...] resolve against its ViewModel's own tables — this
            is what actually catches a namespaced-action name mismatch (:combat/select-skill etc.),
            since (name kw) silently drops the namespace and only the compiler's full-string lookup fails"
    (doseq [template-id ["academy:combat_hud" "academy:terminal" "academy:application"
                         "academy:machine_container" "academy:wireless_matrix" "academy:wireless_node"]]
      (is (some? (#'register/resolve-template template-id)) template-id))))

(deftest combat-hud-template-compiles-and-renders-with-a-full-snapshot
  (testing "every [:bind ...]/[:action ...] in combat_hud.ui.edn resolves against presentation-hud's own tables"
    (is (some? (#'register/resolve-template "academy:combat_hud"))))
  (testing "render-template runs clean over one value for every reactive-hud/build-snapshot field"
    (let [{:keys [model snapshot]} (hud/combat-view-model
                                      #uuid "00000000-0000-0000-0000-000000000000"
                                      (fn [_ _] nil))
          _ (reset! snapshot full-combat-hud-snapshot)
          template (#'register/resolve-template "academy:combat_hud")
          commands (presentation-render/render-template template model {:width 320 :height 180})]
      (is (vector? commands))
      (is (pos? (count commands)) "a fully-populated snapshot must paint something"))))
