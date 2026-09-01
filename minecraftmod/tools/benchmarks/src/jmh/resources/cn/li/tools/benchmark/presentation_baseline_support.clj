(ns cn.li.tools.benchmark.presentation-baseline-support
  "Pre-refactor (Presentation Runtime v2) baseline fixtures.

   Captures the JMH baseline for the paint/hit-test/dirty-flag path BEFORE
   Phase 3 of the UI refactor replaces paint.clj/runtime.clj's geometry
   code with the Java engine kernels. Keep this namespace and its numbers
   around after the rewrite so the improvement is provable, not asserted."
  (:require [cn.li.presentation.core.host :as host]
            [cn.li.presentation.core.paint :as paint]))

(def ^:private skill-count 20)

(defn- skill-slot [i]
  {:type :box :key (keyword (str "slot-" i))
   :layout {:width 20 :height 34 :direction :column :gap 1}
   :children
   [{:type :button :key (keyword (str "slot-" i "-icon"))
     :layout {:height 20}
     :bind {:text [:item :label]}
     :on {:activate :demo/select-skill}}
    {:type :progress :key (keyword (str "slot-" i "-cooldown"))
     :layout {:height 8}
     :bind {:value [:item :cooldown-ratio]}}
    {:type :text :key (keyword (str "slot-" i "-key"))
     :layout {:height 6}
     :bind {:text [:item :key-label]}}]})

(defn- combat-hud-shaped-artifact
  "~80 nodes: a root row of skill slots (3 nodes each) plus HUD chrome bars,
   the same order of magnitude as the real combat HUD tree."
  []
  {:magic :pui4 :schema 4 :view-id :bench/combat-hud
   :nodes {:type :column :key :root :layout {:width 320 :height 180}
           :children
           (into
            [{:type :progress :key :cp-bar :layout {:width 120 :height 8}
              :bind {:value [:state :cp-ratio]}}
             {:type :progress :key :overload-bar :layout {:width 120 :height 8}
              :bind {:value [:state :overload-ratio]}}
             {:type :row :key :skills :layout {:direction :row :gap 2}
              :children (mapv skill-slot (range skill-count))}]
            [])}})

(defn- skill-item [i tick]
  {:label (str "Skill " i)
   :key-label (str (inc i))
   :cooldown-ratio (double (mod (/ (+ i tick) 37.0) 1.0))})

(defn- frame-state [tick]
  {:cp-ratio (double (mod (/ tick 41.0) 1.0))
   :overload-ratio (double (mod (/ tick 53.0) 1.0))
   :skills (mapv #(skill-item % tick) (range skill-count))})

;; ── extract-stage! steady state: reproduces claim 4 — present! unconditionally
;;    marks :paint dirty every frame, so every real frame does a full repaint. ──

(defn make-extract-fixture []
  (let [runtime (host/create-runtime)
        api (host/api runtime)
        artifact (combat-hud-shaped-artifact)
        mount ((:mount! api) {:host {:stage :hud}
                              :view-id :bench/combat-hud
                              :artifact artifact
                              :state (frame-state 0)
                              :paint-fn paint/paint-view})
        tick (long-array 1)]
    {:api api :mount mount
     :step! (fn []
              (let [t (aget tick 0)]
                (aset tick 0 (unchecked-inc t))
                ((:sync! api) mount 0 (frame-state t))
                ((:extract-stage! api) :hud {:width 320 :height 180})))}))

;; ── pointer-move hit-test: reproduces claims 3/6 — hit-hover walks the full
;;    tree with its own child-rects re-derivation, every pointer move. ──

(defn make-hit-test-fixture []
  (let [runtime (host/create-runtime)
        api (host/api runtime)
        artifact (combat-hud-shaped-artifact)
        mount ((:mount! api) {:host {:stage :hud}
                              :view-id :bench/combat-hud
                              :artifact artifact
                              :state (frame-state 0)
                              :paint-fn paint/paint-view})
        xs (double-array 1)]
    ((:extract-stage! api) :hud {:width 320 :height 180})
    {:api api :mount mount
     :step! (fn []
              (let [x (mod (+ (aget xs 0) 7.0) 300.0)]
                (aset xs 0 x)
                ((:dispatch-input! api) mount
                 {:type :pointer :event-type :move :x x :y 60})))}))
