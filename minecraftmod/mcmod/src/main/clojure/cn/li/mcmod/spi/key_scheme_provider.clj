(ns cn.li.mcmod.spi.key-scheme-provider
  "Key scheme SPI - abstracts keyboard state detection across platforms.
   
   Shields platform differences (Forge: event-driven via InputEvent$Key,
   Fabric: polling via GLFW). All platforms implement the same protocol."
  (:require [cn.li.mcmod.util.log :as log]))

(def ^:private provider (atom nil))

(defprotocol KeySchemeProvider
  "Abstraction for checking key state across different platforms"
  (is-key-down? [this scheme-name key-idx]
    "Check if a key is currently pressed.
     
     Args:
     - scheme-name: keyword (:original or :alternative)
     - key-idx: integer or keyword identifying the key
     
     Returns: boolean (true if key is pressed, false otherwise)"))

(defn install-provider!
  "Install the SPI implementation (called by Forge/Fabric platform).
   
   Args:
   - provider-impl: object implementing KeySchemeProvider protocol
   
   Called during platform initialization before keybindings are used."
  [provider-impl]
  (assert (satisfies? KeySchemeProvider provider-impl)
          "provider must implement KeySchemeProvider protocol")
  (reset! provider provider-impl)
  (log/info "KeySchemeProvider installed")
  nil)

(defn require-provider
  "Get the installed provider, fail if not available.
   
   Returns: the installed KeySchemeProvider
   Throws: ex-info if provider not installed (indicates init order bug)"
  []
  (or @provider
      (throw (ex-info "KeySchemeProvider not installed. Did you forget to install it from platform layer?"
                     {:error :missing-spi}))))

(defn query-key-down?
  "Query key state using the installed provider (facade function).
   
   Args:
   - scheme-name: keyword (:original or :alternative)
   - key-idx: integer or keyword
   
   Returns: boolean
   
   This is the primary API used by key polling code."
  [scheme-name key-idx]
  (let [provider (require-provider)]
    (is-key-down? provider scheme-name key-idx)))
