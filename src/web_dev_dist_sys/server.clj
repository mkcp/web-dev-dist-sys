(ns web-dev-dist-sys.server
  (:require
   [clojure.string     :as str]
   [ring.middleware.defaults]
   [compojure.core     :as comp :refer [defroutes GET POST]]
   [compojure.route    :as route]
   [hiccup.core        :as hiccup]
   [clojure.core.async :as async  :refer [<! <!! >! >!! put! chan go go-loop]]
   [taoensso.encore    :as encore]
   [taoensso.timbre    :as timbre :refer [tracef debugf infof warnf errorf]]
   [taoensso.timbre.appenders.core :refer [spit-appender]]
   [taoensso.sente     :as sente]
   [org.httpkit.server :as http]
   [taoensso.sente.server-adapters.http-kit :refer [sente-web-server-adapter]]))

;; FIXME
#_(timbre/set-config!
   {:appenders
    {:spit (spit-appender {:fname "//server.log"})}})

(defn get-count
  "FIXME: This should actually ignore hidden files instead of deccing again. .DS_Store
  does not exist in all systems, so this is a bug but hey it's a talk lol."
  []
  (let [slides-dir (clojure.java.io/file "./resources/public/slides/")
        slides (file-seq slides-dir)]
    (dec (dec (count slides)))))

(def app-state (atom {:index 0
                      :count (get-count)}))

(defn start-selected-web-server!
  [ring-handler port]
  (infof "Starting http-kit...")
  (let [stop-fn (http/run-server ring-handler {:port port})]
    {:server  nil ; http-kit doesn't expose this
     :port    (:local-port (meta stop-fn))
     :stop-fn (fn [] (stop-fn :timeout 100))}))

(defn user-id-fn
  "Assigns user-id from each client's provided client-id."
  [ring-req]
  (:client-id ring-req))

(defn make-channel-socket-server []
  (sente/make-channel-socket-server! sente-web-server-adapter
                                     {:packer :edn
                                      :user-id-fn user-id-fn}))

;; Make channel-socket-server then extract its to assign to global vars
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
    [:div#app] ;; Figwheel mount point
    [:script {:src "js/compiled/web_dev_dist_sys.js"}] ;; Compiled application
    ]))

(defroutes ring-routes
  (GET  "/"      ring-req (landing-pg-handler            ring-req))
  (GET  "/chsk"  ring-req (ring-ajax-get-or-ws-handshake ring-req))
  (POST "/chsk"  ring-req (ring-ajax-post                ring-req))
  (route/resources "/") ; Static files, notably public/main.js (our cljs target)
  (route/not-found "<h1>Route not found, 404 :C</h1>"))

(def main-ring-handler
  "**NB**: Sente requires the Ring `wrap-params` + `wrap-keyword-params`
  middleware to work. These are included with
  `ring.middleware.defaults/wrap-defaults` - but you'll need to ensure
  that they're included yourself if you're not using `wrap-defaults`."
  (ring.middleware.defaults/wrap-defaults
    ring-routes ring.middleware.defaults/site-defaults))

;; Event handlers
(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [event ring-req]}]
  (let [session (:session ring-req)
        uid (:uid session)]
    (do
      (debugf "Client: %s sent: %s" uid event)
      (-event-msg-handler ev-msg))))

(defn dec-index
  [{:keys [index] :as state}]
  (assoc state :index (dec index)))

(defn inc-index
  [{:keys [index] :as state}]
  (assoc state :index (inc index)))

(defmethod -event-msg-handler
  :cli/prev
  [_]
  (swap! app-state dec-index))

(defmethod -event-msg-handler
  :cli/next
  [_]
  (swap! app-state inc-index))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (debugf "Unhandled event: %s" event)
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
  (debugf "Broadcasting server>user: %s" @connected-uids)
  (let [{:keys [index count]} @app-state]
    (doseq [uid (:any @connected-uids)]
      (chsk-send! uid [:srv/sync {:index index
                                  :count count
                                  :tick tick}]))))

(defn push-client
  [_ _ _ new-state]
  (debugf "Pushing state to: %s" @connected-uids)
  (doseq [uid (:any @connected-uids)]
    (chsk-send! uid [:srv/push new-state])))

(defonce heartbeat_ (atom nil))

#_(defn stop-heartbeat! [])

;; FIXME
(defn start-heartbeat!
  "Every second, sends out of "
  []
  (go-loop [i 0]
    (<! (async/timeout 1000))
    (sync-client i)
    (recur (inc i))))

(defn stop-watcher!
  "Removes watch from index"
  []
  (remove-watch app-state :index))

(defn start-watcher!
  "Watches index for changes and broadcasts new state to all clients."
  []
  (add-watch app-state :index push-client))

;; Web Server
(defonce web-server_ (atom nil)) ; {:server _ :port _ :stop-fn (fn [])}

(defn stop-web-server! []
  (when-let [m @web-server_]
    ((:stop-fn m))))

(defn start-web-server! [& [port]]
  (stop-web-server!)
  (let [{:keys [stop-fn port] :as server-map}
        (start-selected-web-server! (var main-ring-handler)
                                    (or port 0) ; 0 => auto (any available) port
                                    )
        uri (format "http://localhost:%s/" port)]
    (infof "Web server is running at `%s`" uri)
    (try
      (.browse (java.awt.Desktop/getDesktop) (java.net.URI. uri))
      (catch java.awt.HeadlessException _))
    (reset! web-server_ server-map)))

(defn stop!
  "FIXME: Doesn't stop the heartbeat! Broken!! :D"
  []
  (stop-router!)
  (stop-watcher!)
  (stop-web-server!))

(defn start!
  "Starts the router to dispatch for events
  Starts the web server to do work w/ clients.
  And starts the broadcaster to sync index w/ clients."
  []
  (do
    (start-router!)
    (start-watcher!)
    (start-heartbeat!)
    (start-web-server! 10002)))

(defn -main "For `lein run`, etc." [] (start!))
