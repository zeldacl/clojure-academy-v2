(ns cn.li.mcmod.spi.vanilla-input-control
  "Vanilla input suppression SPI - abstracts LMB/RMB suppression across platforms.
   
   Both Forge and Fabric use the same Minecraft Options API internally,
   but this SPI shields platform-specific details and ensures consistent behavior."
  (:require [cn.li.mcmod.util.log :as log]))

(def ^:private suppressor (atom nil))

(defprotocol VanillaInputSuppressor
  "Abstraction for suppressing Vanilla input (LMB/RMB)"
  (suppress-vanilla-attack-use! [this minecraft-client]
    "Suppress Vanilla attack (LMB) and use (RMB) key presses.
     Called when AC ability is active.
     
     Args:
     - minecraft-client: net.minecraft.client.Minecraft instance (or nil to get current)")
  
  (restore-vanilla-input! [this minecraft-client]
    "Restore Vanilla input handling.
     Called when AC ability deactivates.
     
     Args:
     - minecraft-client: net.minecraft.client.Minecraft instance (or nil to get current)"))

(defn install-suppressor!
  "Install the SPI implementation (called by Forge/Fabric platform).
   
   Args:
   - suppressor-impl: object implementing VanillaInputSuppressor protocol
   
   Called during platform initialization."
  [suppressor-impl]
  (assert (satisfies? VanillaInputSuppressor suppressor-impl)
          "suppressor must implement VanillaInputSuppressor protocol")
  (reset! suppressor suppressor-impl)
  (log/info "VanillaInputSuppressor installed")
  nil)

(defn require-suppressor
  "Get the installed suppressor, fail if not available.
   
   Returns: the installed VanillaInputSuppressor
   Throws: ex-info if suppressor not installed"
  []
  (or @suppressor
      (throw (ex-info "VanillaInputSuppressor not installed"
                     {:error :missing-spi}))))

(defn suppress-vanilla-input!
  "Suppress Vanilla attack/use through the installed suppressor (facade).
   
   Args:
   - minecraft-client: net.minecraft.client.Minecraft (or nil)"
  [minecraft-client]
  (let [suppressor (require-suppressor)]
    (suppress-vanilla-attack-use! suppressor minecraft-client)))

(defn restore-vanilla-inputs!
  "Restore Vanilla input through the installed suppressor (facade).
   
   Args:
   - minecraft-client: net.minecraft.client.Minecraft (or nil)"
  [minecraft-client]
  (let [suppressor (require-suppressor)]
    (restore-vanilla-input! suppressor minecraft-client)))
