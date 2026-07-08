(ns cn.li.ac.ability.adapters.reactive-overlay
  "Reactive overlay for HUD — signal-driven per-frame updates.
   Replace :client-build-overlay-plan with reactive node tree."
  (:require [cn.li.ac.ability.adapters.client-ui-hooks :as client-ui-hooks]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.dsl :as dsl]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.anim :as anim]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.world.entity.player Player]))

(defn- local-player-uuid
  []
  (when-let [^Player player (bridge/get-client-player)]
    (str (.getUUID player))))

(defn- build-overlay-spec [sw sh]
  (dsl/group {:id :root :w sw :h sh}
    (dsl/box {:id :bg-mask :x 0 :y 0 :w sw :h sh :fill 0x00000000})
    (dsl/progress {:id :cp-bar :x 8 :y 8 :w 100 :h 10})
    (dsl/progress {:id :overload-bar :x 8 :y 22 :w 100 :h 10})
    (dsl/image {:id :cp-glow :x 8 :y 8 :w 100 :h 10
                 :src "my_mod:textures/guis/cpbar/cp.png" :visible? false})))

(defn- attach-overlay-bindings! [r]
  (let [clock (rt/clock-ms-sig r)
        cp-target (sig/signal-d 0.0)
        ol-target (sig/signal-d 0.0)
        cp-smooth (anim/smoothed cp-target clock 2.0)
        ol-smooth (anim/smoothed ol-target clock 2.0)
        glow-alpha (anim/breathe clock 1500.0 0.2 0.35)]
    (rt/put-user-signal! r :cp-target cp-target)
    (rt/put-user-signal! r :ol-target ol-target)
    (ui/bind! r :cp-bar :progress cp-smooth)
    (ui/bind! r :overload-bar :progress ol-smooth)
    (ui/bind! r :cp-glow :alpha glow-alpha)
    r))

(defn build-overlay-runtime
  [sw sh]
  (let [r (rt/create-runtime)]
    (rt/build! r (build-overlay-spec sw sh))
    (attach-overlay-bindings! r)))

(defn update-overlay-signals!
  "Per-frame update. Called from overlay-host update-fn."
  [r]
  (when-let [player-uuid (local-player-uuid)]
    (let [snapshot (client-ui-hooks/reactive-overlay-snapshot
                     player-uuid {:now-ms (sig/sget-l (rt/clock-ms-sig r))})]
      (if-let [cp-target (rt/user-signal r :cp-target)]
        (if (:activated snapshot)
          (do
            (sig/sset-d! cp-target (:cp-percent snapshot 0.0))
            (when-let [ol-target (rt/user-signal r :ol-target)]
              (sig/sset-d! ol-target (:overload-percent snapshot 0.0)))
            (when-let [^cn.li.mcmod.ui.node.INode glow (ui/node r :cp-glow)]
              (.setVisible glow (boolean (:cp-full-glow? snapshot)))))
          (do
            (sig/sset-d! cp-target 0.0)
            (when-let [ol-target (rt/user-signal r :ol-target)]
              (sig/sset-d! ol-target 0.0))
            (when-let [^cn.li.mcmod.ui.node.INode glow (ui/node r :cp-glow)]
              (.setVisible glow false))))
        (log/warn "Reactive overlay runtime missing :cp-target signal")))))
