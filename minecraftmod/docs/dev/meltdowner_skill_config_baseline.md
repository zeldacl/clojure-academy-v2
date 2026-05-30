# Meltdowner Skill Config Baseline Diff

Purpose: track current skill_config/meltdowner defaults against available upstream baseline evidence.

Primary baseline sources used in this pass:
- AcademyCraft upstream Scala/Java source files in LambdaInnovation/AcademyCraft.
- docs/98-archive/M6_FINAL_MIGRATION_REPORT.md as secondary historical cross-check.

## Source Confidence
- Level A: direct upstream code + runtime proof.
- Level B: archived migration report claims + current tests.
- Level C: no reliable upstream baseline found in-repo.

## Upstream Source Availability (Current Workspace)
- Local _upstream/ only contains LambdaLib2, but direct upstream AcademyCraft sources were fetched from https://github.com/LambdaInnovation/AcademyCraft.
- Meltdowner family source-of-truth paths (upstream):
	- src/main/scala/cn/academy/ability/vanilla/meltdowner/skill/Meltdowner.scala
	- src/main/scala/cn/academy/ability/vanilla/meltdowner/skill/ElectronMissile.scala
	- src/main/scala/cn/academy/ability/vanilla/meltdowner/skill/ScatterBomb.scala
	- src/main/scala/cn/academy/ability/vanilla/meltdowner/skill/RayBarrage.scala
	- src/main/scala/cn/academy/ability/vanilla/meltdowner/skill/JetEngine.scala
	- src/main/scala/cn/academy/ability/vanilla/meltdowner/skill/MineRaysBase.scala
	- src/main/scala/cn/academy/ability/vanilla/meltdowner/skill/MineRayBasic.scala
	- src/main/scala/cn/academy/ability/vanilla/meltdowner/skill/MineRayExpert.scala
	- src/main/scala/cn/academy/ability/vanilla/meltdowner/skill/MineRayLuck.scala
	- src/main/scala/cn/academy/ability/vanilla/meltdowner/passiveskill/RadiationIntensify.scala
	- src/main/java/cn/academy/ability/vanilla/meltdowner/skill/LightShield.java
	- src/main/java/cn/academy/ability/vanilla/meltdowner/skill/ElectronBomb.java

## Test Runner Note
- `runAcUnitTests` classpath bootstrap is no longer blocked by missing runner compilation when `ac.test.only` is set.
- Remaining validation risk is now in functional test expectations, not runner namespace resolution.

## Core Skills

