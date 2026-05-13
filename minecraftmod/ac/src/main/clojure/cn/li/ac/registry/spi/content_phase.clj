(ns cn.li.ac.registry.spi.content-phase
	"SPI facade for declaring and listing AC content phases."
	(:require [cn.li.ac.registry.content-plan-builder :as plan-builder]))

(defn declare-content-phase!
	"Declare (or replace by :phase key) a content phase plugin spec."
	[phase-spec]
	(plan-builder/register-phase-plugin! phase-spec))

(defn list-content-phases
	"List currently declared content phases in load order."
	[]
	(plan-builder/build-load-plan))