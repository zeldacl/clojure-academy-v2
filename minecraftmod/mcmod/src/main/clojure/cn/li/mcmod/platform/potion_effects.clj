(ns cn.li.mcmod.platform.potion-effects
  "Minecraft-free relay for neutral potion/status-effect operations."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]))

(defn available?
  []
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/get-adapter fw-atom :potion-effects))))

(defn apply-effect!
  [player-uuid effect-type duration amplifier]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :potion-effects :apply-potion-effect!
                            player-uuid effect-type duration amplifier))))

(defn remove-effect!
  [player-uuid effect-type]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :potion-effects :remove-potion-effect!
                            player-uuid effect-type))))

(defn has-effect?
  [player-uuid effect-type]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :potion-effects :has-potion-effect?
                            player-uuid effect-type))))

(defn clear-all!
  [player-uuid]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :potion-effects :clear-all-effects!
                            player-uuid))))
