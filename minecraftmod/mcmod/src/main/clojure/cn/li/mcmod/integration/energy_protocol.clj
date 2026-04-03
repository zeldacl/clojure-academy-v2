(ns cn.li.mcmod.integration.energy-protocol
  "Platform-neutral energy protocols for external mod integration.

  These protocols abstract external energy systems (Forge Energy, IC2 EU, etc.)
  so that AC code can work with them without importing platform-specific classes.")

(defprotocol IExternalEnergyProvider
  "Protocol for blocks that can provide energy to external systems."
  (can-provide-energy? [this]
    "Returns true if this block can provide energy to external systems.")
  (extract-external-energy [this amount simulate?]
    "Extract energy from this block for external systems.
    Returns the amount actually extracted."))

(defprotocol IExternalEnergyReceiver
  "Protocol for blocks that can receive energy from external systems."
  (can-receive-energy? [this]
    "Returns true if this block can receive energy from external systems.")
  (receive-external-energy [this amount simulate?]
    "Receive energy from external systems into this block.
    Returns the amount actually received."))

(defprotocol IExternalEnergyStorage
  "Protocol for blocks that expose energy storage to external systems."
  (get-external-energy-stored [this]
    "Get the amount of energy stored, in external system units.")
  (get-external-max-energy [this]
    "Get the maximum energy capacity, in external system units.")
  (get-conversion-rate [this]
    "Get the conversion rate from IF to external energy units (1 IF = X external)."))
