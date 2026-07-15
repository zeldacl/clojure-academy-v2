(ns cn.li.ac.item.developer-portable-energy
  "Server-side energy access for the portable developer item.

  Upstream PortableDevData: the developer's energy IS the held item stack's
  energy; DevelopData.tick pulls CPS/TPS per tick via tryPullEnergy →
  IFItemManager.pull(stack, amount, true) (bandwidth ignored). The ability
  layer reaches these through platform-hooks registered fns (see
  ability.adapters.server-hooks) — it never requires ac.item/ac.energy."
  (:require [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.item.item-energy-base :as energy-base]
            [cn.li.mcmod.platform.runtime-interop :as interop]))

(defn- held-portable-stack [player-uuid]
  (when (interop/available?)
    (let [stack (interop/get-player-main-hand-item* player-uuid)]
      (when (and stack (= :developer-portable (energy-base/get-energy-item-type stack)))
        stack))))

(defn held-portable-energy
  "Energy on the held portable developer, or nil when not holding one."
  [player-uuid]
  (some-> (held-portable-stack player-uuid)
          energy/get-item-energy
          double))

(defn try-pull-portable-energy!
  "Pull `amount` IF from the held portable developer; true iff the full
   amount was extracted (upstream tryPullEnergy semantics — partial drain on
   the final tick still fails the development)."
  [player-uuid ^double amount]
  (if-let [stack (held-portable-stack player-uuid)]
    (>= (double (energy/pull-energy-from-item stack amount true)) amount)
    false))
