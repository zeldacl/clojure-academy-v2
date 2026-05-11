(ns cn.li.fabric1201.integration.crafttweaker-impl
  "Fabric CraftTweaker integration (stub/placeholder).

  This namespace provides CraftTweaker recipe registration for Fabric.
  Note: CraftTweaker support on Fabric is currently limited compared to Forge.

  CraftTweaker integration is optional - if CraftTweaker is not present,
  this module will not be loaded."
  (:require [cn.li.mcmod.platform.integration-runtime :as integration-runtime]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; CraftTweaker Support Detection (Fabric)
;; ============================================================================

(defn- crafttweaker-available?
  "Check if CraftTweaker is available on Fabric.

  Note: CraftTweaker support on Fabric is limited. This checks for the
  presence of CraftTweaker classes at runtime."
  []
  (try
    (some? (Class/forName "com.blamejared.crafttweaker.api.CraftTweakerAPI" false
                          (.getContextClassLoader (Thread/currentThread))))
    (catch ClassNotFoundException _
      false)))

;; ============================================================================
;; Item Stack Conversion
;; ============================================================================

(defn itemstack-to-item-spec
  "Convert a Minecraft ItemStack to AC item spec.

  Args:
    stack - Minecraft ItemStack

  Returns:
    Item spec map {:item 'modid:item' :count N} or nil"
  [stack]
  (try
    (when (and stack (not (.isEmpty ^net.minecraft.world.item.ItemStack stack)))
      (let [item (.getItem ^net.minecraft.world.item.ItemStack stack)
            registry-name (str (.. item builtInRegistryHolder key))
            count (.getCount ^net.minecraft.world.item.ItemStack stack)]
        {:item registry-name
         :count (int count)}))
    (catch Exception e
      (log/warn "Failed to convert ItemStack to spec:" (ex-message e))
      nil)))

(defn crafttweaker-itemstack-to-spec
  "Convert a CraftTweaker IItemStack to AC item spec (Fabric version).

  Note: This is a placeholder. Full CraftTweaker support on Fabric
  requires the CraftTweaker module to be loaded.

  Args:
    ct-stack - CraftTweaker IItemStack object

  Returns:
    Item spec map or nil"
  [ct-stack]
  (try
    ;; Try to convert CraftTweaker IItemStack to Minecraft ItemStack
    (when (and (crafttweaker-available?) ct-stack)
      ;; Attempt reflection-based conversion
      (log/debug "Converting CraftTweaker IItemStack on Fabric (experimental)")
      ;; Placeholder - actual conversion depends on CraftTweaker's Fabric support
      nil)
    (catch Exception e
      (log/warn "CraftTweaker item conversion failed on Fabric:" (ex-message e))
      nil)))

;; ============================================================================
;; Recipe Registration (Stubs for Fabric)
;; ============================================================================

(defn add-fusor-recipe
  "Add an Imag Fusor recipe via CraftTweaker (Fabric stub).

  Args:
    output - CraftTweaker IItemStack (output item)
    input - CraftTweaker IItemStack (input item)
    energy - int (energy cost in IF)

  Returns:
    true if successful, false if CraftTweaker not available"
  [output input energy]
  (try
    (if (crafttweaker-available?)
      (do
        (log/info "CraftTweaker Imag Fusor recipe registration on Fabric (awaiting full support)")
        true)
      (do
        (log/debug "CraftTweaker not available - recipe registration skipped")
        false))
    (catch Exception e
      (log/error "Failed to add CraftTweaker Imag Fusor recipe on Fabric:" (ex-message e))
      false)))

(defn add-former-recipe
  "Add a Metal Former recipe via CraftTweaker (Fabric stub).

  Args:
    output - CraftTweaker IItemStack (output item)
    input - CraftTweaker IItemStack (input item)
    mode - String ('etch', 'incise', or 'plate')
    energy - int (energy cost in IF)

  Returns:
    true if successful, false if CraftTweaker not available"
  [output input mode energy]
  (try
    (if (crafttweaker-available?)
      (do
        (log/info (str "CraftTweaker Metal Former recipe registration on Fabric (mode=" mode ", awaiting full support)"))
        true)
      (do
        (log/debug "CraftTweaker not available - recipe registration skipped")
        false))
    (catch Exception e
      (log/error (str "Failed to add CraftTweaker Metal Former recipe (" mode ") on Fabric:") (ex-message e))
      false)))

(defn remove-fusor-recipe
  "Remove an Imag Fusor recipe via CraftTweaker (Fabric stub).

  Args:
    output - CraftTweaker IItemStack (output item to remove)

  Returns:
    true if successful, false if not available"
  [output]
  (try
    (if (crafttweaker-available?)
      (do
        (log/info "CraftTweaker Imag Fusor recipe removal on Fabric (awaiting full support)")
        true)
      false)
    (catch Exception e
      (log/error "Failed to remove CraftTweaker Imag Fusor recipe on Fabric:" (ex-message e))
      false)))

(defn remove-former-recipe
  "Remove a Metal Former recipe via CraftTweaker (Fabric stub).

  Args:
    output - CraftTweaker IItemStack (output item to remove)
    mode - String (optional, optional recipe mode filter)

  Returns:
    true if successful, false if not available"
  [output & {:keys [mode]}]
  (try
    (if (crafttweaker-available?)
      (do
        (log/info (str "CraftTweaker Metal Former recipe removal on Fabric" (when mode (str " (mode=" mode ")"))))
        true)
      false)
    (catch Exception e
      (log/error "Failed to remove CraftTweaker Metal Former recipe on Fabric:" (ex-message e))
      false)))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-crafttweaker!
  "Initialize CraftTweaker integration for Fabric.

  This is called during mod initialization if CraftTweaker is detected.

  Returns:
    nil"
  []
  (if (crafttweaker-available?)
    (do
      (log/info "CraftTweaker integration initialized for Fabric (limited support)")
      nil)
    (do
      (log/debug "CraftTweaker not available on Fabric - integration disabled")
      nil)))
