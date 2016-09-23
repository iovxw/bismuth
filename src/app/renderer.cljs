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

(def state (r/atom {:working? false
                    :wallpapers []}))

(def config (r/atom {:save-path (str (get-user-home) "/.cache/bismuth/")
                     :max-wp-num 100
                     :width 1920
                     :height 1080}))

(defn file-exist? [path]
  (let [result-chan (chan)]
    (.stat fs (:save-path @config)
           #(if %
              (if (= (.-code %) "ENOENT")
                (put! result-chan {:result false})
                (put! result-chan {:error %}))
              (put! result-chan {:result true})))
    result-chan))

(defn save-wallpaper [wallpaper-base64]
  (let [data (subs wallpaper-base64 22)
        file-name (str "wp" (.getTime (js/Date.)) ".png")
        file-path (str (:save-path @config) file-name)
        result-chan (chan)]
    (go-try
     (when-not (<!-with-err (file-exist? (:save-path @config)))
       (.mkdir fs (:save-path @config) #(when % (put! result-chan {:error %}))))
     (.writeFile fs file-path data "base64"
                 #(if %
                    (put! result-chan {:error %})
                    (put! result-chan {:result file-path})))
     (catch :default e
       (put! result-chan {:error e})))
    result-chan))

(defn preview []
  [:div#preview
   [:div#wallpaper-history>div
    (for [src (:wallpapers @state)]
      ^{:key (hash src)}
      [:img {:src src}])]
   [:div.toolbar
    [:button#new-wallpaper
     {:on-click (fn [e]
                  (swap! state assoc :working? true)
                  (go-try
                   (let [result (<! (w/generate w/line (:width @config) (:height @config)))
                         file-path (<!-with-err (save-wallpaper result))]
                     (swap! state assoc :working? false)
                     (swap! state update-in [:wallpapers] #(cons (str "file://" file-path) %)))
                   (catch :default e
                      (js/console.error e))))
      :disabled (:working? @state)}
     [:div "新壁纸"]]]])

(defn body []
  [:div [title-bar]
   [contents
    "壁纸设置" [preview]
    "生成器设置" [:div
                  [:p "生成器选择"]
                  [:label "line" [:input {:type "checkbox"}]]]
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
  (r/render body (query-selector "#app")))
