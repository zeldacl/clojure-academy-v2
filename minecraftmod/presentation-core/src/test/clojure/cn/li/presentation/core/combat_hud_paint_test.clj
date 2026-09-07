(ns cn.li.presentation.core.combat-hud-paint-test
  "Combat HUD golden must paint activated composites (mask + CP quads)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cn.li.presentation.core.runtime :as runtime]
            [cn.li.presentation.core.nodetable :as nodetable])
  (:import [cn.li.presentation.core HostGeometry]
           [cn.li.mcmod.runtime.ui UiOp]))

(use-fixtures :each
  (fn [f] (nodetable/clear-tables-for-test!) (f)))

(defn- combat-hud-golden-file
  []
  (first (filter #(.isFile ^java.io.File %)
                 [(io/file "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/combat-hud.uic.edn")
                  (io/file ".." "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/combat-hud.uic.edn")
                  (io/file ".." "minecraftmod" "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/combat-hud.uic.edn")])))

(deftest combat-hud-activated-paints-mask-and-composites
  (let [art-file (combat-hud-golden-file)]
    (is (some? art-file) "combat-hud golden artifact must be on disk")
    (let [artifact (edn/read-string (slurp art-file))
          rt (runtime/create-runtime)
          state {:background-mask {:r 0.12 :g 0.45 :b 0.70 :a 0.28}
                 :composite-list [{:kind :quad :x 10.0 :y 8.0 :w 193.0 :h 29.0
                                   :rgba (unchecked-int 0xCC101820)}
                                  {:kind :quad :x 10.0 :y 8.0 :w 96.0 :h 29.0
                                   :rgba (unchecked-int 0xFF46B3FF)}]
                 :cp-ratio 0.5 :overload-ratio 0.0 :charging? false :skills []}
          mount (runtime/mount!
                  rt {:host {:stage :hud} :artifact artifact :state state})
          _ (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 854 480 1.0))
          dl (-> (runtime/extract-stage! rt :hud {:width 854 :height 480})
                 :mounts first :commands)]
      (is (pos? (.count dl)) "activated combat HUD must emit draw commands")
      (is (some #(or (= UiOp/RECT %) (= UiOp/IMAGE %))
                (map #(aget (.op dl) %) (range (.count dl))))
          "vignette IMAGE and/or CP quads must paint"))))
