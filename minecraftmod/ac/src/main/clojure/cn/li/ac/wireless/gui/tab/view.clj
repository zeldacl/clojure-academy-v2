(ns cn.li.ac.wireless.gui.tab.view
	"View-layer helpers for wireless tab rendering and widget wiring."
	(:require [cn.li.mcmod.gui.cgui-core :as cgui-core]
						[cn.li.mcmod.gui.xml-parser :as cgui-doc]
						[cn.li.mcmod.gui.components :as comp]
						[cn.li.mcmod.gui.events :as events]
						[cn.li.ac.wireless.gui.component.widget-helpers :as wh]
						[cn.li.ac.config.modid :as modid]
						[cn.li.mcmod.util.log :as log]))

(defn- ensure-template-hidden! [elem-template]
	(when elem-template
		(cgui-core/set-visible! elem-template false)))

(defn- attach-scroll-buttons! [btn-up btn-down elist]
	(when (and btn-up elist)
		(events/unlisten! btn-up :left-click)
		(events/on-left-click btn-up (fn [_] (comp/list-progress-last! elist))))
	(when (and btn-down elist)
		(events/unlisten! btn-down :left-click)
		(events/on-left-click btn-down (fn [_] (comp/list-progress-next! elist)))))

(defn base-wireless-doc []
	(cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_wireless.xml")))

(defn wireless-panel-from-main
	[main-root]
	(or (cgui-core/find-widget main-root "panel_wireless") main-root))

(defn rebuild-page!
	"Port of AcademyCraft WirelessPage.rebuildPage behavior:
	 - Connected element shows icon+name; connect icon click disconnects only if linked.
	 - Avail list: encrypted shows pass box and key; else hides them.
	 - Pass box confirm triggers connect.
	 - Key icon alpha toggles on focus.

	`wireless-root` must be the `panel_wireless` widget (paths are relative to it)."
	[wireless-root {:keys [linked avail connect-fn disconnect-fn name-fn encrypted?-fn]}]
	(let [wlist (cgui-core/find-widget wireless-root "zone_elementlist")
				elem-template (cgui-core/find-widget wireless-root "zone_elementlist/element")
				connected-elem (cgui-core/find-widget wireless-root "elem_connected")
				linked-atom (atom linked)
				_ (log/info "[rebuild-page!] wlist=" (pr-str (some? wlist)) "avail=" (count avail) "linked=" (pr-str linked))
				_ (log/info "[rebuild-page!] elem-template=" (pr-str (some? elem-template)))]

		;; Hide the template element
		(when elem-template
			(cgui-core/set-visible! elem-template false))

		;; Update connected element
		(when connected-elem
			(let [icon-connect (cgui-core/find-widget connected-elem "icon_connect")
						icon-logo (cgui-core/find-widget connected-elem "icon_logo")
						text-name (cgui-core/find-widget connected-elem "text_name")
						connected? (some? linked)
						alpha (if connected? 1.0 0.6)
						name (if connected? (name-fn linked) "Not Connected")]
				(reset! linked-atom linked)
				(when icon-connect
					(events/unlisten! icon-connect :left-click)
					(wh/set-drawtexture! icon-connect
						(if connected?
							(modid/asset-path "textures" "guis/icons/icon_connected.png")
							(modid/asset-path "textures" "guis/icons/icon_unconnected.png")))
					(wh/set-drawtexture-color! icon-connect (wh/alpha-argb 0xFFFFFFFF alpha))
					(wh/set-tint-enabled! icon-connect connected?)
					(events/on-left-click icon-connect
						(fn [_]
							(when-let [t @linked-atom]
								(disconnect-fn t)))))
				(when icon-logo
					(wh/set-drawtexture-color! icon-logo (wh/alpha-argb 0xFFFFFFFF alpha)))
				(when text-name
					(wh/set-textbox-text! text-name name))))

		;; Remove old element children (except the hidden template) and add new ones
		(when wlist
			;; Remove previous copies (keep the template widget if present)
			(let [old-children @(:children wlist)
						template-id (:id elem-template)]
				(doseq [child old-children]
					(when (and template-id (not= (:id child) template-id))
						(cgui-core/remove-widget! wlist child))))

			;; Add each available network as a direct child of zone_elementlist
			(doseq [[idx target] (map vector (range) avail)]
				(when elem-template
					(let [elem (cgui-core/copy-widget elem-template)
								text-name (cgui-core/find-widget elem "text_name")
								icon-key (cgui-core/find-widget elem "icon_key")
								input-pass (cgui-core/find-widget elem "input_pass")
								icon-connect (cgui-core/find-widget elem "icon_connect")
								pass-box (when input-pass (wh/widget-textbox input-pass))
								encrypted? (boolean (encrypted?-fn target))]

						;; Position: y = index * 16 (element height) from top of zone_elementlist
						(cgui-core/set-pos! elem 0 (* idx 16))
						(cgui-core/set-visible! elem true)

						(when text-name
							(wh/set-textbox-text! text-name (name-fn target)))

						(if encrypted?
							(do
								(when icon-key
									(cgui-core/set-visible! icon-key true)
									(wh/set-drawtexture-color! icon-key (wh/alpha-argb 0xFFFFFFFF 0.6)))
								(when input-pass
									(cgui-core/set-visible! input-pass true))
								(when (and input-pass pass-box)
									;; use input-pass widget so :owner-widget points to correct copy
									(events/on-confirm-input input-pass
										(fn [event]
											(let [pwd (:value event)]
												(connect-fn target pwd)
												(comp/set-text! pass-box ""))))
									(events/on-gain-focus input-pass
										(fn [_]
											(when icon-key
												(wh/set-drawtexture-color! icon-key (wh/alpha-argb 0xFFFFFFFF 1.0)))))
									(events/on-lost-focus input-pass
										(fn [_]
											(when icon-key
												(wh/set-drawtexture-color! icon-key (wh/alpha-argb 0xFFFFFFFF 0.6)))))))
							(do
								(when input-pass (cgui-core/set-visible! input-pass false))
								(when icon-key (cgui-core/set-visible! icon-key false))))

						(when icon-connect
							(events/on-left-click icon-connect
								(fn [_]
										(log/info "[rebuild-page!] connect icon CLICKED for" (name-fn target))
									(let [pwd (if (and encrypted? pass-box) (comp/get-text pass-box) "")]
										(connect-fn target pwd)
										(when pass-box (comp/set-text! pass-box ""))))))

						;; Add as direct child of zone_elementlist
						(cgui-core/add-widget! wlist elem)))))

		wireless-root))
(defn setup-panel-logo!
	"Apply logo texture and optional breathe effect to the panel icon_logo widget."
	[root {:keys [logo-path logo-breathe?]} override-path]
	(when-let [logo (cgui-core/find-widget root "icon_logo")]
		(when logo-breathe?
			(comp/add-component! logo (comp/breathe-effect)))
		(let [path (or override-path
									 (when logo-path (modid/namespaced-path logo-path)))]
			(when path
					(wh/set-drawtexture! logo path)))))

(defn set-connected-row-logo!
	[panel connected-row-logo-path]
	(when connected-row-logo-path
		(when-let [row-logo (cgui-core/find-widget panel "elem_connected/icon_logo")]
			(wh/set-drawtexture! row-logo connected-row-logo-path))))