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
