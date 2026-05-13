(ns cn.li.forge1201.registry.effects
  "Sound, mob-effect, and particle registration for Forge 1.20.1."
  (:require [cn.li.forge1201.registry.state :as registry-state]
            [cn.li.mcmod.registry.metadata :as registry-metadata])
  (:import [cn.li.mc1201.effect ScriptedMobEffect]
           [net.minecraft.core.particles SimpleParticleType]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.sounds SoundEvent]
           [net.minecraft.world.effect MobEffectCategory]
           [net.minecraftforge.registries DeferredRegister RegistryObject]))

(defn- effect-category->forge
  ^MobEffectCategory
  [category-kw]
  (case category-kw
    :beneficial MobEffectCategory/BENEFICIAL
    :neutral MobEffectCategory/NEUTRAL
    MobEffectCategory/HARMFUL))

(defn register-all-sounds!
  [{:keys [sounds-register mod-id]}]
  (doseq [sound-id (registry-metadata/get-all-sound-ids)]
    (let [registry-name (registry-metadata/get-sound-registry-name sound-id)
          registered-obj (.register ^DeferredRegister sounds-register registry-name
                                    (reify java.util.function.Supplier
                                      (get [_]
                                        (SoundEvent/createVariableRangeEvent
                                          (ResourceLocation. mod-id registry-name)))))]
      (swap! registry-state/registered-sounds assoc sound-id registered-obj))))

(defn register-all-effects!
  [{:keys [effects-register]}]
  (doseq [effect-id (registry-metadata/get-all-effect-ids)]
    (let [effect-spec (registry-metadata/get-effect-spec effect-id)
          registry-name (registry-metadata/get-effect-registry-name effect-id)
          category (effect-category->forge (:category effect-spec))
          color (int (or (:color effect-spec) 0xAA0000))
          tick-interval (int (or (:tick-interval effect-spec) 20))
          damage-per-tick (float (or (:damage-per-tick effect-spec) 0.0))
          registered-obj (.register ^DeferredRegister effects-register registry-name
                                    (reify java.util.function.Supplier
                                      (get [_]
                                        (ScriptedMobEffect. category color tick-interval damage-per-tick))))]
      (swap! registry-state/registered-effects assoc effect-id registered-obj))))

(defn register-all-particles!
  [{:keys [particle-types-register]}]
  (doseq [particle-id (registry-metadata/get-all-particle-ids)]
    (let [particle-spec (registry-metadata/get-particle-spec particle-id)
          registry-name (registry-metadata/get-particle-registry-name particle-id)
          always-show? (boolean (:always-show? particle-spec))
          registered-obj (.register ^DeferredRegister particle-types-register registry-name
                                    (reify java.util.function.Supplier
                                      (get [_]
                                        (SimpleParticleType. always-show?))))]
      (swap! registry-state/registered-particles assoc particle-id registered-obj))))
