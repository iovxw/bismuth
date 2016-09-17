(ns app.main)

(def electron       (js/require "electron"))
(def app            (.-app electron))
(def BrowserWindow  (.-BrowserWindow electron))
(def globalShortcut (.-globalShortcut electron))
(def ipcMain        (.-ipcMain electron))
(def Tray           (.-Tray electron))
(def Menu           (.-Menu electron))

(goog-define dev? false)

(defn resource [file]
  (if dev?
    (str js/__dirname "/../../" file)
    (str js/__dirname "/" file)))

(defn resource-with-header [file]
  (str "file://" (resource file)))

(defn load-page [window]
  (.loadURL window (resource-with-header "index.html")))

(defn registr-global-shortcut [shortcut func]
  (.register globalShortcut shortcut func))

(defn unregistr-global-shortcut [shortcut]
  (.unregister globalShortcut shortcut))

(defn unregistr-all-global-shortcut []
  (.unregisterAll globalShortcut))

(defn global-shortcutis-is-registered? [shortcut]
  (.isRegistered globalShortcut shortcut))

(defn get-cursor-point [screen]
  (.getCursorScreenPoint screen))

(defn build-menu [template]
  (.buildFromTemplate Menu (clj->js template)))

(def main-window (atom nil))

(defn mk-window [w h & {:keys [skip-taskbar min-width min-height
                               always-on-top focusable resizable
                               x y frame show icon]}]
  (BrowserWindow. (clj->js {:width w :height h :frame frame :show show
                            :minWidth min-width :minHeight min-height
                            :skipTaskbar skip-taskbar
                            :alwaysOnTop always-on-top
                            :focusable focusable :icon icon
                            :resizable resizable :x x :y y})))

(def tray (atom nil))

(enable-console-print!)

(defn init-browser []
  (let [screen (.-screen electron)
        width 800
        height 450
        display (.getPrimaryDisplay screen)
        bounds (.-bounds display)
        x (+ (.-x bounds) (- (/ (.-width bounds) 2) (/ width 2)))
        y (+ (.-y bounds) (- (/ (.-height bounds) 2) (/ height 2)))]
    (reset! main-window (mk-window width height :frame false
                                   :min-width width :min-height height
                                   :x x :y y :icon (resource "images/icon.png"))))

  (load-page @main-window)
  (when dev?
    (.openDevTools @main-window))

  (let [t (Tray. (resource "images/icon.png"))
        menu (build-menu [{:label "Exit"
                           :click #(.quit app)}])]
    (.setToolTip t "bismuth")
    (.setContextMenu t menu)
    (reset! tray t))

  (.on @main-window "closed" #(reset! main-window nil)))

(defn init []
  (.on app "window-all-closed" #(when-not (= js/process.platform "darwin") (.quit app)))
  (.on app "will-quit" #(do (unregistr-all-global-shortcut)
                            (.destroy @tray)))
  (.on app "ready" init-browser)
  (set! *main-cli-fn* (fn [] nil)))
