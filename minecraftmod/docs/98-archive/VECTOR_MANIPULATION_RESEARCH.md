# Vector Manipulation Category - Research Summary

## Overview

Vector Manipulation (向量操作) is one of the four vanilla ability categories in AcademyCraft. It focuses on manipulating motion vectors, forces, and kinetic energy. The category has 9 skills across 5 levels.

**Category Color**: Black (0, 0, 0)
**Skill Count**: 9 skills
**Complexity**: High (includes toggle skills, passive effects, complex physics)

---

## Skill List

### Level 1 Skills

#### 1. DirectedShock (定向冲击)
**Type**: Instant melee attack
**Mechanics**:
- Raycast to find entity within 3 blocks
- Punch animation with hand render override
- Damage: 7-15 (scales with experience)
- Knockback at 25%+ experience (0.7 velocity)
- Minimum charge: 6 ticks, optimal: <50 ticks
- Experience gain: 0.0035 on hit, 0.001 on miss

**Resources**:
- CP: 50-100 (scales with experience)
- Overload: 18-12 (scales with experience)
- Cooldown: 60-20 ticks (scales with experience)

**Implementation Notes**:
- Simple instant-cast skill, good starting point
- Uses raycast protocol for entity detection
- Uses entity damage protocol for attack
- Client-side punch animation

---

#### 2. Groundshock (地震冲击)
**Type**: Charged ground slam AOE
**Mechanics**:
- Requires player on ground
- Charge for 5+ ticks (affects pitch animation)
- Propagates shockwave in look direction
- Breaks/modifies blocks in path (stone→cobblestone, grass→dirt)
- Damages entities in AOE (4-6 damage)
- Launches entities upward (0.6-0.9 y velocity)
- At 100% experience: breaks blocks in 5-block radius around player
- Energy system: starts with 60-120 energy, consumes per block/entity

**Resources**:
- CP: 80-150 (scales with experience)
- Overload: 15-10 (scales with experience)
- Cooldown: 80-40 ticks (scales with experience)
- Energy: 60-120 (internal energy for propagation)

**Implementation Notes**:
- Requires IBlockManipulation protocol for block breaking
- Complex propagation algorithm (Plotter class)
- Block hardness affects energy consumption
- Drop rate: 30-100% based on experience

---

### Level 2 Skills

#### 3. VecAccel (矢量加速)
**Type**: Instant dash/acceleration
**Mechanics**:
- Charge up to 20 ticks for increased speed
- Accelerates player in look direction (pitch - 10°)
- Max velocity: 2.5 blocks/tick
- Speed scales with charge time (sin curve: 0.4-1.0)
- Dismounts riding entities
- Resets fall damage
- Requires ground at <50% experience, ignores at 50%+

**Resources**:
- CP: 120-80 (scales with experience)
- Overload: 30-15 (scales with experience)
- Cooldown: 80-50 ticks (scales with experience)

**Implementation Notes**:
- Requires IPlayerMotion protocol for velocity manipulation
- Requires ITeleportation protocol for fall damage reset
- Ground check using raycast (2 blocks down)
- Charge indicator needed (client-side)

**Prerequisites**: DirectedShock

---

#### 4. VecDeviation (矢量偏移)
**Type**: Toggle passive deflection
**Mechanics**:
- **Toggle skill** - stays active until deactivated or resources depleted
- Deflects incoming projectiles (arrows, fireballs, etc.)
- Stops projectile motion (motionX/Y/Z = 0)
- Reduces incoming damage by 40-90% (scales with experience)
- Tracks visited entities to avoid duplicate processing
- Marks deflected entities to prevent re-deflection
- Experience gain: 0.0006 per damage point deflected

**Resources**:
- CP drain: 13-5 per tick (passive)
- CP per projectile: 15-12 (scales with experience)
- CP per damage: 15-12 (scales with experience)
- No overload cost

