(ns web-dev-dist-sys.client
  (:require [clojure.string :as str]
            [goog.events :as events]
            [goog.events.KeyCodes :as KeyCodes]
            [taoensso.encore :as encore]
            [taoensso.timbre :as timbre :refer-macros [tracef debugf infof warnf errorf]]
            [taoensso.sente  :as sente  :refer [cb-success?]]
            [reagent.core :as r :refer [atom]]))

;; Dev tooling
(enable-console-print!)
(timbre/debugf "Client is running at %s" (.getTime (js/Date.)))

;; Database, init with some scratch vals.
(defonce app-state (atom {:index 0
                          :count 80}))

;; Channel socket setup
(defn make-chsk-client
  "Creates a socket connection with server at /chsk"
  []
  (sente/make-channel-socket-client! "/chsk"
                                     {:type :auto
                                      :packer :edn}))

;; Defines the client's channel and defines vars for various properties
(defonce state_
  (let [{:keys [chsk ch-recv send-fn state]} (make-chsk-client)]

    (def chsk       chsk)
    (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
    (def chsk-send! send-fn) ; ChannelSocket's send API fn
    (def chsk-state state)   ; Watchable, read-only atom
    ))

;; FIXME Hella janky. Yea...
(defn update-app-state
  [app-state {:keys [index count] :as new-state}]
  (let [index (or index 0)
        count (or count 1)]
    (assoc app-state
           :index index
           :count count)))

;;;; Sente event handlers
(defmulti -event-msg-handler :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (timbre/debugf "%s" event)
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (if (= ?data {:first-open? true})
    (timbre/debugf "Channel socket successfully established!")
    (timbre/debugf "Channel socket state change: %s" ?data)))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (timbre/debugf "Handshake: %s" ?data)))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (let [id (first ?data)
        body (second ?data)]
    (case id
      :srv/sync (swap! app-state update-app-state body)
      :srv/push (do
                  (timbre/debug "%s" body)
                  (swap! app-state update-app-state body)))))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (timbre/debugf "Unhandled event: %s" event))


(defonce router_ (atom nil))

(defn stop-router! []
  (when-let [stop-f @router_] (stop-f)))

(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-client-chsk-router! ch-chsk event-msg-handler)))

(defn send-event
  "Wraps event submissions with logging."
  ([id]
   (do
     (chsk-send! [id])
     (timbre/debugf "[%s] event sent" id)))
  ([id body]
   (do
     (chsk-send! [id body])
     (timbre/debugf "[%s %s] event sent" id body))))

(defn slide-prev
  "Send next event to server. Either commit change locally on correct response, or sync w/ heartbeat."
  []
  (let [{:keys [index]} @app-state
        first-slide? (fn [idx] (zero? idx))]
    (if-not (first-slide? index)
      (send-event :cli/prev))))

(defn slide-next
  "Send next event to server. Either commit change locally on correct response, or sync w/ heartbeat."
  []
  (let [out-of-bounds? (fn [idx c] (>= idx c))
        {:keys [index count]} @app-state]
    (if-not (out-of-bounds? index count)
      (send-event :cli/next))))

;; Input handling
(def input-prev #{40 37})
(def input-next #{38 39})

(defn handle-keyboard
  "Maps keyCode propery to correct handler."
  [e]
  (let [key-code (.-keyCode e)]
    (cond
      (input-prev key-code) (slide-prev)
      (input-next key-code) (slide-next)
      :else :noop)))

(defn listen-keyboard []
  (events/listen js/window "keydown" handle-keyboard))

(defn start! [] (start-router!))

;; FIXME Maybe?
(defonce _start-once
  (do
    (listen-keyboard)
    (start!)))

(defn get-slide []
  (let [index (:index @app-state)]
    (str "slides/web-dev-dist-sys" index ".png")))

(defn main []
  [:div.app-container
   [:div.click-container
    [:div.click-left {:on-click slide-prev}]
    [:div.click-right {:on-click slide-next}]]

   [:div.slide-container
    [:img#slide.noselect {:src (get-slide)}]]])

(r/render-component [main]
                    (. js/document (getElementById "app")))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
  )
