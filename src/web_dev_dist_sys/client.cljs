(ns web-dev-dist-sys.client
  (:require [clojure.string :as str]
            [taoensso.encore :as encore]
            [taoensso.timbre :as timbre :refer-macros [tracef debugf infof warnf errorf]]
            [taoensso.sente  :as sente  :refer [cb-success?]]
            [reagent.core :as r :refer [atom]]))

(enable-console-print!)

(timbre/debug "Client is running at %s" (.getTime (js/Date.)))

(defonce app-state (atom {:text "Hello world!"
                          :index 0}))

(defn index [] (:index @app-state))

(defn inc-index []
  (swap! app-state #(inc (:index %))))

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

;;;; Sente event handlers
(defmulti -event-msg-handler :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (timbre/debug "Unhandled event: %s" event))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (if (= ?data {:first-open? true})
    (timbre/debug "Channel socket successfully established!")
    (timbre/debug "Channel socket state change: %s" ?data)))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (timbre/debug "Push event from server: %s" ?data))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (timbre/debug "Handshake: %s" ?data)))

(defonce router_ (atom nil))

(defn stop-router! []
  (when-let [stop-f @router_] (stop-f)))

(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-client-chsk-router!
           ch-chsk event-msg-handler)))

(defn login []
  (let [user-id "kit"]
    (do
      (timbre/debug "Logging in with user-id %s" user-id)

            ;;; Use any login procedure you'd like. Here we'll trigger an Ajax
            ;;; POST request that resets our server-side session. Then we ask
            ;;; our channel socket to reconnect, thereby picking up the new
            ;;; session.
      (sente/ajax-lite "/login"
                       {:method :post
                        :headers {:X-CSRF-Token (:csrf-token @chsk-state)}
                        :params  {:user-id (str user-id)}}

                       (fn [ajax-resp]
                         (timbre/debug "Ajax login response: %s" ajax-resp)
                         (do
                           (timbre/debug "Login successful")
                           (sente/chsk-reconnect! chsk)))))))

(defn slide-next
  "Send next event to server. Either commit change locally on correct response, or sync w/ heartbeat."
  []
  (do
    (timbre/debug "Next")
    (chsk-send! [:cli/next {:event :next}])))

(defn sync
  "Get state from server, if out of date change."
  []
  (chsk-send! [:cli/sync (:index @app-state)]))

(defn start! [] (start-router!))

(defonce _start-once (start!))

(defn hello-world []
  [:div
   [:h1 (str "App State: " )]
   [:h3 (pr-str @app-state)]
   #_[:button {:on-click prev} "Prev"]
   [:button {:on-click slide-next} "Next"]
   [:img {:src "slides/title-card.png"}]
   ])

(r/render-component [hello-world]
                    (. js/document (getElementById "app")))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
  )
