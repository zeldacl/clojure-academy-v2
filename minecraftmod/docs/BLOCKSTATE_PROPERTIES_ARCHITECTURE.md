BlockState Properties Architecture
===================================

This document explains how BlockState properties are now defined and used,
enabling new properties to be added with Clojure-only changes.

Problem Solved
--------------
Previously, BlockState properties were hardcoded in Java:
- Adding a new property required Java changes (NodeDynamicBlock)
- Properties couldn't be dynamically configured
- Platform code and business logic were entangled

Current Solution
----------------

1. BUSINESS LOGIC (Core)
   Location: my-mod.block.wireless_node/block-state-properties
   
   (def block-state-properties
     {:energy {:name "energy"
               :type :integer
               :min 0
               :max 4
               :default 0}
      :connected {:name "connected"
                  :type :boolean
                  :default false}})
   
   Blocks that need these properties declare them:
   (bdsl/defblock wireless-node-basic
     ...
     :block-state-properties block-state-properties
     ...)

2. PROPERTY GENERATION (Core)
   Location: my-mod.block.blockstate-properties
   
   - Reads property definitions from block specs
   - Auto-generates Minecraft Property objects (IntegerProperty, BooleanProperty)
   - Stores them in a registry for platform access
   - Provides query APIs: get-property, get-all-properties, etc.

3. PLATFORM INTEGRATION (Fabric/Forge)
   
   a) Runtime Block Registration (mod.clj):
   
      For each block with block-state-properties:
      1. Create NodeDynamicBlock instance
      2. Get Property objects via bsp/get-all-properties(block-id)
      3. Call block.setBlockIdAndProperties(block-id, properties)
      4. This injects properties before Minecraft initializes BlockState
   
   b) Data Generation (datagen):
   
      When building blockstate JSON, get-property() retrieves Property objects
      dynamically from the registry instead of hardcoded Java constants.

Adding New Properties
---------------------

Example: Add a "powered" property to wireless nodes.

Step 1: Update block-state-properties in core/block/wireless_node.clj

  (def block-state-properties
    {:energy {...}
     :connected {...}
     :powered {:name "powered"           ; ← NEW
               :type :boolean
               :default false}})

Step 2: Update blockstate definition if generating JSON variants

  core/block/blockstate_definition.clj may need updates to generate
  model variants for the new property.

Step 3: Done!

That's it. No Java changes needed.
- Platform code automatically discovers the new property
- Property objects are auto-generated
- Both runtime and datagen work seamlessly

Architecture Benefits
---------------------

✓ Single source of truth: Clojure metadata
✓ No hardcoded block names in platform layer
✓ New properties added with Clojure-only changes
✓ Consistent across Fabric and Forge
✓ Easy to support new platforms (just wire up property injection)
✓ Flexible: support multiple property types without hardcoding

Implementation Details
----------------------

NodeDynamicBlock (Java):
- Generic Block subclass that accepts injected properties
- Calls setBlockIdAndProperties() before state initialization
- Uses injected properties in createBlockStateDefinition()
- No hardcoded property knowledge

blockstate-properties.clj (Clojure):
- create-property(): Factory for Property objects
- register-block-properties!(): Stores properties in registry
- get-property(): Retrieves by block-id + property-key
- init-all-properties!(): Called during mod initialization

Both Fabric and Forge:
- Import blockstate-properties module  
- Call init-all-properties!() early in mod-init
- When registering blocks, inject properties to NodeDynamicBlock
- When generating datagen, query properties dynamically

See Also
--------

- Core: my-mod.block.blockstate-properties
- Core Data: my-mod.block.wireless-node/block-state-properties
- Fabric Platform: my-mod.fabric1201.mod/register-blocks!
- Forge Platform: my-mod.forge1201.mod/register-all-blocks!
- Datagen: my-mod.forge1201.datagen.blockstate-provider
