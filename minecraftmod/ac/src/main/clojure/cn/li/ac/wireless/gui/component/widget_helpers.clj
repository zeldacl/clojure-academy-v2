(ns cn.li.ac.wireless.gui.component.widget-helpers
	"Shared widget-level helpers for wireless GUI rendering."
	(:require [cn.li.mcmod.gui.components :as comp]))

(defn widget-textbox [widget]
	(comp/get-textbox-component widget))

(defn widget-drawtexture [widget]
	(comp/get-drawtexture-component widget))

(defn set-textbox-text! [widget text]
	(when-let [tb (widget-textbox widget)]
		(comp/set-text! tb text)))

(defn set-drawtexture! [widget texture-path]
	(when-let [dt (widget-drawtexture widget)]
		(comp/set-texture! dt texture-path)))

(defn set-drawtexture-color!
	[widget argb]
	(when-let [dt (widget-drawtexture widget)]
		(swap! (:state dt) assoc :color (unchecked-int argb))))

(defn set-tint-enabled!
	[widget enabled?]
	(when-let [t (comp/get-tint-component widget)]
		(swap! (:state t) assoc :enabled (boolean enabled?))))

(defn alpha-argb
	[argb alpha-f]
	(let [a (int (Math/round (* 255.0 (double alpha-f))))
				rgb (bit-and (long argb) 0x00FFFFFF)]
		(unchecked-int (bit-or (bit-shift-left (bit-and a 0xFF) 24) rgb))))