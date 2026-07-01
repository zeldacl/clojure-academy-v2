(ns cn.li.ac.bootstrap
  "AC keybinding initialization — self-registers into mcmod lifecycle.

  Platforms do NOT call this namespace directly. Instead:
  1. AC registers keybinding configs into `mcmod.spi.keybinding-registry`
     and a post-SPI init callback into `mcmod.lifecycle`.
  2. Platforms call `lifecycle/run-post-spi-client-init!` after SPI install.
  3. The key-mapping adapter reads from the neutral keybinding registry.

  This eliminates the PLATFORM_NO_AC_STATIC_OR_HARDCODED_DEPENDENCY violation
  where forge/fabric were directly requiring cn.li.ac.bootstrap."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.spi.keybinding-registry :as kb-registry]
            [cn.li.ac.input-ids :as input-ids]))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn initialize-keybindings!
  "Initialize the keybinding system for AC.

  Prerequisites:
  - mcmod SPI providers (key-scheme-provider, vanilla-input-control) must be installed

  This function:
  1. Registers AC keybinding configs into the neutral mcmod registry
     (so platform key-mapping adapters can read them without AC dependency)
  2. Calls AC input_ids bootstrap to register all keybindings
     (registers handlers into mcmod.protocol.keyboard-input)"
  []
  (try
    (log/info "Initializing AC keybindings...")

    ;; 1. Register keybinding configs into neutral registry.
    ;;    Platform key-mapping adapters read from this, not from AC directly.
    (kb-registry/register-keybinding-configs!
      "ac"
      (input-ids/get-input-ids))

    ;; 2. Bootstrap keybindings (registers handlers to mcmod.protocol.keyboard-input)
    (input-ids/bootstrap!)

    (log/info "AC keybindings initialization complete")
    nil

    (catch Exception e
      (log/error e "Failed to initialize AC keybindings")
      (throw e))))

;; ============================================================================
;; Self-registration into neutral mcmod lifecycle
;; ============================================================================

(let [registered? (volatile! false)]
  (defn- register-post-spi-init!
    "Register AC keybinding init as a post-SPI client callback.
    Idempotent — only registers once."
    []
    (when-not @registered?
      (vreset! registered? true)
      (lifecycle/register-post-spi-client-init! initialize-keybindings!)
      (log/info "AC keybinding init registered into mcmod lifecycle (post-spi-client-init)"))))

;; Auto-register when this namespace is loaded (during content-init via ServiceLoader).
(register-post-spi-init!)
