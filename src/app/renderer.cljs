(ns app.renderer
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [app.macros :refer [<!-with-err go-let go-try]])
  (:require [reagent.core :as r]
            [cljs.core.async :refer [<! >! put! take! chan close!]]
            [clojure.string :as string]
            [app.wallpaper-generator :as w]))

(def electron    (js/require "electron"))
(def ipcRenderer (.-ipcRenderer electron))
(def remote      (.-remote electron))
(def fs          (js/require "fs"))

(def current-window (.getCurrentWindow remote))

(defn query-selector [q]
  (.querySelector js/document q))

(defn query-selecor-all [q]
  (.querySelectorAll js/document q))

(defn title-bar []
  [:div#title-bar
   [:div.toolbar
    [:button.mini-size {:on-click #(.minimize current-window)}]
    [:button.close {:on-click #(.close current-window)}]]])

(defn scroll-to-tab [id]
  (let [element (query-selector (str "#tab" id))
        offset-top (.-offsetTop element)
        parent (.-parentElement element)
        parent-offset-top (.-offsetTop parent)
        relative-top (- offset-top parent-offset-top)
        distance (- relative-top (.-scrollTop parent))
        move (if (> distance 0) + -)
        tick 10
        speed 20
        step (quot (if (> distance 0) distance (- distance)) speed)]
    (doall
     (for [i (range (inc step))]
       (js/setTimeout
        #(set! (.-scrollTop parent)
               (if (= i step)
                 relative-top
                 (move (.-scrollTop parent) speed)))
        (* tick i))))))

(defn tabs [tabs-state title-list]
  (into
   [:div.tabs>div]
   (map-indexed
    (fn [id title]
      [:div.tab
       {:class (when (= id (:focus @tabs-state)) "focus")
        :on-click (fn [e] (swap! tabs-state assoc :focus id))}
       [:a title]]) title-list)))

(defn tab-contents [tabs-state contents]
  (into
   [:div.tab-contents>div
    {:style {:transform (str "translateY(-" (* (:focus @tabs-state) 100) "%)")}}]
   (doall
    (for [[title content] (partition 2 contents)]
      [:div.block>div
       [:h1 title]
       [:div content]]))))

(defn contents [& contents]
  (let [title-list (take-nth 2 contents)
        tabs-state (r/atom {:focus 0})]
    [:div.contents
     [tabs tabs-state title-list] [tab-contents tabs-state contents]]))

(defn get-user-home []
  (or js/process.env.HOME js/process.env.HOMEPATH js/process.env.USERPROFILE))

;; 注意，此处最大屏幕不是物理上的，而是最大屏幕长和最大屏幕宽
(defn get-largest-screen-size []
  (let [all-displays (.getAllDisplays (.-screen electron))
        width (max-key #(.-width %) all-displays)
        height (max-key #(.-height %) all-displays)]
    {:width width :height height}))

(def state (r/atom {:working? false
                    :wallpapers []}))

(def config (let [{:keys [width height]} (get-largest-screen-size)]
              (r/atom {:save-path (str (get-user-home) "/.cache/bismuth/")
                       :max-wp-num 50
                       :width width
                       :height height})))

(def config-dir (str (get-user-home) "/.config/bismuth/"))
(def config-file (str config-dir "config.json"))

(defn file-exist? [path]
  (let [result-chan (chan)]
    (.stat fs path
           #(if %
              (if (= (.-code %) "ENOENT")
                (put! result-chan {:result false})
                (put! result-chan {:error %}))
              (put! result-chan {:result true})))
    result-chan))

(defn write-file [file data & {:keys [encoding mode flag]
                               :or {encoding "utf8" mode 0666 flag "w"}}]
  (let [error-chan (chan)]
    (.writeFile fs file data encoding mode flag
                #(if % (put! error-chan {:error %}) (close! error-chan)))
    error-chan))

(defn read-file [file & {:keys [encoding flag]
                         :or {encoding nil flag "r"}}]
  (let [result-chan (chan)]
    (.readFile fs file encoding flag
               #(if %1
                  (put! result-chan {:error %1})
                  (put! result-chan {:result %2})))
    result-chan))

(defn read-dir [path & {:keys [encoding]
                        :or {encoding "utf8"}}]
  (let [result-chan (chan)]
    (.readdir fs path encoding
              #(if %1
                 (put! result-chan {:error %1})
                 (put! result-chan {:result (js->clj %2)})))
    result-chan))

(defn unlink [path]
  (let [error-chan (chan)]
    (.unlink fs path #(if % (put! error-chan {:error %1}) (close! error-chan)))
    error-chan))

(defn do-for-every-key [m func]
  (if (and (map? m) func)
    (into
     {}
     (for [[k v] m]
       [(func k) (do-for-every-key v func)]))
    m))

(defn read-json-str [string & {:keys [key-fn value-fn]
                               :or {key-fn nil value-fn nil}}]
  (do-for-every-key (js->clj (js/JSON.parse string nil)) key-fn))

(defn write-json-str [data & {:keys [space key-fn value-fn]
                              :or {space nil key-fn nil value-fn nil}}]
  (js/JSON.stringify (clj->js (do-for-every-key data key-fn)) value-fn space))

(defn save-config []
  (write-file config-file
              (write-json-str @config :space 4)))

(defn mkdir
  ([path] (mkdir path 0777))
  ([path mode]
   (let [error-chan (chan)]
     (.mkdir fs path mode
             #(if (and % (not= (.-code %) "EEXIST"))
                (put! error-chan {:error %})
                (close! error-chan)))
     error-chan)))

(defn save-wallpaper [wallpaper-base64]
  (let [data (subs wallpaper-base64 22)
        file-name (str "wp" (.getTime (js/Date.)) ".png")
        file-path (str (:save-path @config) file-name)
        result-chan (chan)]
    (go-try
     (when-not (<!-with-err (file-exist? (:save-path @config)))
       (<!-with-err (mkdir (:save-path @config))))
     (<!-with-err (write-file file-path data :encoding "base64"))
     (put! result-chan {:result file-path})
     (catch :default e
       (put! result-chan {:error e})))
    result-chan))

(defn delete-old-wallpaper []
  (let [num (- (count (:wallpapers @state)) (:max-wp-num @config))
        old-files (take-last num (:wallpapers @state))
        error-chan (chan)]
    (if (> num 0)
      (go-try
       (swap! state assoc :wallpapers (take (:max-wp-num @config) (:wallpapers @state)))
       (doseq [file old-files]
         (<!-with-err (unlink file)))
       (close! error-chan)
       (catch :default e
         (put! error-chan {:error e})))
      (close! error-chan))
    error-chan))

(defn preview []
  [:div#preview
   [:div#wallpaper-history>div
    (doall
     (for [src (:wallpapers @state)]
       ^{:key (hash src)}
       [:img {:src src}]))]
   [:div.toolbar
    [:button#new-wallpaper
     {:on-click (fn [e]
                  (swap! state assoc :working? true)
                  (go-try
                   (let [result (<! (w/generate w/line (:width @config) (:height @config)))
                         file-path (<!-with-err (save-wallpaper result))]
                     (swap! state update-in [:wallpapers] #(cons file-path %))
                     (swap! state assoc :working? false)
                     (<!-with-err (delete-old-wallpaper)))
                   (catch :default e
                      (js/console.error e))))
      :disabled (:working? @state)}
     [:div "新壁纸"]]]])

(defn parse-int [s]
  (js/parseInt s))

(defn generator-setting []
  [:div
   [:p "生成器选择#未完工"]
   [:label "line" [:input {:type "checkbox"}]]
   [:p "生成壁纸大小"]
   [:p
    "宽:" [:input {:value (:width @config) :type "number"
                   :on-change #(do (swap! config assoc :width (-> % .-target .-value parse-int))
                                   (save-config))}]
    "高:" [:input {:value (:height @config) :type "number"
                   :on-change #(do (swap! config assoc :height (-> % .-target .-value parse-int))
                                   (save-config))}]]
   [:p "壁纸保存路径"
    [:input {:value (:save-path @config)
             :on-change #(do (swap! config assoc :save-path (-> % .-target .-value))
                             (save-config))}]]
   [:p "最大壁纸保留数量"
    [:input {:value (:max-wp-num @config) :type "number"
             :on-change #(do (swap! config assoc :max-wp-num (-> % .-target .-value parse-int))
                             (save-config))}]]])

(defn body []
  [:div [title-bar]
   [contents
    "壁纸设置" [preview]
    "生成器设置" [generator-setting]
    "自动刷新" [:div
                [:label "启用" [:input {:type "checkbox"}]]
                [:p "时间间隔" [:input]]
                [:div "壁纸设置命令:"
                 [:div "预置:"
                  [:button "GNOME"] [:button "KDE"]
                  [:button "Xfce"] [:button "Cinnamon"]]
                 [:textarea]]]
    "关于" [:div "..."]]])

(defn init []
  (enable-console-print!)
  (go-try
   (if (<!-with-err (file-exist? config-file))
     (let [data (<!-with-err (read-file config-file))
           cfg (read-json-str data :key-fn keyword)]
       (reset! config cfg))
     (do (<!-with-err (mkdir config-dir))
         (<!-with-err
          (write-file config-file
                      (write-json-str @config :space 4)))))
   (swap! state assoc :wallpapers
                (->> (<!-with-err (read-dir (:save-path @config)))
                     reverse
                     (map #(str (:save-path @config) %))))
   (<!-with-err (delete-old-wallpaper))
   (r/render body (query-selector "#app"))
   (catch :default e
     (js/console.error e))))
