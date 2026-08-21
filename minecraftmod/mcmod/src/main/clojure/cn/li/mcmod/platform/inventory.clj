(ns cn.li.mcmod.platform.inventory
  "Player main-hand inventory operations via the shared :runtime-interop
   platform adapter — pure relay layer, no MC dependencies. Returns only
   neutral data (item-id/count), never a raw ItemStack, so combat-core
   never sees a Minecraft object."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]))

(defn available? []
  (boolean (get-in @(fw/fw-atom) [:platform :runtime-interop])))

(defn main-hand-item
  "Neutral `{:item-id :count}` snapshot of player-id's main-hand stack, or nil
   when empty or the port is unavailable."
  [player-id]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter-optional fw-atom :runtime-interop
                                    :main-hand-item-snapshot (str player-id))))

(defn consume-main-hand-item!
  "Shrink player-id's main-hand stack by count. Returns `{:consumed n}` when
   the stack held at least count, or nil when it didn't or the port is
   unavailable."
  [player-id count]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter-optional fw-atom :runtime-interop
                                    :consume-main-hand-item! (str player-id) (long count))))

(defn drop-main-hand-item-at!
  "Drop count one from the player's main hand at a neutral world position."
  [player-id count x y z]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter-optional fw-atom :runtime-interop
                                    :drop-main-hand-item-at!
                                    (str player-id) (long count) (double x) (double y) (double z))))

(defn spawn-main-hand-item-copy-at!
  "Spawn a copy of the main-hand stack at a neutral world position."
  [player-id count x y z]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter-optional fw-atom :runtime-interop
                                    :spawn-main-hand-item-copy-at!
                                    (str player-id) (long count) (double x) (double y) (double z))))
