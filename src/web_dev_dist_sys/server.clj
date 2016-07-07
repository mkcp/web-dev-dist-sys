(ns web-dev-dist-sys.server
  "Server for the talk Wev Development Is Distributed Systems Programming."
  (:require
   [clojure.string     :as str]
   [ring.middleware.defaults]
   [compojure.core     :as comp :refer [defroutes GET POST]]
   [compojure.route    :as route]
   [hiccup.core        :as hiccup]
   [clojure.core.async :as async  :refer [<! <!! >! >!! put! chan go go-loop close!]]
   [taoensso.encore    :as encore]
   [taoensso.timbre    :as timbre]
   [taoensso.timbre.appenders.core :as appenders]
   [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
   [taoensso.sente     :as sente]
   [taoensso.sente.server-adapters.http-kit :refer [sente-web-server-adapter]]
   [org.httpkit.server :as http]
   [figwheel-sidecar.repl-api :as fig]))


(timbre/set-config! {:level :info
                     :appenders {:rotor (rotor/rotor-appender {:max-size (* 1024 1024)
                                                               :backlog 10
                                                               :path "./web-dev-dist-sys.log"})}})

(defn get-max-index
  "Counts the number of slides in the folder and decs to 0-index."
  []
  (let [slides-dir (clojure.java.io/file "./resources/public/slides/")
        slides (file-seq slides-dir)
        names (map #(.getName %) slides)
        filtered (filter #(re-find #"^web" %) names)]
    (dec (count filtered))))

(def db (atom {:index 0
               :count (get-max-index)
               :log []}))

(defn append-entry [entry]
  (let [new-log (conj (:log @db) entry)]
    (swap! db assoc :log new-log)))


(defn user-id-fn
  "Assigns user-id from each client's provided client-id."
  [ring-req]
  (:client-id ring-req))

(defn make-channel-socket-server []
  (sente/make-channel-socket-server! sente-web-server-adapter
                                     {:packer :edn
                                      :user-id-fn user-id-fn}))

;; Make channel-socket-server then extract its keys to assign to vars on this ns.
;; Honestly this is pretty janky, you should model this within the server better.
(let [chsk-server (make-channel-socket-server)
      {:keys [ch-recv
              send-fn
              ajax-post-fn
              ajax-get-or-ws-handshake-fn
              connected-uids]} chsk-server]

  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel for the chsk router
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

(defn landing-pg-handler [ring-req]
  (hiccup/html
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport"
            :content "width=device-width, initial-scale=1"}]
    [:link {:href "css/style.css"
            :rel "stylesheet"
            :type "text/css"}]]
   [:body
    [:div#app]
    [:script {:src "js/compiled/web_dev_dist_sys.js"}]]))

(defroutes ring-routes
  (GET  "/"      ring-req (landing-pg-handler            ring-req))
  (GET  "/chsk"  ring-req (ring-ajax-get-or-ws-handshake ring-req))
  (POST "/chsk"  ring-req (ring-ajax-post                ring-req))
  (route/resources "/") ; Static files, notably public/main.js (our cljs target)
  (route/not-found "<h1>Route not found, 404 :C</h1>"))

(def main-ring-handler
  (ring.middleware.defaults/wrap-defaults ring-routes
                                          ring.middleware.defaults/site-defaults))

(defn dec-index [{:keys [index] :as state}]
  (assoc state :index (dec index)))

(defn inc-index [{:keys [index] :as state}]
  (assoc state :index (inc index)))

;; Event handlers
(defmulti -event-msg-handler :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging."
  [{:as ev-msg :keys [event ring-req]}]
  (let [session (:session ring-req)
        uid (:client-id ring-req)]
    (timbre/info (str "UID: " uid " Event:" event))
    (-event-msg-handler ev-msg)))

(defmethod -event-msg-handler :cli/prev [_]
  (swap! db dec-index))

(defmethod -event-msg-handler :cli/next [_]
  (swap! db inc-index))

(defmethod -event-msg-handler :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (timbre/debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

;; Channel socket router
(defonce router_ (atom nil))

(defn stop-router! []
  (when-let [stop-f @router_] (stop-f)))

(defn start-router!
  "Ensures the router isn't running before assigning a new channel socket router to the `router_` atom."
  []
  (stop-router!)
  (reset! router_
          (sente/start-server-chsk-router! ch-chsk event-msg-handler)))

(defn sync-client
  [tick]
  (timbre/debugf "Broadcasting server>user: %s" @connected-uids)
  (let [{:keys [index count]} @db]
    (doseq [uid (:any @connected-uids)]
      (chsk-send! uid [:srv/sync {:index index
                                  :count count
                                  :tick tick
                                  :time (System/currentTimeMillis)}]))))

(defn push-client
  [_ _ _ new-state]
  (timbre/debugf "Pushing state to: %s" @connected-uids)
  (doseq [uid (:any @connected-uids)]
    (chsk-send! uid [:srv/push new-state])))

(defonce heartbeat_ (atom nil))

(defn stop-heartbeat! []
  (let [c @heartbeat_]
    (when c (close! c))))

(defn start-heartbeat! []
  (timbre/info "Starting heartbeat broadcasts.")
  (reset! heartbeat_
          (go-loop [i 0]
            (<! (async/timeout 1000))
            (sync-client i)
            (recur (inc i)))))

(defn stop-watcher!
  "Removes watch from index"
  []
  (timbre/info "Stopping app-state watcher.")
  (remove-watch db :index))

(defn start-watcher!
  "Watches index for changes and broadcasts new state to all clients."
  []
  (timbre/info "Starting app-state watcher.")
  (add-watch db :index push-client))

(defonce web-server_ (atom nil))

(defn stop-web-server! []
  (when-let [m @web-server_]
    (timbre/info "Stopping web server.")
    ((:stop-fn m))))

(defn start-selected-web-server!
  [ring-handler port]
  (timbre/infof "Starting presentation server.")
  (let [stop-fn (http/run-server ring-handler {:port port})]
    {:port    (:local-port (meta stop-fn))
     :stop-fn (fn [] (stop-fn :timeout 100))}))

(defn start-web-server! [& [port]]
  (let [{:keys [stop-fn port] :as server-map}
        (start-selected-web-server! (var main-ring-handler)
                                    (or port 0) ; 0 => auto (any available) port
                                    )
        uri (format "http://localhost:%s/" port)]
    (timbre/infof "Web server is running at `%s`" uri)
    (try
      (.browse (java.awt.Desktop/getDesktop) (java.net.URI. uri))
      (catch java.awt.HeadlessException _))
    (reset! web-server_ server-map)))

(defn stop!
  "Resets the state of all server components."
  []
  (stop-router!)
  (stop-watcher!)
  (stop-heartbeat!)
  (stop-web-server!))


;; TODO Add stop-fns to parts of the system.
(defn start!
  "Starts the router to dispatch for events
  Starts the web server to do work w/ clients.
  And starts the heartbeat to sync index w/ clients every 1000ms."
  []
  (start-router!)
  (start-watcher!)
  (start-heartbeat!)
  (start-web-server! 10002))

(defn cider-stop! []
  (fig/stop-figwheel!)
  (stop!))

(defn cider-start! []
  (fig/start-figwheel!)
  (start!))

(defn -main "For `lein run`, etc." [] (start!))
