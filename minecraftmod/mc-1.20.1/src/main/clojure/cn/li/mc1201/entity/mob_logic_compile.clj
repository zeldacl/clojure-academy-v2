(ns cn.li.mc1201.entity.mob-logic-compile
  "Compile declarative mob hook maps into loader-neutral MobLogicBundle instances."
  (:import [cn.li.mc1201.entity ScriptedMobEntity]
           [cn.li.mc1201.entity.logic
            IMobTickLogic IMobHurtLogic IMobDeathLogic IMobLootLogic MobLogicBundle]
           [net.minecraft.world.damagesource DamageSource]))

(defn ^MobLogicBundle compile-mob-logic
  "Compile :properties.mob map (or merged cfg) into a MobLogicBundle singleton."
  [mob-props]
  (let [props (or mob-props {})
        tick-fn  (:mob-tick-fn props)
        hurt-fn  (:mob-hurt-fn props)
        death-fn (:mob-death-fn props)
        loot-fn  (:mob-loot-fn props)
        tick (when tick-fn
               (proxy [IMobTickLogic] []
                 (aiStep [mob]
                   (tick-fn mob))))
        hurt (when hurt-fn
               (proxy [IMobHurtLogic] []
                 (onIncomingDamage [mob src amt]
                   (float (hurt-fn mob src amt)))))
        death (when death-fn
                (proxy [IMobDeathLogic] []
                  (onDie [mob src]
                    (death-fn mob src))))
        loot (when loot-fn
               (proxy [IMobLootLogic] []
                 (dropLoot [mob src recent?]
                   (boolean (loot-fn mob src recent?)))))]
    (MobLogicBundle. tick hurt death loot)))
