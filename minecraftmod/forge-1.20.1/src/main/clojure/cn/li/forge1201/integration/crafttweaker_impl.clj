(ns cn.li.forge1201.integration.crafttweaker-impl
  "Forge-specific CraftTweaker integration implementation.

  This namespace provides the platform-specific CraftTweaker recipe registration
  using the recipe API defined in ac.integration.crafttweaker.recipes.

  CraftTweaker integration is optional - if CraftTweaker is not present,
  this module will not be loaded."
  (:require [cn.li.mcmod.platform.integration-runtime :as integration-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.bridge ForgeRuntimeBridge]))


(defn- load-class-no-init ^Class [class-name]
  ;; Used only for optional third-party dep checks (e.g. CraftTweaker).
  ;; Must NOT be used for Minecraft classes — use direct imports instead.
  (Class/forName class-name false (.getContextClassLoader (Thread/currentThread))))

;; CraftTweaker type conversion

(defn itemstack-to-item-spec
  "Convert a Minecraft ItemStack to AC item spec.

  Args:
    ^ItemStack stack - Minecraft ItemStack

  Returns:
    Item spec map {:item 'modid:item' :count N}"
  [stack]
  (when (and stack (not (ForgeRuntimeBridge/isItemStackEmpty stack)))
    (let [item-id (ForgeRuntimeBridge/getItemKeyString (ForgeRuntimeBridge/getItemFromStack stack))]
      (when item-id
        {:item  item-id
         :count (ForgeRuntimeBridge/getItemStackCount stack)}))))

(defn crafttweaker-itemstack-to-spec
  "Convert a CraftTweaker IItemStack to AC item spec.

  This function uses reflection to call CraftTweaker's conversion methods.
  It's designed to work even if CraftTweaker classes aren't on the classpath.

  Args:
    ct-stack - CraftTweaker IItemStack object

  Returns:
    Item spec map or nil"
  [ct-stack]
  (try
    ;; CraftTweaker's IItemStack can be converted to Minecraft ItemStack
    ;; via CraftTweakerMC.getItemStack(IItemStack)
    (let [ct-mc-class (load-class-no-init "com.blamejared.crafttweaker.api.CraftTweakerAPI")
          get-stack-method (.getMethod ct-mc-class "getIItemStack" (into-array Class [(ForgeRuntimeBridge/getItemStackClass)]))]
      (when-let [mc-stack (.invoke get-stack-method nil (into-array Object [ct-stack]))]
        (itemstack-to-item-spec mc-stack)))
    (catch Exception e
      (log/error "Failed to convert CraftTweaker IItemStack:" (ex-message e))
      nil)))

;; Imag Fusor support

(defn add-fusor-recipe
  "Add an Imag Fusor recipe via CraftTweaker.

  This is called from the Java wrapper class with @ZenMethod annotation.

  Args:
    output - CraftTweaker IItemStack (output item)
    input - CraftTweaker IItemStack (input item)
    energy - int (energy cost in IF)

  Returns:
    true if successful, false otherwise"
  [output input energy]
  (try
    (let [input-spec (crafttweaker-itemstack-to-spec input)
          output-spec (crafttweaker-itemstack-to-spec output)]
      (if (and input-spec output-spec)
        (do
          (integration-runtime/crafttweaker-add-fusor-recipe!
            input-spec output-spec (double energy))
          (log/info (str "CraftTweaker: Added Imag Fusor recipe - "
                        (integration-runtime/crafttweaker-describe-recipe
                          {:input input-spec
                           :output output-spec
                           :energy energy})))
          true)
        (do
          (log/error "CraftTweaker: Invalid Imag Fusor recipe parameters")
          false)))
    (catch Exception e
      (log/error "CraftTweaker: Failed to add Imag Fusor recipe:" (ex-message e))
      false)))

(defn remove-fusor-recipe
  "Remove an Imag Fusor recipe via CraftTweaker.

  Args:
    output - CraftTweaker IItemStack (output item to remove)

  Returns:
    Number of recipes removed"
  [output]
  (try
    (let [output-spec (crafttweaker-itemstack-to-spec output)]
      (if output-spec
        (integration-runtime/crafttweaker-remove-fusor-recipe!
          (:item output-spec))
        0))
    (catch Exception e
      (log/error "CraftTweaker: Failed to remove Imag Fusor recipe:" (ex-message e))
      0)))

;; Metal Former support

(defn- add-former-recipe
  "Internal helper to add Metal Former recipes.

  Args:
    output - CraftTweaker IItemStack
    input - CraftTweaker IItemStack
    mode - String ('etch', 'incise', or 'plate')

  Returns:
    true if successful"
  [output input mode]
  (try
    (let [input-spec (crafttweaker-itemstack-to-spec input)
          output-spec (crafttweaker-itemstack-to-spec output)
          ;; Default energy cost - could be made configurable
          energy 1000.0]
      (if (and input-spec output-spec)
        (do
          (integration-runtime/crafttweaker-add-former-recipe!
            input-spec output-spec mode energy)
          (log/info (str "CraftTweaker: Added Metal Former recipe (" mode ") - "
                        (integration-runtime/crafttweaker-describe-recipe
                          {:input input-spec
                           :output output-spec
                           :mode mode
                           :energy energy})))
          true)
        (do
          (log/error (str "CraftTweaker: Invalid Metal Former recipe parameters (mode: " mode ")"))
          false)))
    (catch Exception e
      (log/error (str "CraftTweaker: Failed to add Metal Former recipe (" mode "):") (ex-message e))
      false)))

(defn add-former-etch-recipe
  "Add a Metal Former etch recipe via CraftTweaker.

  Args:
    output - CraftTweaker IItemStack
    input - CraftTweaker IItemStack

  Returns:
    true if successful"
  [output input]
  (add-former-recipe output input "etch"))

(defn add-former-incise-recipe
  "Add a Metal Former incise recipe via CraftTweaker.

  Args:
    output - CraftTweaker IItemStack
    input - CraftTweaker IItemStack

  Returns:
    true if successful"
  [output input]
  (add-former-recipe output input "incise"))

(defn add-former-plate-recipe
  "Add a Metal Former plate recipe via CraftTweaker.

  Args:
    output - CraftTweaker IItemStack
    input - CraftTweaker IItemStack

  Returns:
    true if successful"
  [output input]
  (add-former-recipe output input "plate"))

(defn remove-former-recipe
  "Remove a Metal Former recipe via CraftTweaker.

  Args:
    output - CraftTweaker IItemStack (output item to remove)
    mode - String (optional: 'etch', 'incise', or 'plate')

  Returns:
    Number of recipes removed"
  [output & [mode]]
  (try
    (let [output-spec (crafttweaker-itemstack-to-spec output)]
      (if output-spec
        (integration-runtime/crafttweaker-remove-former-recipe!
          (:item output-spec) mode)
        0))
    (catch Exception e
      (log/error "CraftTweaker: Failed to remove Metal Former recipe:" (ex-message e))
      0)))

;; Initialization

(defn init-crafttweaker!
  "Initialize CraftTweaker integration.

  This is called during mod initialization if CraftTweaker is present.
  The actual ZenScript registration happens via @ZenClass annotations
  on the Java wrapper classes."
  []
  (log/info "CraftTweaker integration initialized (ZenScript classes will be auto-discovered)"))
