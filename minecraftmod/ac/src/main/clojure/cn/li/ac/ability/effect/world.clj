(ns cn.li.ac.ability.effect.world
  (:require [cn.li.ac.ability.effect :as effect]
            [cn.li.mcmod.platform.world-effects :as world-effects]))

(effect/defop :spawn-lightning
  [evt {:keys [at]}]
  (when world-effects/*world-effects*
    (let [{:keys [x y z]} (or (when (map? at) at) (get evt at))]
      (world-effects/spawn-lightning! world-effects/*world-effects*
                                      (:world-id evt)
                                      (double x) (double y) (double z))))
  evt)

(effect/defop :create-explosion
  [evt {:keys [at radius damage destroy-blocks?]}]
  ;; Placeholder op: actual explosion dispatch is platform-specific and can be
  ;; bound here later once a stable protocol signature is finalized.
  (let [_coords (or (when (map? at) at) (get evt at))
        _radius radius
        _damage damage
        _destroy? destroy-blocks?]
    nil)
  evt)
