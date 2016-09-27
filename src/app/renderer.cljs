(ns app.renderer
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [app.macros :refer [<!-with-err go-let go-try]])
  (:require [reagent.core :as r]
            [cljs.core.async :refer [<! >! put! take! chan close!]]
            [clojure.string :as string]
            [app.wallpaper-generator :as w]
            [app.json :as json]
            [app.io :as io]
            [app.utils :as u]))

(def electron    (js/require "electron"))
(def ipcRenderer (.-ipcRenderer electron))
(def remote      (.-remote electron))

(def current-window (.getCurrentWindow remote))

(defn title-bar []
  [:div#title-bar
   [:div.toolbar
    [:button.mini-size {:on-click #(.minimize current-window)}]
    [:button.close {:on-click #(.close current-window)}]]])

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

;; 注意，此处最大屏幕不是物理上的，而是最大屏幕长和最大屏幕宽
(defn get-largest-screen-size []
  (let [all-displays (js->clj (.getAllDisplays (.-screen electron)))
        all-width (map #(get-in % ["size" "width"]) all-displays)
        all-height (map #(get-in % ["size" "height"]) all-displays)
        width (apply max all-width)
        height (apply max all-height)]
    {:width width :height height}))

(def state (r/atom {:working? false
                    :wallpapers []}))

(def config (let [{:keys [width height]} (get-largest-screen-size)]
              (r/atom {:save-path (str (u/get-user-home) "/.cache/bismuth/")
                       :max-wp-num 50
                       :width width
                       :height height})))

(def config-dir (str (u/get-user-home) "/.config/bismuth/"))
(def config-file (str config-dir "config.json"))

(defn save-config []
  (io/write-file config-file
                 (json/write-str @config :space 4)))

(defn save-wallpaper [wallpaper-base64]
  (let [data (subs wallpaper-base64 22)
        file-name (str "wp" (.getTime (js/Date.)) ".png")
        file-path (str (:save-path @config) file-name)
        result-chan (chan)]
    (go-try
     (when-not (<!-with-err (io/file-exist? (:save-path @config)))
       (<!-with-err (io/mkdir (:save-path @config))))
     (<!-with-err (io/write-file file-path data :encoding "base64"))
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
         (<!-with-err (io/unlink file)))
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

(defn generator-setting []
  [:div#generator-setting
   [:p "生成器选择"]
   [:label.generator-preview
    [:div {:style {:background-image "url(images/background.png)"}}
     [:div.footer>div.right "line" [:input {:type "checkbox" :checked true}]]]]
   [:p "生成壁纸大小:"]
   [:label.comment [:a "宽"]
    [:input {:value (:width @config) :type "number"
             :style {:width "5em"}
             :on-change #(do (swap! config assoc :width (-> % .-target .-value u/parse-int))
                             (save-config))}]
    [:label.comment [:a "高"]
     [:input {:value (:height @config) :type "number"
              :style {:width "5em"}
              :on-change #(do (swap! config assoc :height (-> % .-target .-value u/parse-int))
                              (save-config))}]]]
   [:p "壁纸保存路径:"]
   [:input {:value (:save-path @config)
            :style {:width "20em"}
            :on-change #(do (swap! config assoc :save-path (-> % .-target .-value))
                            (save-config))}]
   [:p "最大壁纸保留数量:"]
   [:input {:value (:max-wp-num @config) :type "number"
            :style {:width "3em"}
            :on-change #(do (swap! config assoc :max-wp-num (-> % .-target .-value u/parse-int))
                            (save-config))}]])

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
   (if (<!-with-err (io/file-exist? config-file))
     (let [data (<!-with-err (io/read-file config-file))
           cfg (json/read-str data :key-fn keyword)]
       (reset! config cfg))
     (do (<!-with-err (io/mkdir config-dir))
         (<!-with-err (save-config))))
   (swap! state assoc :wallpapers
          (->> (<!-with-err (io/read-dir (:save-path @config)))
               reverse
               (map #(str (:save-path @config) %))))
   (<!-with-err (delete-old-wallpaper))
   (r/render body (u/query-selector "#app"))
   (catch :default e
     (js/console.error e))))
