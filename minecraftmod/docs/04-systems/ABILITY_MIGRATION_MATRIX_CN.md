# 50 技能迁移矩阵（唯一 final graph 路径）

本表由 `ac/combat/manifest.edn` 生成，registration 与 source 分离；每项最终都必须经过同一套 ability-runtime → combat-core → vfx-core → presentation-core。

| # | registration | source | EDN | 迁移组合 | adapter/VFX 隔离 |
|---:|---|---|---|---|---|
| 1 | `:railgun` | `:railgun` | `ac/combat/abilities/railgun.edn` | source → target/beam composite → kernel/trace-beam → damage → beam VFX | owner-scoped state + event-seq VFX |
| 2 | `:arc-gen` | `:arc-gen` | `ac/combat/abilities/arc_gen.edn` | source → target query → area/damage composite → impact VFX | owner-scoped state + event-seq VFX |
| 3 | `:thunder-clap` | `:thunder-clap` | `ac/combat/abilities/thunder_clap.edn` | source → target query → area/damage composite → impact VFX | owner-scoped state + event-seq VFX |
| 4 | `:vec-reflection` | `:vec-reflection` | `ac/combat/abilities/vec_reflection.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 5 | `:vec-deviation` | `:vec-deviation` | `ac/combat/abilities/vec_deviation.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 6 | `:vec-accel` | `:vec-accel` | `ac/combat/abilities/vec_accel.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 7 | `:blood-retrograde` | `:blood-retrograde` | `ac/combat/abilities/blood_retrograde.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 8 | `:directed-blastwave` | `:directed-blastwave` | `ac/combat/abilities/directed_blastwave.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 9 | `:directed-shock` | `:directed-shock` | `ac/combat/abilities/directed_shock.edn` | source → target query → area/damage composite → impact VFX | owner-scoped state + event-seq VFX |
| 10 | `:groundshock` | `:groundshock` | `ac/combat/abilities/groundshock.edn` | source → target query → area/damage composite → impact VFX | owner-scoped state + event-seq VFX |
| 11 | `:scatter-bomb` | `:scatter-bomb` | `ac/combat/abilities/scatter_bomb.edn` | source → target query → area/damage composite → impact VFX | owner-scoped state + event-seq VFX |
| 12 | `:mark-teleport` | `:mark-teleport` | `ac/combat/abilities/mark_teleport.edn` | source → target composite → entity/teleport → marker VFX | owner-scoped state + event-seq VFX |
| 13 | `:penetrate-teleport` | `:penetrate-teleport` | `ac/combat/abilities/penetrate_teleport.edn` | source → target composite → entity/teleport → marker VFX | owner-scoped state + event-seq VFX |
| 14 | `:threatening-teleport` | `:threatening-teleport` | `ac/combat/abilities/threatening_teleport.edn` | source → target composite → entity/teleport → marker VFX | owner-scoped state + event-seq VFX |
| 15 | `:shift-teleport` | `:shift-teleport` | `ac/combat/abilities/shift_teleport.edn` | source → target composite → entity/teleport → marker VFX | owner-scoped state + event-seq VFX |
| 16 | `:location-teleport` | `:location-teleport` | `ac/combat/abilities/location_teleport.edn` | source → target composite → entity/teleport → marker VFX | owner-scoped state + event-seq VFX |
| 17 | `:dim-folding-theorem` | `:dim-folding-theorem` | `ac/combat/abilities/dim_folding_theorem.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 18 | `:space-fluct` | `:space-fluct` | `ac/combat/abilities/space_fluct.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 19 | `:flashing` | `:flashing` | `ac/combat/abilities/flashing.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 20 | `:plasma-cannon` | `:plasma-cannon` | `ac/combat/abilities/plasma_cannon.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 21 | `:storm-wing` | `:storm-wing` | `ac/combat/abilities/storm_wing.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 22 | `:ray-barrage` | `:ray-barrage` | `ac/combat/abilities/ray_barrage.edn` | source → target/beam composite → kernel/trace-beam → damage → beam VFX | owner-scoped state + event-seq VFX |
| 23 | `:current-charging` | `:current-charging` | `ac/combat/abilities/current_charging.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 24 | `:thunder-bolt` | `:thunder-bolt` | `ac/combat/abilities/thunder_bolt.edn` | source → target query → area/damage composite → impact VFX | owner-scoped state + event-seq VFX |
| 25 | `:mine-detect` | `:mine-detect` | `ac/combat/abilities/mine_detect.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 26 | `:mag-movement` | `:mag-movement` | `ac/combat/abilities/mag_movement.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 27 | `:mag-manip` | `:mag-manip` | `ac/combat/abilities/mag_manip.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 28 | `:body-intensify` | `:body-intensify` | `ac/combat/abilities/body_intensify.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 29 | `:jet-engine` | `:jet-engine` | `ac/combat/abilities/jet_engine.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 30 | `:light-shield` | `:light-shield` | `ac/combat/abilities/light_shield.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 31 | `:meltdowner` | `:meltdowner` | `ac/combat/abilities/meltdowner.edn` | source → target/beam composite → kernel/trace-beam → damage → beam VFX | owner-scoped state + event-seq VFX |
| 32 | `:flesh-ripping` | `:flesh-ripping` | `ac/combat/abilities/flesh_ripping.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 33 | `:electron-bomb` | `:electron-bomb` | `ac/combat/abilities/electron_bomb.edn` | source → target/beam composite → kernel/trace-beam → damage → beam VFX | owner-scoped state + event-seq VFX |
| 34 | `:electron-missile` | `:electron-missile` | `ac/combat/abilities/electron_missile.edn` | source → target/beam composite → kernel/trace-beam → damage → beam VFX | owner-scoped state + event-seq VFX |
| 35 | `:rad-intensify` | `:rad-intensify` | `ac/combat/abilities/rad_intensify.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 36 | `:mine-ray-basic` | `:mine-ray` | `ac/combat/abilities/mine_ray.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 37 | `:mine-ray-expert` | `:mine-ray` | `ac/combat/abilities/mine_ray.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 38 | `:mine-ray-luck` | `:mine-ray` | `ac/combat/abilities/mine_ray.edn` | source → flow/phases → policy → query → action → effect/vfx | owner-scoped state + event-seq VFX |
| 39 | `:electromaster/brain-course` | `:brain-course` | `ac/combat/abilities/brain_course.edn` | source → passive-effects → progression | owner-scoped state + event-seq VFX |
| 40 | `:meltdowner/brain-course` | `:brain-course` | `ac/combat/abilities/brain_course.edn` | source → passive-effects → progression | owner-scoped state + event-seq VFX |
| 41 | `:teleporter/brain-course` | `:brain-course` | `ac/combat/abilities/brain_course.edn` | source → passive-effects → progression | owner-scoped state + event-seq VFX |
| 42 | `:vecmanip/brain-course` | `:brain-course` | `ac/combat/abilities/brain_course.edn` | source → passive-effects → progression | owner-scoped state + event-seq VFX |
| 43 | `:electromaster/mind-course` | `:mind-course` | `ac/combat/abilities/mind_course.edn` | source → passive-effects → progression | owner-scoped state + event-seq VFX |
| 44 | `:meltdowner/mind-course` | `:mind-course` | `ac/combat/abilities/mind_course.edn` | source → passive-effects → progression | owner-scoped state + event-seq VFX |
| 45 | `:teleporter/mind-course` | `:mind-course` | `ac/combat/abilities/mind_course.edn` | source → passive-effects → progression | owner-scoped state + event-seq VFX |
| 46 | `:vecmanip/mind-course` | `:mind-course` | `ac/combat/abilities/mind_course.edn` | source → passive-effects → progression | owner-scoped state + event-seq VFX |
| 47 | `:electromaster/brain-course-advanced` | `:brain-course-advanced` | `ac/combat/abilities/brain_course_advanced.edn` | source → passive-effects → progression | owner-scoped state + event-seq VFX |
| 48 | `:meltdowner/brain-course-advanced` | `:brain-course-advanced` | `ac/combat/abilities/brain_course_advanced.edn` | source → passive-effects → progression | owner-scoped state + event-seq VFX |
| 49 | `:teleporter/brain-course-advanced` | `:brain-course-advanced` | `ac/combat/abilities/brain_course_advanced.edn` | source → passive-effects → progression | owner-scoped state + event-seq VFX |
| 50 | `:vecmanip/brain-course-advanced` | `:brain-course-advanced` | `ac/combat/abilities/brain_course_advanced.edn` | source → passive-effects → progression | owner-scoped state + event-seq VFX |

## 共用 composite / kernel 节点

| 可视 composite | 隐藏 kernel | 适用技能族 |
|---|---|---|
| `target/raycast-destination`、`target/hold-destination`、`target/penetration-destination`、`target/directional-destination` | `target/raycast` / `kernel/trace-beam` | 所有瞄准、传送、beam 技能 |
| `combat/area-damage`、`combat/impact-strike`、`combat/charged-area-damage` | `entity/damage`、`entity/status` | 爆炸、冲击、雷击、炮击 |
| `motion/radial-impulse` | `kernel/motion-radial-impulse` | groundshock、爆炸、冲击波 |
| `terrain/break-area`、`terrain/random-break`、`terrain/apply-break-budget` | `kernel/terrain-break-area`、`kernel/terrain-random-break`、`kernel/terrain-apply-break-budget` | groundshock、mine、地形技 |
| `terrain/wave-plan` | `kernel/terrain-wave-plan` | groundshock / terrain shockwave |
| `combat/projectile-reflection-scan` | entity query + owner-scoped `state` | vec-deviation、vec-reflection |

## 每项完成条件

- registration 只含显式 `bindings`，不含 `status/engine/activation` 迁移字段。
- 图中只能出现 `source/primitive/composite`；kernel 只在 lowering 后执行，schema-export 永不导出。
- adapter 必须以 owner + ability-id + activation-seed 作为隔离键；VFX signal 必须带 owner/world/event-seq。
- block/entity 操作按 query → bounded plan → ordered action barrier 提交，禁止在 primitive 内隐藏循环。
