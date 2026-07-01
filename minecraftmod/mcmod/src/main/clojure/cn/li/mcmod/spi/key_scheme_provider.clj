(ns cn.li.mcmod.spi.key-scheme-provider
  "Key scheme SPI — abstracts keyboard state detection across platforms.

  Uses a plain function map instead of `defprotocol` or `definterface`:
  - `defprotocol` fails under AOT cross-module ClassLoader isolation
    (satisfies? returns false)
  - `definterface` + `reify`/`proxy` fails AOT compilation entirely
    (anonymous class generation in separate module)

  Contract: {:is-key-down? (fn [scheme-name key-idx] -> boolean)}"
  (:require [cn.li.mcmod.util.log :as log]))

(def ^:private provider (atom nil))

(defn- valid-provider?
  [provider-impl]
  (and (map? provider-impl)
       (fn? (:is-key-down? provider-impl))))

(defn install-provider!
  "Install the SPI implementation (called by Forge/Fabric platform).
  provider-impl must be a map with :is-key-down? fn of 2 args returning boolean."
  [provider-impl]
  (assert (valid-provider? provider-impl)
          "provider must be a map with :is-key-down? fn")
  (reset! provider provider-impl)
  (log/info "KeySchemeProvider installed")
  nil)

(defn require-provider
  "Get the installed provider, fail if not available."
  []
  (or @provider
      (throw (ex-info "KeySchemeProvider not installed. Did you forget to install it from platform layer?"
                     {:error :missing-spi}))))

(defn query-key-down?
  "Query key state using the installed provider (facade function).
  Args: scheme-name keyword, key-idx int or keyword. Returns boolean."
  [scheme-name key-idx]
  (let [p (require-provider)]
    ((:is-key-down? p) scheme-name key-idx)))
