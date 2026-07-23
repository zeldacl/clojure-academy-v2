(ns cn.li.ac.ability.skill-config.meltdowner
  "Meltdowner skill action tunables for player-facing skill config.")

(def skill-tunable-definitions
  [{:skill-id :rad-intensify :id :combat.damage-rate :path "combat.damage-rate" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [1.4 1.8] :comment "RadIntensify damage amplification rate endpoints."}
   {:skill-id :rad-intensify :id :effect.mark-duration-ticks :path "effect.mark-duration-ticks" :section-suffix "effect" :type :int :min 1 :default 60 :comment "RadIntensify target mark duration in ticks."}

   {:skill-id :electron-bomb :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [6.0 12.0] :comment "ElectronBomb beam damage endpoints."}
   {:skill-id :electron-bomb :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [250.0 180.0] :comment "ElectronBomb down-stage CP cost. Not present in original (which consumes no resource at all) — kept as a deliberate departure."}
   {:skill-id :electron-bomb :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [120.0 90.0] :comment "ElectronBomb down-stage overload cost. Not present in original (which consumes no resource at all) — kept as a deliberate departure."}
   {:skill-id :electron-bomb :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [20.0 10.0] :comment "ElectronBomb cooldown ticks."}
   {:skill-id :electron-bomb :id :progression.exp-hit :path "progression.exp-hit" :section-suffix "progression" :type :double :min 0.0 :default 0.005 :comment "ElectronBomb exp gained per cast (unconditional — matches original's ctx.addSkillExp(.005f) firing regardless of whether the delayed ray hits)."}
   {:skill-id :electron-bomb :id :charge.settle-ticks :path "charge.settle-ticks" :section-suffix "charge" :type :int :min 1 :default 20 :comment "ElectronBomb ball settle time (ticks) before the ray fires, below the improved-exp threshold."}
   {:skill-id :electron-bomb :id :charge.settle-ticks-improved :path "charge.settle-ticks-improved" :section-suffix "charge" :type :int :min 1 :default 5 :comment "ElectronBomb ball settle time (ticks) once skill exp exceeds charge.improved-exp-threshold."}
   {:skill-id :electron-bomb :id :charge.improved-exp-threshold :path "charge.improved-exp-threshold" :section-suffix "charge" :type :double :min 0.0 :max 1.0 :default 0.8 :comment "ElectronBomb skill exp above which the ball settles faster (charge.settle-ticks-improved)."}

   {:skill-id :electron-missile :id :targeting.seek-range :path "targeting.seek-range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [5.0 13.0] :comment "ElectronMissile target seek range."}
   {:skill-id :electron-missile :id :charge.max-hold-ticks :path "charge.max-hold-ticks" :section-suffix "charge" :type :double-list :min 1.0 :list-count 2 :default [80.0 200.0] :comment "ElectronMissile max hold ticks."}
  {:skill-id :electron-missile :id :projectile.max-hold-balls :path "projectile.max-hold-balls" :section-suffix "projectile" :type :int :min 1 :default 5 :comment "ElectronMissile max active stored balls."}
  {:skill-id :electron-missile :id :timing.spawn-interval-ticks :path "timing.spawn-interval-ticks" :section-suffix "timing" :type :int :min 1 :default 10 :comment "ElectronMissile ticks between ball spawns."}
   {:skill-id :electron-missile :id :timing.fire-interval-ticks :path "timing.fire-interval-ticks" :section-suffix "timing" :type :int :min 1 :default 8 :comment "ElectronMissile ticks between ball launches."}
   {:skill-id :electron-missile :id :projectile.travel-speed :path "projectile.travel-speed" :section-suffix "projectile" :type :double :min 0.001 :default 1.6 :comment "ElectronMissile estimated projectile speed in blocks per tick."}
   {:skill-id :electron-missile :id :projectile.travel-ticks-min :path "projectile.travel-ticks-min" :section-suffix "projectile" :type :int :min 1 :default 2 :comment "ElectronMissile minimum delayed hit ticks."}
   {:skill-id :electron-missile :id :projectile.travel-ticks-max :path "projectile.travel-ticks-max" :section-suffix "projectile" :type :int :min 1 :default 20 :comment "ElectronMissile maximum delayed hit ticks."}
   {:skill-id :electron-missile :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [10.0 18.0] :comment "ElectronMissile damage per ball."}
   {:skill-id :electron-missile :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double :min 0.0 :default 200.0 :comment "ElectronMissile activation overload floor/cost."}
  {:skill-id :electron-missile :id :cost.attack.cp :path "cost.attack.cp" :section-suffix "cost.attack" :type :double-list :min 0.0 :list-count 2 :default [60.0 25.0] :comment "ElectronMissile extra CP cost per fired ball."}
  {:skill-id :electron-missile :id :cost.attack.overload :path "cost.attack.overload" :section-suffix "cost.attack" :type :double-list :min 0.0 :list-count 2 :default [9.0 4.0] :comment "ElectronMissile extra overload cost per fired ball."}
   {:skill-id :electron-missile :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [12.0 5.0] :comment "ElectronMissile channel CP cost."}
   {:skill-id :electron-missile :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [700.0 400.0] :comment "ElectronMissile manual cooldown ticks."}
  {:skill-id :electron-missile :id :progression.exp-hit :path "progression.exp-hit" :section-suffix "progression" :type :double :min 0.0 :default 0.001 :comment "ElectronMissile exp gained per hit."}

   {:skill-id :jet-engine :id :charge.min-ticks :path "charge.min-ticks" :section-suffix "charge" :type :int :min 0 :default 10 :comment "JetEngine minimum charge ticks."}
   {:skill-id :jet-engine :id :charge.max-ticks :path "charge.max-ticks" :section-suffix "charge" :type :int :min 1 :default 80 :comment "JetEngine max charge ticks."}
   {:skill-id :jet-engine :id :movement.speed :path "movement.speed" :section-suffix "movement" :type :double-list :min 0.0 :list-count 2 :default [1.2 2.8] :comment "JetEngine base launch speed."}
   {:skill-id :jet-engine :id :movement.charge-speed-bonus :path "movement.charge-speed-bonus" :section-suffix "movement" :type :double :min 0.0 :default 1.5 :comment "JetEngine full-charge extra speed multiplier."}
  {:skill-id :jet-engine :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [7.0 20.0] :comment "JetEngine trigger-hit damage endpoints."}
   {:skill-id :jet-engine :id :combat.hit-radius :path "combat.hit-radius" :section-suffix "combat" :type :double :min 0.0 :default 1.5 :comment "JetEngine collision search radius."}
   {:skill-id :jet-engine :id :combat.hit-steps :path "combat.hit-steps" :section-suffix "combat" :type :int :min 1 :default 4 :comment "JetEngine collision samples along launch direction."}
  {:skill-id :jet-engine :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [170.0 140.0] :comment "JetEngine release-stage CP cost."}
  {:skill-id :jet-engine :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [60.0 50.0] :comment "JetEngine release-stage overload cost."}
  {:skill-id :jet-engine :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [60.0 30.0] :comment "JetEngine cooldown ticks after successful release."}
  {:skill-id :jet-engine :id :progression.exp-use :path "progression.exp-use" :section-suffix "progression" :type :double :min 0.0 :default 0.004 :comment "JetEngine exp gained on successful release."}

   {:skill-id :light-shield :id :combat.damage-reduction :path "combat.damage-reduction" :section-suffix "combat" :type :double-list :min 0.0 :max 1.0 :list-count 2 :default [0.5 0.8] :comment "LightShield incoming damage reduction."}
   {:skill-id :light-shield :id :combat.touch-damage :path "combat.touch-damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [3.0 8.0] :comment "LightShield contact damage."}
   {:skill-id :light-shield :id :combat.touch-radius :path "combat.touch-radius" :section-suffix "combat" :type :double :min 0.0 :default 3.0 :comment "LightShield touch damage radius."}
  {:skill-id :light-shield :id :combat.touch-interval-ticks :path "combat.touch-interval-ticks" :section-suffix "combat" :type :int :min 1 :default 4 :comment "LightShield touch damage interval ticks."}
  {:skill-id :light-shield :id :combat.absorb-damage :path "combat.absorb-damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [15.0 50.0] :comment "LightShield max absorbed damage per hit."}
  {:skill-id :light-shield :id :combat.absorb-interval-ticks :path "combat.absorb-interval-ticks" :section-suffix "combat" :type :int :min 1 :default 18 :comment "LightShield absorb window interval ticks."}
   {:skill-id :light-shield :id :combat.front-cone-dot :path "combat.front-cone-dot" :section-suffix "combat" :type :double :min -1.0 :max 1.0 :default 0.5 :comment "LightShield minimum dot product for front cone."}
  {:skill-id :light-shield :id :timing.max-active-ticks :path "timing.max-active-ticks" :section-suffix "timing" :type :double-list :min 1.0 :list-count 2 :default [120.0 180.0] :comment "LightShield max active duration ticks."}
   {:skill-id :light-shield :id :effect.deactivate-slowness-duration-ticks :path "effect.deactivate-slowness-duration-ticks" :section-suffix "effect" :type :int :min 0 :default 60 :comment "LightShield deactivate slowness duration."}
   {:skill-id :light-shield :id :effect.abort-slowness-duration-ticks :path "effect.abort-slowness-duration-ticks" :section-suffix "effect" :type :int :min 0 :default 40 :comment "LightShield abort slowness duration."}
   {:skill-id :light-shield :id :effect.slowness-amplifier :path "effect.slowness-amplifier" :section-suffix "effect" :type :int :min 0 :default 1 :comment "LightShield slowness amplifier."}
   {:skill-id :light-shield :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [200.0 160.0] :comment "LightShield activation CP cost."}
   {:skill-id :light-shield :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [100.0 70.0] :comment "LightShield activation overload cost."}
   {:skill-id :light-shield :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [12.0 8.0] :comment "LightShield tick CP cost."}
  {:skill-id :light-shield :id :cost.absorb.cp :path "cost.absorb.cp" :section-suffix "cost.absorb" :type :double-list :min 0.0 :list-count 2 :default [50.0 30.0] :comment "LightShield absorb CP cost per successful absorb/touch damage."}
  {:skill-id :light-shield :id :cost.absorb.overload :path "cost.absorb.overload" :section-suffix "cost.absorb" :type :double-list :min 0.0 :list-count 2 :default [5.0 3.0] :comment "LightShield absorb overload cost per successful absorb/touch damage."}
   {:skill-id :light-shield :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [100.0 60.0] :comment "LightShield cooldown ticks."}
   {:skill-id :light-shield :id :progression.exp-absorbed-scale :path "progression.exp-absorbed-scale" :section-suffix "progression" :type :double :min 0.0 :default 0.0004 :comment "LightShield exp gained per absorbed damage."}

   {:skill-id :meltdowner :id :charge.min-ticks :path "charge.min-ticks" :section-suffix "charge" :type :int :min 1 :default 20 :comment "Meltdowner minimum charge ticks."}
   {:skill-id :meltdowner :id :charge.max-ticks :path "charge.max-ticks" :section-suffix "charge" :type :int :min 1 :default 40 :comment "Meltdowner optimal max charge ticks."}
   {:skill-id :meltdowner :id :charge.max-tolerant-ticks :path "charge.max-tolerant-ticks" :section-suffix "charge" :type :int :min 1 :default 100 :comment "Meltdowner tolerant max hold ticks."}
   {:skill-id :meltdowner :id :charge.time-rate :path "charge.time-rate" :section-suffix "charge" :type :double-list :min 0.0 :list-count 2 :default [0.8 1.2] :comment "Meltdowner time-rate endpoints between min and max charge."}
   {:skill-id :meltdowner :id :beam.radius :path "beam.radius" :section-suffix "beam" :type :double-list :min 0.0 :list-count 2 :default [2.0 3.0] :comment "Meltdowner beam radius endpoints."}
   {:skill-id :meltdowner :id :beam.query-radius :path "beam.query-radius" :section-suffix "beam" :type :double :min 0.0 :default 30.0 :comment "Meltdowner beam query radius."}
   {:skill-id :meltdowner :id :beam.step :path "beam.step" :section-suffix "beam" :type :double :min 0.001 :default 0.9 :comment "Meltdowner beam trace step."}
   {:skill-id :meltdowner :id :beam.max-distance :path "beam.max-distance" :section-suffix "beam" :type :double :min 0.0 :default 50.0 :comment "Meltdowner beam max distance."}
   {:skill-id :meltdowner :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [18.0 50.0] :comment "Meltdowner beam damage endpoints."}
   {:skill-id :meltdowner :id :beam.block-energy :path "beam.block-energy" :section-suffix "beam" :type :double-list :min 0.0 :list-count 2 :default [300.0 700.0] :comment "Meltdowner block break energy endpoints."}
   {:skill-id :meltdowner :id :reflection.cp-per-damage :path "reflection.cp-per-damage" :section-suffix "reflection" :type :double-list :min 0.0 :list-count 2 :default [20.0 15.0] :comment "VecReflection CP cost per incoming Meltdowner damage."}
   {:skill-id :meltdowner :id :reflection.shot-distance :path "reflection.shot-distance" :section-suffix "reflection" :type :double :min 0.0 :default 10.0 :comment "Meltdowner reflected shot distance."}
   {:skill-id :meltdowner :id :reflection.damage-multiplier :path "reflection.damage-multiplier" :section-suffix "reflection" :type :double :min 0.0 :default 0.5 :comment "Meltdowner reflected shot damage multiplier."}
   {:skill-id :meltdowner :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [200.0 170.0] :comment "Meltdowner down-stage overload cost."}
   {:skill-id :meltdowner :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [10.0 15.0] :comment "Meltdowner tick CP cost."}
   {:skill-id :meltdowner :id :cooldown.base-multiplier :path "cooldown.base-multiplier" :section-suffix "cooldown" :type :double :min 0.0 :default 20.0 :comment "Meltdowner cooldown base multiplier."}
   {:skill-id :meltdowner :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [15.0 7.0] :comment "Meltdowner cooldown endpoints multiplied by time-rate/base."}
   {:skill-id :meltdowner :id :progression.exp-use :path "progression.exp-use" :section-suffix "progression" :type :double :min 0.0 :default 0.002 :comment "Meltdowner exp gained per performed beam scaled by time-rate."}

   {:skill-id :mine-ray-basic :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [8.0 12.0] :comment "MineRayBasic range."}
   {:skill-id :mine-ray-basic :id :mining.break-speed :path "mining.break-speed" :section-suffix "mining" :type :double-list :min 0.0 :list-count 2 :default [0.15 0.35] :comment "MineRayBasic break speed."}
   {:skill-id :mine-ray-basic :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [60.0 40.0] :comment "MineRayBasic down-stage overload cost."}
   {:skill-id :mine-ray-basic :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [12.0 8.0] :comment "MineRayBasic tick CP cost."}
   {:skill-id :mine-ray-basic :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :int :min 0 :default 5 :comment "MineRayBasic cooldown ticks."}
   {:skill-id :mine-ray-basic :id :progression.exp-block :path "progression.exp-block" :section-suffix "progression" :type :double :min 0.0 :default 0.001 :comment "MineRayBasic exp per block broken."}

   {:skill-id :mine-ray-expert :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [16.0 22.0] :comment "MineRayExpert range."}
   {:skill-id :mine-ray-expert :id :mining.break-speed :path "mining.break-speed" :section-suffix "mining" :type :double-list :min 0.0 :list-count 2 :default [0.4 0.8] :comment "MineRayExpert break speed."}
   {:skill-id :mine-ray-expert :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [80.0 55.0] :comment "MineRayExpert down-stage overload cost."}
   {:skill-id :mine-ray-expert :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [18.0 12.0] :comment "MineRayExpert tick CP cost."}
   {:skill-id :mine-ray-expert :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :int :min 0 :default 5 :comment "MineRayExpert cooldown ticks."}
   {:skill-id :mine-ray-expert :id :progression.exp-block :path "progression.exp-block" :section-suffix "progression" :type :double :min 0.0 :default 0.001 :comment "MineRayExpert exp per block broken."}

   {:skill-id :mine-ray-luck :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [16.0 22.0] :comment "MineRayLuck range."}
   {:skill-id :mine-ray-luck :id :mining.break-speed :path "mining.break-speed" :section-suffix "mining" :type :double-list :min 0.0 :list-count 2 :default [0.5 1.0] :comment "MineRayLuck break speed."}
   {:skill-id :mine-ray-luck :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [100.0 70.0] :comment "MineRayLuck down-stage overload cost."}
   {:skill-id :mine-ray-luck :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [22.0 15.0] :comment "MineRayLuck tick CP cost."}
   {:skill-id :mine-ray-luck :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :int :min 0 :default 5 :comment "MineRayLuck cooldown ticks."}
   {:skill-id :mine-ray-luck :id :progression.exp-block :path "progression.exp-block" :section-suffix "progression" :type :double :min 0.0 :default 0.002 :comment "MineRayLuck exp per block broken."}

  {:skill-id :ray-barrage :id :combat.damage.plain :path "combat.damage.plain" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [25.0 60.0] :comment "RayBarrage plain/direct branch damage endpoints."}
  {:skill-id :ray-barrage :id :combat.damage.scattered :path "combat.damage.scattered" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [10.0 18.0] :comment "RayBarrage scattered branch damage endpoints."}
  {:skill-id :ray-barrage :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double :min 0.0 :default 22.0 :comment "RayBarrage forward detect range."}
  {:skill-id :ray-barrage :id :scatter.target-radius :path "scatter.target-radius" :section-suffix "scatter" :type :double :min 0.0 :default 8.0 :comment "RayBarrage scattered target search radius around silbarn."}
  {:skill-id :ray-barrage :id :scatter.count :path "scatter.count" :section-suffix "scatter" :type :int :min 1 :default 8 :comment "RayBarrage scattered beam count when silbarn branch triggers."}
   {:skill-id :ray-barrage :id :beam.radius :path "beam.radius" :section-suffix "beam" :type :double :min 0.0 :default 0.3 :comment "RayBarrage beam radius."}
   {:skill-id :ray-barrage :id :beam.query-radius :path "beam.query-radius" :section-suffix "beam" :type :double :min 0.0 :default 20.0 :comment "RayBarrage beam query radius."}
   {:skill-id :ray-barrage :id :beam.step :path "beam.step" :section-suffix "beam" :type :double :min 0.001 :default 0.8 :comment "RayBarrage beam step."}
   {:skill-id :ray-barrage :id :beam.max-distance :path "beam.max-distance" :section-suffix "beam" :type :double :min 0.0 :default 22.0 :comment "RayBarrage beam max distance."}
   {:skill-id :ray-barrage :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [300.0 220.0] :comment "RayBarrage down-stage CP cost."}
   {:skill-id :ray-barrage :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [130.0 100.0] :comment "RayBarrage down-stage overload cost."}
  {:skill-id :ray-barrage :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [100.0 40.0] :comment "RayBarrage cooldown ticks."}
   {:skill-id :ray-barrage :id :progression.exp-hit :path "progression.exp-hit" :section-suffix "progression" :type :double :min 0.0 :default 0.003 :comment "RayBarrage exp gained when any beam hits."}

   {:skill-id :scatter-bomb :id :projectile.max-balls :path "projectile.max-balls" :section-suffix "projectile" :type :int :min 1 :default 6 :comment "ScatterBomb maximum accumulated balls."}
  {:skill-id :scatter-bomb :id :projectile.max-hold-ticks :path "projectile.max-hold-ticks" :section-suffix "projectile" :type :int :min 1 :default 80 :comment "ScatterBomb hold window tick cap for ball accumulation."}
   {:skill-id :scatter-bomb :id :projectile.spawn-interval-ticks :path "projectile.spawn-interval-ticks" :section-suffix "projectile" :type :int :min 1 :default 10 :comment "ScatterBomb ball spawn interval."}
   {:skill-id :scatter-bomb :id :projectile.spawn-start-tick :path "projectile.spawn-start-tick" :section-suffix "projectile" :type :int :min 0 :default 20 :comment "ScatterBomb first ball spawn tick."}
   {:skill-id :scatter-bomb :id :projectile.cone-spread :path "projectile.cone-spread" :section-suffix "projectile" :type :double-list :min 0.0 :list-count 2 :default [0.3 0.8] :comment "ScatterBomb random cone spread [min,max]."}
   {:skill-id :scatter-bomb :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [4.0 9.0] :comment "ScatterBomb beam damage."}
   {:skill-id :scatter-bomb :id :beam.radius :path "beam.radius" :section-suffix "beam" :type :double :min 0.0 :default 0.3 :comment "ScatterBomb delayed beam radius."}
   {:skill-id :scatter-bomb :id :beam.query-radius :path "beam.query-radius" :section-suffix "beam" :type :double :min 0.0 :default 20.0 :comment "ScatterBomb delayed beam query radius."}
   {:skill-id :scatter-bomb :id :beam.step :path "beam.step" :section-suffix "beam" :type :double :min 0.001 :default 0.8 :comment "ScatterBomb delayed beam trace step."}
   {:skill-id :scatter-bomb :id :beam.max-distance :path "beam.max-distance" :section-suffix "beam" :type :double :min 0.0 :default 25.0 :comment "ScatterBomb delayed beam max distance."}
   {:skill-id :scatter-bomb :id :effect.anti-afk-tick :path "effect.anti-afk-tick" :section-suffix "effect" :type :int :min 1 :default 200 :comment "ScatterBomb anti-AFK self-damage tick."}
   {:skill-id :scatter-bomb :id :effect.anti-afk-damage :path "effect.anti-afk-damage" :section-suffix "effect" :type :double :min 0.0 :default 6.0 :comment "ScatterBomb anti-AFK self-damage amount."}
   {:skill-id :scatter-bomb :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [150.0 120.0] :comment "ScatterBomb down-stage overload cost."}
   {:skill-id :scatter-bomb :id :cost.overload-floor-scale :path "cost.overload-floor-scale" :section-suffix "cost" :type :double :min 0.0 :default 0.8 :comment "ScatterBomb overload floor scale from activation overload."}
   {:skill-id :scatter-bomb :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [8.0 5.0] :comment "ScatterBomb tick CP cost."}
   {:skill-id :scatter-bomb :id :cooldown.ticks-per-ball :path "cooldown.ticks-per-ball" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [30.0 15.0] :comment "ScatterBomb cooldown ticks per ball."}
   {:skill-id :scatter-bomb :id :progression.exp-per-ball :path "progression.exp-per-ball" :section-suffix "progression" :type :double :min 0.0 :default 0.002 :comment "ScatterBomb exp gained per fired ball."}])

(def internal-tunable-definitions
  [{:skill-id :meltdowner :id :beam.visual-distance :path "beam.visual-distance" :section-suffix "beam" :type :double :min 0.0 :default 45.0 :comment "Internal FX beam visual distance."}
   {:skill-id :ray-barrage :id :beam.visual-distance :path "beam.visual-distance" :section-suffix "beam" :type :double :min 0.0 :default 20.0 :comment "Internal FX beam visual distance."}
   {:skill-id :scatter-bomb :id :beam.visual-distance :path "beam.visual-distance" :section-suffix "beam" :type :double :min 0.0 :default 23.0 :comment "Internal FX beam visual distance."}])