**Implementation Notes**:
- Requires toggle skill support in context system
- Requires damage event interception (LivingHurtEvent)
- Requires entity tracking system (visited set)
- Client-side wave effect UI overlay
- Uses EntityAffection system for marking

**Prerequisites**: VecAccel

---

### Level 3 Skills

#### 5. DirectedBlastwave (定向冲击波)
**Type**: Charged ranged shockwave
**Mechanics**: (Need to read source file)
- Likely a ranged version of DirectedShock
- Probably uses charge mechanics
- AOE damage in cone/line

**Prerequisites**: Groundshock

---

#### 6. StormWing (风翼)
**Type**: Flight/levitation
**Mechanics**: (Need to read source file)
- Likely provides flight or levitation
- Probably consumes CP over time
- May have speed/altitude limits

**Prerequisites**: VecAccel

---

### Level 4 Skills

#### 7. BloodRetrograde (血液逆流)
**Type**: Debuff/damage over time
**Mechanics**: (Need to read source file)
- Likely applies debuff to entities
- Probably causes internal damage
- May have duration/tick damage

**Prerequisites**: DirectedBlastwave

---

#### 8. VecReflection (矢量反射)
**Type**: Toggle advanced reflection
**Mechanics**:
- **Toggle skill** - stays active until deactivated
- Reflects projectiles back at attacker (redirects to player's look target)
- Reflects damage back to attacker (60-120% of incoming damage)
- Handles fireballs (creates new fireball entity)
- Handles arrows and other projectiles
- Prevents reentrant reflection (no infinite loops)
- Tracks visited entities
- Experience gain: 0.0008 per difficulty, 0.0004 per damage point

**Resources**:
- CP drain: 15-11 per tick (passive)
- CP per entity: 300-160 × difficulty (scales with experience)
- CP per damage: (20-15) × damage (scales with experience)
- Overload: keeps 350-250 (prevents overload decay)

**Implementation Notes**:
- Requires toggle skill support
- Requires damage event interception (LivingAttackEvent, LivingHurtEvent)
- Requires projectile redirection
- Requires ReflectEvent handling (custom event)
- Client-side wave effect UI overlay
- Complex fireball recreation logic

**Prerequisites**: VecDeviation

---

### Level 5 Skills

#### 9. PlasmaCannon (等离子炮)
**Type**: Charged projectile attack
**Mechanics**: (Need to read source file)
- Likely fires a powerful plasma projectile
- Probably uses charge mechanics
- High damage, long cooldown

**Prerequisites**: StormWing

---

## New Systems Required

### 1. IPlayerMotion Protocol (mcmod/platform/player_motion.clj)
```clojure
(defprotocol IPlayerMotion
  "Protocol for manipulating player motion and physics."
  (set-velocity! [this player-id x y z]
    "Set player velocity vector.")
  (add-velocity! [this player-id x y z]
    "Add to player velocity vector.")
  (get-velocity [this player-id]
    "Get player velocity as {:x :y :z}.")
  (set-on-ground! [this player-id on-ground?]
    "Set player on-ground state.")
  (is-on-ground? [this player-id]
    "Check if player is on ground.")
  (dismount-riding! [this player-id]
    "Dismount player from riding entity."))
```

### 2. IBlockManipulation Protocol (mcmod/platform/block_manipulation.clj)
```clojure
(defprotocol IBlockManipulation
  "Protocol for breaking and modifying blocks."
  (break-block! [this world-id x y z drop?]
    "Break block at position, optionally drop items.")
  (set-block! [this world-id x y z block-id]
    "Set block at position to new block type.")
  (get-block [this world-id x y z]
    "Get block at position.")
  (get-block-hardness [this world-id x y z]
    "Get block hardness value.")
  (can-break-block? [this player-id world-id x y z]
    "Check if player can break block (permissions, protection, etc.).")
  (find-blocks-in-line [this world-id x1 y1 z1 dx dy dz max-distance]
    "Find blocks along a line (for Groundshock propagation)."))
```

### 3. Toggle Skill Support
- Modify context system to support persistent contexts
- Add activation/deactivation handlers
- Add tick-based resource consumption
- Add UI overlay for active toggle skills

### 4. EntityAffection System
- Track which entities have been affected by skills
- Mark entities to prevent duplicate processing
- Difficulty rating system for different entity types

### 5. Damage Event Interception
- Hook into Forge damage events (LivingHurtEvent, LivingAttackEvent)
- Allow skills to modify/cancel damage
- Support reflection mechanics

---

## Implementation Priority

### Phase 1: Simple Skills (Week 1)
1. **DirectedShock** - Validate basic melee mechanics
2. **VecAccel** - Validate player motion manipulation
3. **Groundshock** - Validate block manipulation

### Phase 2: Toggle Skills (Week 2)
4. **VecDeviation** - Implement toggle skill framework
5. **VecReflection** - Extend toggle framework with reflection

### Phase 3: Advanced Skills (Week 3)
6. **DirectedBlastwave** - Research and implement
7. **StormWing** - Research and implement
8. **BloodRetrograde** - Research and implement
9. **PlasmaCannon** - Research and implement

---

## Architecture Considerations

### Protocol Layer (mcmod/)
- IPlayerMotion - Player velocity/physics manipulation
- IBlockManipulation - Block breaking/modification
- Extend IEntityDamage - Add damage event interception

### Utility Layer (ac/ability/util/)
- motion.clj - Player motion calculations
- block_propagation.clj - Groundshock line propagation
- entity_affection.clj - Entity marking/tracking system
- toggle_skill.clj - Toggle skill state management

### Forge Layer (forge-1.20.1/)
- player_motion.clj - Implement IPlayerMotion
- block_manipulation.clj - Implement IBlockManipulation
- damage_events.clj - Hook Forge damage events

---

## Skill Dependencies

```
DirectedShock (L1)
├── Groundshock (L1)
│   └── DirectedBlastwave (L3)
│       └── BloodRetrograde (L4)
└── VecAccel (L2)
    ├── VecDeviation (L2)
    │   └── VecReflection (L4)
    └── StormWing (L3)
        └── PlasmaCannon (L5)
```

---

## Comparison with Existing Categories

| Category | Skills | Complexity | New Protocols | Toggle Skills |
|----------|--------|------------|---------------|---------------|
| Electromaster | 7 | Medium | 4 | 0 |
| Meltdowner | 1 | Medium | 0 | 0 |
| Teleporter | 2 | Medium | 2 | 0 |
| **Vector Manipulation** | **9** | **High** | **2** | **2** |

Vector Manipulation is the most complex category due to:
- Toggle skill mechanics (persistent contexts)
- Block manipulation (world modification)
- Player motion control (physics manipulation)
- Damage event interception (reflection mechanics)
- Entity tracking system (affection marking)

---

## Next Steps

1. ✅ Research complete - All 9 skills identified
2. ⏳ Read remaining 3 skill files (DirectedBlastwave, StormWing, BloodRetrograde, PlasmaCannon)
3. ⏳ Implement IPlayerMotion protocol
4. ⏳ Implement IBlockManipulation protocol
5. ⏳ Implement DirectedShock (simplest skill)
6. ⏳ Implement VecAccel (motion mechanics)
7. ⏳ Implement Groundshock (block mechanics)
8. ⏳ Implement toggle skill framework
9. ⏳ Implement VecDeviation (first toggle skill)
10. ⏳ Implement remaining skills

---

## Estimated Effort

- **Phase 1 (Simple Skills)**: 1 week
- **Phase 2 (Toggle Skills)**: 1 week
- **Phase 3 (Advanced Skills)**: 1 week
- **Total**: ~3 weeks for complete Vector Manipulation category

This is the most ambitious category yet, but follows established patterns from previous implementations.
