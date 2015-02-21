(ns pepa.web.poll
  (:require [pepa.model :as m]
            [pepa.db :as db]
            [pepa.bus :as bus]

            [clojure.data.json :as json]
            [cognitect.transit :as transit]
            
            [immutant.web.async :as async-web]
            [clojure.core.async :as async :refer [go go-loop <!]])
  (:import java.io.ByteArrayOutputStream))

(defn ^:private data->response-fn [content-type]
  (case content-type
    "application/json"
    json/write-str

    "application/transit+json"
    (let [out (ByteArrayOutputStream.)
          writer (transit/writer out :json)]
      (fn [data]
        (.reset out)
        (transit/write writer data)
        (.toString out "UTF-8")))))

(defn ^:private send-fn [content-type]
  (let [data->response (data->response-fn content-type)]
    (fn send! [ch data]
      (async-web/send! ch
                       (-> data (data->response) (str \newline))
                       {:close? true}))))

(defn ^:private send-seqs! [db ch content-type]
  ((send-fn content-type) ch {:seqs (m/sequence-numbers db)}))

(defn lock! [db topic]
  (db/with-transaction [db db]
    (doseq [lock db/advisory-locks]
      (db/advisory-xact-lock! db lock))))

(defn ^:private handle-poll! [config db bus ch seqs content-type]
  (let [send! (send-fn content-type)
        timeout (async/timeout (* 1000 (:timeout config)))
        bus-changes (bus/subscribe-all bus (async/sliding-buffer 1))]
    (go-loop []
      (if-let [changed (m/changed-entities db seqs)]
        (do
          (println "changed" (pr-str changed))
          (send! ch changed))
        ;; NOTE: We have to manually close the channels after a timeout,
        ;; else they stay open for forever & hog memory!
        (let [[val port] (async/alts! [timeout bus-changes])]
          (cond
            ;; Something changed
            (= port bus-changes)
            (let [topic (bus/topic val)]
              (lock! db topic)
              ;; Recur to trigger the then-part of the if.
              (recur))
            ;; Hit a timeout or channel is closed
            (or (= port timeout) (not (async-web/open? ch)))
            (do
              (println "Closing long-polling channel...")
              (when (async-web/open? ch)
                (async-web/close ch)))))))))

(defn ^:private poll-handler* [req]
  (let [method (:request-method req)
        allowed-methods #{:get :post}
        content-type (some #(re-find % (:content-type req))
                           [#"^application/transit\+json"
                            #"^application/json"])
        seqs (:body req)
        
        config (get-in req [:pepa.web.handlers/config :web :poll])
        db (:pepa.web.handlers/db req)
        bus (:pepa.web.handlers/bus req)
        handle-poll! (partial handle-poll! config db bus)]
    (cond
      (not content-type)
      {:status 406}

      (not (contains? allowed-methods method))
      {:status 405}

      (and (seq seqs) (not (m/valid-seqs? seqs)))
      {:status 400}

      true
      (-> req
          (async-web/as-channel
           {:on-open (fn [ch]
                       (if (empty? seqs)
                         (send-seqs! db ch content-type)
                         (handle-poll! ch seqs content-type)))
            :on-error (fn [ch throwable]
                        (println "Caught exception:" throwable)
                        (async-web/close ch))})
          (assoc-in [:headers "content-type"] content-type)))))


(def poll-handler #'poll-handler*)