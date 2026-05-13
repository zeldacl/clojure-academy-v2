(ns cn.li.ac.wireless.gui.tab.view
	"View-layer helpers for wireless tab rendering and widget wiring."
	(:require [cn.li.mcmod.gui.cgui :as cgui]
						[cn.li.mcmod.gui.xml-parser :as cgui-doc]
						[cn.li.mcmod.gui.components :as comp]
						[cn.li.mcmod.gui.events :as events]
						[cn.li.ac.config.modid :as modid]))

(defn- widget-textbox [widget] (comp/get-textbox-component widget))
(defn- widget-drawtexture [widget] (comp/get-drawtexture-component widget))

(defn- set-textbox-text! [widget text]
	(when-let [tb (widget-textbox widget)]
		(comp/set-text! tb text)))

(defn- set-drawtexture! [widget texture-path]
	(when-let [dt (widget-drawtexture widget)]
		(comp/set-texture! dt texture-path)))

(defn- set-drawtexture-color!
	[widget argb]
	(when-let [dt (widget-drawtexture widget)]
		(swap! (:state dt) assoc :color (unchecked-int argb))))

(defn- set-tint-enabled!
	[widget enabled?]
	(when-let [t (comp/get-tint-component widget)]
		(swap! (:state t) assoc :enabled (boolean enabled?))))

(defn- alpha-argb
	[argb alpha-f]
	(let [a (int (Math/round (* 255.0 (double alpha-f))))
				rgb (bit-and (long argb) 0x00FFFFFF)]
		(unchecked-int (bit-or (bit-shift-left (bit-and a 0xFF) 24) rgb))))

(defn- ensure-template-hidden! [elem-template]
	(when elem-template
		(cgui/set-visible! elem-template false)))

(defn- attach-scroll-buttons! [btn-up btn-down elist]
	(when (and btn-up elist)
		(events/on-left-click btn-up (fn [_] (comp/list-progress-last! elist))))
	(when (and btn-down elist)
		(events/on-left-click btn-down (fn [_] (comp/list-progress-next! elist)))))

(defn base-wireless-doc []
	(cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_wireless.xml")))

(defn wireless-panel-from-main
	[main-root]
	(or (cgui/find-widget main-root "panel_wireless") main-root))

(defn rebuild-page!
	"Port of AcademyCraft WirelessPage.rebuildPage behavior:
	 - Connected element shows icon+name; connect icon click disconnects only if linked.
	 - Avail list: encrypted shows pass box and key; else hides them.
	 - Pass box confirm triggers connect.
	 - Key icon alpha toggles on focus.

	`wireless-root` must be the `panel_wireless` widget (paths are relative to it)."
	[wireless-root {:keys [linked avail connect-fn disconnect-fn name-fn encrypted?-fn]}]
	(let [wlist (cgui/find-widget wireless-root "zone_elementlist")
				elem-template (cgui/find-widget wireless-root "zone_elementlist/element")
				connected-elem (cgui/find-widget wireless-root "elem_connected")
				btn-up (cgui/find-widget wireless-root "btn_arrowup")
				btn-down (cgui/find-widget wireless-root "btn_arrowdown")
				elist (comp/element-list :spacing 2)
				linked-atom (atom linked)]

		(when wlist
			(comp/add-component! wlist elist))
		(ensure-template-hidden! elem-template)
		(attach-scroll-buttons! btn-up btn-down elist)

		(when connected-elem
			(let [icon-connect (cgui/find-widget connected-elem "icon_connect")
						icon-logo (cgui/find-widget connected-elem "icon_logo")
						text-name (cgui/find-widget connected-elem "text_name")
						connected? (some? linked)
						alpha (if connected? 1.0 0.6)
						name (if connected? (name-fn linked) "Not Connected")]
				(reset! linked-atom linked)
				(when icon-connect
					(set-drawtexture! icon-connect
														(if connected?
															(modid/asset-path "textures" "guis/icons/icon_connected.png")
															(modid/asset-path "textures" "guis/icons/icon_unconnected.png")))
					(set-drawtexture-color! icon-connect (alpha-argb 0xFFFFFFFF alpha))
					(set-tint-enabled! icon-connect connected?)
					(events/on-left-click icon-connect
						(fn [_]
							(when-let [t @linked-atom]
								(disconnect-fn t)))))
				(when icon-logo
					(set-drawtexture-color! icon-logo (alpha-argb 0xFFFFFFFF alpha)))
				(when text-name
					(set-textbox-text! text-name name))))

		(when elist
			(comp/list-clear! elist)
			(doseq [target avail]
				(when elem-template
					(let [elem (cgui/copy-widget elem-template)
								text-name (cgui/find-widget elem "text_name")
								icon-key (cgui/find-widget elem "icon_key")
								input-pass (cgui/find-widget elem "input_pass")
								icon-connect (cgui/find-widget elem "icon_connect")
								pass-box (when input-pass (widget-textbox input-pass))
								encrypted? (boolean (encrypted?-fn target))]

						(when text-name
							(set-textbox-text! text-name (name-fn target)))

						(if encrypted?
							(do
								(when icon-key
									(cgui/set-visible! icon-key true)
									(set-drawtexture-color! icon-key (alpha-argb 0xFFFFFFFF 0.6)))

								(when input-pass
									(cgui/set-visible! input-pass true))

								(when (and input-pass pass-box)
									;; enter to confirm
									(events/on-confirm-input pass-box
										(fn [_]
											(let [pwd (comp/get-text pass-box)]
												(connect-fn target pwd)
												(comp/set-text! pass-box ""))))
									;; focus brightens key icon
									(events/on-gain-focus input-pass
										(fn [_]
											(when icon-key
												(set-drawtexture-color! icon-key (alpha-argb 0xFFFFFFFF 1.0)))))
									(events/on-lost-focus input-pass
										(fn [_]
											(when icon-key
												(set-drawtexture-color! icon-key (alpha-argb 0xFFFFFFFF 0.6)))))))
							(do
								(when input-pass (cgui/set-visible! input-pass false))
								(when icon-key (cgui/set-visible! icon-key false))))

						(when icon-connect
							(events/on-left-click icon-connect
								(fn [_]
									(let [pwd (if (and encrypted? pass-box) (comp/get-text pass-box) "")]
										(connect-fn target pwd)
										(when pass-box (comp/set-text! pass-box ""))))))

						(comp/list-add! elist elem)))))

		wireless-root))

(defn setup-panel-logo!
	"Apply logo texture and optional breathe effect to the panel icon_logo widget."
	[root {:keys [logo-path logo-breathe?]} override-path]
	(when-let [logo (cgui/find-widget root "icon_logo")]
		(when logo-breathe?
			(comp/add-component! logo (comp/breathe-effect)))
		(let [path (or override-path
									 (when logo-path (modid/asset-path logo-path)))]
			(when path
				(set-drawtexture! logo path)))))

(defn set-connected-row-logo!
	[panel connected-row-logo-path]
	(when connected-row-logo-path
		(when-let [row-logo (cgui/find-widget panel "elem_connected/icon_logo")]
			(set-drawtexture! row-logo connected-row-logo-path))))