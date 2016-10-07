(ns app.renderer
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [app.macros :refer [<!? take!? go-let go-try]])
  (:require [reagent.core :as r]
            [cljs.core.async :refer [<! >! put! take! chan close! timeout]]
            [clojure.string :as string]
            [app.wallpaper-generator :as w]
            [app.json :as json]
            [app.io :as io]
            [app.utils :as u]))

(def electron    (js/require "electron"))
(def ipcRenderer (.-ipcRenderer electron))
(def remote      (.-remote electron))
(def child-proc  (js/require "child_process"))

(def current-window (.getCurrentWindow remote))

(defn title-bar []
  [:div#title-bar
   [:div.toolbar
    [:button.mini-size {:on-click #(.hide current-window)}]
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

(def wallpaper-set-commands {"GNOME" "gsettings set org.gnome.desktop.background picture-uri \"file://%PIC%\""
                             "KED" "" ; TODO
                             "XFCE" "xfconf-query --channel xfce4-desktop --property /backdrop/screen0/monitor0/image-path --set \"%PIC%\""
                             "Cinnamon" "gsettings set org.cinnamon.desktop.background picture-uri \"file://%PIC%\""
                             "DDE" "gsettings set com.deepin.wrap.gnome.desktop.background picture-uri \"file://%PIC%\""})

(def state (r/atom {:working? false
                    :wallpapers []
                    :color-editor-target nil}))

(def config (let [{:keys [width height]} (get-largest-screen-size)]
              (r/atom {:save-path (str (u/get-user-home) "/.cache/bismuth/")
                       :max-wp-num 50
                       :width width
                       :height height
                       :colors [{:background ["#3a3d34"]
                                 :foreground ["#e7e5d8" "#f3f4f4" "#3a3d34" "#849333"]
                                 :disabled false}
                                {:background ["#474747"]
                                 :foreground ["#474747" "#3d3d3d" "#e84c3d" "#595959"]
                                 :disabled false}
                                {:background ["#474747"]
                                 :foreground ["#474747" "#3d3d3d" "#2962FF" "#595959"]
                                 :disabled false}]
                       :auto-wallpaper {:enabled false
                                        :minute 10
                                        :command (get wallpaper-set-commands "GNOME")}})))

(def config-dir (str (u/get-user-home) "/.config/bismuth/"))
(def config-file (str config-dir "config.json"))

(defn log-error [msg]
  (js/console.error msg)
  (js/alert msg))

(defn save-config []
  (io/write-file config-file
                 (json/write-str @config :space 4)))

(defn save-wallpaper [wallpaper-base64]
  (let [data (subs wallpaper-base64 22)
        file-name (str "wp" (.getTime (js/Date.)) ".png")
        file-path (str (:save-path @config) file-name)
        result-chan (chan)]
    (go-try
     (when-not (<!? (io/file-exist? (:save-path @config)))
       (<!? (io/mkdir (:save-path @config))))
     (<!? (io/write-file file-path data :encoding "base64"))
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
         (<!? (io/unlink file)))
       (close! error-chan)
       (catch :default e
         (put! error-chan {:error e})))
      (close! error-chan))
    error-chan))

(defn get-available-colors []
  (filter #(not (:disabled %)) (:colors @config)))

(defn set-wallpaper [path]
  (let [cmds (-> (string/replace (:command (:auto-wallpaper @config))
                                 #"%PIC%" path)
                 (string/split #"\n"))]
    (doseq [cmd cmds]
      (.exec child-proc cmd
             (fn [error stdout stderr]
               (when error (log-error error))
               (when-not (empty? stdout) (js/console.log stdout))
               (when-not (empty? stderr) (js/console.log stderr)))))))

(defn new-wallpaper []
  (go-try
   (swap! state assoc :working? true)
   (let [result (<! (w/generate w/line (get-available-colors)
                                (:width @config) (:height @config)))
         file-path (<!? (save-wallpaper result))]
     (swap! state update-in [:wallpapers] #(cons file-path %))
     (swap! state assoc :working? false)
     (<!? (delete-old-wallpaper))
     {:result file-path})
   (catch :default e
     {:error e})))

(defn preview []
  [:div#preview
   [:div#wallpaper-history>div
    (doall
     (for [src (:wallpapers @state)]
       ^{:key (hash src)}
       [:img {:src src :on-click #(set-wallpaper src)}]))]
   [:div.toolbar
    [:button#new-wallpaper.btn
     {:on-click #(take!? (new-wallpaper)
                         (fn [_ err] (when err (log-error err))))
      :disabled (:working? @state)}
     [:div "新壁纸"]]]])

(defn generator-setting []
  [:div#generator-setting
   [:p "生成器选择:"]
   [:label.generator-preview>div
    {:style {:background-image "url(images/background.png)"}}
    [:div.footer>div.right "line" [:input {:type "checkbox" :checked true
                                           :on-change #()}]]]
   [:p "配色选择:"]
   (into
    [:div.color-group-list]
    (map-indexed
     (fn [i color-group]
       ^{:key i}
       [:label.color-group-preview>div
        [:div.background
         (for [background (:background color-group)]
           ^{:key background}
           [:div.color {:style {:background-color background}}])]
        [:div.foreground
         (for [foreground (:foreground color-group)]
           ^{:key foreground}
           [:div.color {:style {:background-color foreground}}])]
        [:input {:type "checkbox" :checked (not (:disabled color-group))
                 :on-change #(let [checked (-> % .-target .-checked)]
                               ;; 至少保留一组可用配色
                               (when (or checked (> (count (get-available-colors)) 1))
                                 (swap! config assoc-in [:colors i :disabled]
                                        (not checked))
                                 (save-config)))}]])
     (:colors @config)))
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

(defn dissoc-vec [coll pos]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

(defn color-setting []
  [:div#color-setting>div
   {:style {:transform (when (:color-editor-target @state) "translateY(-100%)")}}
   [:div.list
    (map-indexed
     (fn [id color-group]
       ^{:key id}
       [:div.color-group
        {:on-click #(swap! state assoc :color-editor-target id)}
        [:div.background
         (for [background (:background color-group)]
           ^{:key background}
           [:div.color {:style {:background-color background}}])]
        [:div.foreground
         (for [foreground (:foreground color-group)]
           ^{:key foreground}
           [:div.color {:style {:background-color foreground}}])]])
     (:colors @config))
    [:div.color-group.btn {:on-click #(do (swap! config update :colors
                                                 conj {:background ["#ffffff"]
                                                       :foreground ["#ffffff"]
                                                       :disabled false})
                                          (swap! state assoc :color-editor-target
                                                 (dec (count (:colors @config)))))}
     [:a "+"]]]
   (let [index (:color-editor-target @state)
         color-group (get (:colors @config) index)]
     [:div.editor>div
      [:div.box
       [:p "背景色:"]
       (map-indexed
        (fn [i background]
          ^{:key i}
          [:div.color {:style {:background-color background}}
           [:input {:on-change #(let [color (-> % .-target .-value)]
                                  (if-not (empty? color)
                                    (swap! config assoc-in [:colors index :background i]
                                           color)
                                    (when (> (count (get-in @config [:colors index :background])) 1)
                                      (swap! config update-in [:colors index :background]
                                             dissoc-vec i)))
                                  (save-config))
                    :value background}]])
        (:background color-group))
       [:div.color.btn {:on-click #(swap! config update-in [:colors index :background]
                                          conj "#ffffff")}
        [:a "+"]]
       [:p "前景色:"]
       (map-indexed
        (fn [i foreground]
          ^{:key i}
          [:div.color {:style {:background-color foreground}}
           [:input {:on-change #(let [color (-> % .-target .-value)]
                                  (if-not (empty? color)
                                    (swap! config assoc-in [:colors index :foreground i]
                                           color)
                                    (when (> (count (get-in @config [:colors index :foreground])) 1)
                                      (swap! config update-in [:colors index :foreground]
                                             dissoc-vec i)))
                                  (save-config))
                    :value foreground}]])
        (:foreground color-group))
       [:div.color.btn {:on-click #(swap! config update-in [:colors index :foreground]
                                          conj "#ffffff")}
        [:a "+"]]]
      [:div.buttons
       [:button.back.btn {:on-click #(swap! state assoc :color-editor-target nil)} [:a "返回"]]
       [:button.delete.btn {:on-click #(do (swap! state assoc :color-editor-target nil)
                                           (swap! config update :colors dissoc-vec index))
                            :disabled (<= (count (:colors @config)) 1)} [:a "删除"]]]])])

(defn wallpaper-update-loop []
  (go-loop []
    (<! (timeout (* (:minute (:auto-wallpaper @config)) 1000 60)))
    (when (:enabled (:auto-wallpaper @config))
      (take!? (new-wallpaper)
              (fn [wallpaper-path error]
                (if-not error
                  (set-wallpaper wallpaper-path)
                  (log-error error)))))
    (recur)))

(defn auto-wallpaper []
  [:div#auto-wallpaper
   [:p "启用:" [:input {:type "checkbox" :checked (:enabled (:auto-wallpaper @config))
                        :on-change #(do (swap! config assoc-in [:auto-wallpaper :enabled]
                                               (-> % .-target .-checked))
                                        (save-config))}]]
   [:p "刷新间隔:"
    [:label.comment [:input {:type "number" :style {:width "3em"}
                             :value (:minute (:auto-wallpaper @config))
                             :on-change #(do (swap! config assoc-in [:auto-wallpaper :minute]
                                                    (-> % .-target .-value u/parse-int))
                                             (save-config))}]
     [:a "分钟"]]]
   [:p "壁纸设置命令:"
    (for [[de cmd] wallpaper-set-commands]
      ^{:key de}
      [:button.btn {:on-click #(do (swap! config assoc-in [:auto-wallpaper :command] cmd)
                                   (save-config))} de])]
   [:textarea.command {:value (:command (:auto-wallpaper @config))
                       :on-change #(do (swap! config assoc-in [:auto-wallpaper :command]
                                              (-> % .-target .-value))
                                       (save-config))}]])

(defn body []
  [:div [title-bar]
   [contents
    "壁纸设置" [preview]
    "生成器设置" [generator-setting]
    "配色设置" [color-setting]
    "自动刷新" [auto-wallpaper]
    "关于" [:div "..."]]])

(defn init []
  (enable-console-print!)
  (go-try
   (if (<!? (io/file-exist? config-file))
     (let [data (<!? (io/read-file config-file))
           cfg (json/read-str data :key-fn keyword)]
       (swap! config into cfg))
     (do (<!? (io/mkdir config-dir))
         (<!? (save-config))))
   (when (<!? (io/file-exist? (:save-path @config)))
     (swap! state assoc :wallpapers
            (->> (<!? (io/read-dir (:save-path @config)))
                 reverse
                 (map #(str (:save-path @config) %)))))
   (<!? (delete-old-wallpaper))
   (r/render body (u/query-selector "#app"))
   (wallpaper-update-loop)
   (catch :default e
     (log-error e)
     (.close current-window))))