| Skill | Field(s) | Current Default(s) | Baseline Evidence | Match | Confidence | Notes |
|---|---|---|---|---|---|---|
| meltdowner | charge.min/max/tolerant | 20 / 40 / 100 | Meltdowner.scala: TICKS_MIN=20, TICKS_MAX=40, TICKS_TOLE=100 | yes | A | Exact numeric match. |
| meltdowner | combat.damage, beam.block-energy | [18,50], [300,700] | Meltdowner.scala: getDamage=timeRate*lerpf(18,50), getEnergy=timeRate*lerpf(300,700) | yes | A | Exact endpoint/formula match. |
| meltdowner | cooldown and exp use | cooldown.base-multiplier=20, cooldown=[15,7], exp-use=0.002 | Meltdowner.scala: cooldown=timeRate*20*lerpf(15,7), exp=timeRate*0.002 | yes | A | Formula shape matches current decomposition. |
| meltdowner | tick cp and down overload | tick cp [10,15], down overload [200,170] | Meltdowner.scala: tickConsumption lerpf(10,15), s_madeAlive consume lerpf(200,170) | yes | A | Exact endpoint match. |
| electron-missile | spawn/fire interval, max balls, hold, seek | 10 / 8 / 5 / [80,200] / [5,13] | ElectronMissile.scala: ticks%10 spawn, ticks%8 fire, MAX_HOLD=5, timeLimit lerpf(80,200), range lerpf(5,13) | yes | A | Exact behavior/endpoint match. |
| electron-missile | damage and attack costs | damage [10,18], cost.attack.cp [60,25], cost.attack.overload [9,4] | ElectronMissile.scala: damage lerpf(10,18), consumption_attacked lerpf(60,25), overload_attacked lerpf(9,4) | yes | A | Exact endpoint match. |
| electron-missile | tick cp, overload floor, cooldown | tick cp [12,5], down overload 200, cooldown [700,400] | ElectronMissile.scala: consumption lerpf(12,5), overload_keep=200, cooldown MathUtils.clampi(700,400,exp.toInt) | partial | A | Endpoints match; upstream cooldown implementation uses clampi with exp.toInt, semantically weaker than lerp-int. |
| electron-bomb | damage and cooldown | damage [6,12], cooldown [20,10] | ElectronBomb.java: getDamage lerpf(6,12), ctx.setCooldown(lerpf(20,10,exp)) | yes | A | Exact endpoint match. |
| scatter-bomb | damage and core timing | damage [4,9], spawn-interval=10, spawn-start=20, anti-afk tick=200 damage=6 | ScatterBomb.scala: getDamage lerpf(5,9), ticks>=20 && ticks%10 spawn, ticks==200 self-damage 6 | partial | A | Timing aligned; low-end damage differs (current 4 vs upstream 5). |
| rad-intensify | damage amplification rate | [1.4,1.8] | RadiationIntensify.scala: getRate MathUtils.lerpf(1.4,1.8,...) | yes | A | Exact endpoint match. |
| light-shield | absorb and resource costs | absorb [15,50], absorb cp [50,30], absorb overload [5,3] | LightShield.java: getAbsorbDamage lerpf(15,50), getAbsorbConsumption lerpf(50,30), getAbsorbOverload lerpf(5,3) | yes | A | Exact endpoint match. |
| light-shield | touch damage, tick cp, activate overload | touch [3,8], tick cp [12,8], down overload [100,70] | LightShield.java: getTouchDamage lerpf(2,6), tick cp lerpf(9,4), start overload lerpf(110,60) | no | A | Current implementation intentionally diverges from upstream values. |
| ray-barrage | damage and cooldown | damage [4,10], cooldown [40,25] | RayBarrage.scala: plain lerpf(25,60), scattered lerpf(10,18), cooldown lerpf(100,40) | no | A | Current values are significantly retuned vs upstream. |
| jet-engine | costs/cooldown/damage | down cp [170,140], down overload [60,50], cooldown [60,30], damage [7,20] | JetEngine.scala: consumption lerpf(170,140), overload lerpf(60,50), cooldown lerpf(60,30), damage lerpf(7,20) | yes | A | Exact endpoint match on key combat/resource fields. |
| mine-ray-basic | range/speed/costs/cooldown | range [8,12], speed [0.15,0.35], cp [12,8], overload [60,40], cooldown 5 | MineRayBasic.scala: range=10, speed(0.2,0.4), cp(12,7), overload(200,150), cooldown(40,20) | no | A | Current design intentionally differs from upstream class constants. |
| mine-ray-expert | range/speed/costs/cooldown | range [16,22], speed [0.4,0.8], cp [18,12], overload [80,55], cooldown 5 | MineRayExpert.scala: range=20, speed(0.5,1.0), cp(25,15), overload(300,200), cooldown(60,30) | no | A | Current design intentionally differs from upstream class constants. |
| mine-ray-luck | range/speed/costs/cooldown | range [16,22], speed [0.5,1.0], cp [22,15], overload [100,70], cooldown 5 | MineRayLuck.scala: range=20, speed(0.5,1.0), cp(50,35), overload(350,300), cooldown(60,30) | no | A | Current design intentionally differs from upstream class constants. |

## What This Unblocks
- Gives a concrete artifact for config parity review instead of only conversational claims.
- Separates confirmed matches from unknown baseline areas.

## Remaining to Reach Full Closure
1. Keep validating approved drift values in gameplay/balance verification loops.
2. Re-run runtime validation after forge datagen blockers are fixed.

## Drift Signoff Artifact
- See docs/dev/meltdowner_config_drift_signoff.md for explicit approved drift decisions.
