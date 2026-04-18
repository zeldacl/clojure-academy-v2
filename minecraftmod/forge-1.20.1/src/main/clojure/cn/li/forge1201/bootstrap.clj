(ns cn.li.forge1201.bootstrap
  "Shared bootstrap helper utilities for Forge 1.20.1"
  (:import [cn.li.forge1201.shim LazyForgeBootstrapBridge]))

(defn create-stone-properties
  []
  (LazyForgeBootstrapBridge/createStoneProperties))

(defn carrier-block-properties
  [base]
  (LazyForgeBootstrapBridge/carrierBlockProperties base))

(defn create-blocks-register
  [^String mod-id]
  (LazyForgeBootstrapBridge/createBlocksRegister mod-id))

(defn create-items-register
  [^String mod-id]
  (LazyForgeBootstrapBridge/createItemsRegister mod-id))

(defn create-creative-tabs-register
  [^String mod-id]
  (LazyForgeBootstrapBridge/createCreativeTabsRegister mod-id))

(defn create-block-entity-types-register
  [^String mod-id]
  (LazyForgeBootstrapBridge/createBlockEntityTypesRegister mod-id))

(defn create-menus-register
  [^String mod-id]
  (LazyForgeBootstrapBridge/createMenusRegister mod-id))

(defn create-fluid-types-register
  [^String mod-id]
  (LazyForgeBootstrapBridge/createFluidTypesRegister mod-id))

(defn create-fluids-register
  [^String mod-id]
  (LazyForgeBootstrapBridge/createFluidsRegister mod-id))

(defn create-carrier-scripted-dynamic-block
  [^String block-id ^String tile-id properties block-properties]
  (LazyForgeBootstrapBridge/createCarrierScriptedDynamicBlock block-id tile-id properties block-properties))

(defn create-dynamic-state-block
  [^String block-id properties block-properties]
  (LazyForgeBootstrapBridge/createDynamicStateBlock block-id properties block-properties))

(defn create-carrier-scripted-block
  [^String block-id ^String tile-id block-properties]
  (LazyForgeBootstrapBridge/createCarrierScriptedBlock block-id tile-id block-properties))

(defn create-plain-block
  [block-properties]
  (LazyForgeBootstrapBridge/createPlainBlock block-properties))

(defn create-fluid-type
  [luminosity density viscosity temperature can-hydrate supports-boating
   still-texture flowing-texture overlay-texture tint-color]
  (LazyForgeBootstrapBridge/createFluidType
    (int luminosity)
    (int density)
    (int viscosity)
    (int temperature)
    (boolean can-hydrate)
    (boolean supports-boating)
    ^String still-texture
    ^String flowing-texture
    (when overlay-texture ^String overlay-texture)
    (unchecked-int tint-color)))

(defn create-flowing-fluid-properties
  [fluid-type-supplier source-supplier flowing-supplier bucket-supplier block-supplier
   slope-find-distance level-decrease-per-block tick-rate explosion-resistance can-convert-to-source]
  (LazyForgeBootstrapBridge/createFlowingFluidProperties
    fluid-type-supplier source-supplier flowing-supplier bucket-supplier block-supplier
    (int slope-find-distance)
    (int level-decrease-per-block)
    (int tick-rate)
    (float explosion-resistance)
    (boolean can-convert-to-source)))

(defn create-source-fluid
  [properties]
  (LazyForgeBootstrapBridge/createSourceFluid properties))

(defn create-flowing-fluid
  [properties]
  (LazyForgeBootstrapBridge/createFlowingFluid properties))

(defn create-liquid-block
  [fluid-supplier]
  (LazyForgeBootstrapBridge/createLiquidBlock fluid-supplier))

(defn create-fluid-bucket
  [fluid-supplier]
  (LazyForgeBootstrapBridge/createFluidBucket fluid-supplier))

(defn create-scripted-block-entity-type
  [^String tile-id blocks block-id-resolver]
  (LazyForgeBootstrapBridge/createScriptedBlockEntityType tile-id blocks block-id-resolver))

(defn find-block
  [^String namespace ^String path]
  (LazyForgeBootstrapBridge/findBlock namespace path))

(defn get-air-block
  []
  (LazyForgeBootstrapBridge/getAirBlock))

(defn air-block?
  [block air-block]
  (LazyForgeBootstrapBridge/isAirBlock block air-block))
