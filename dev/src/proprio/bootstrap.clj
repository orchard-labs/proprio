(ns proprio.bootstrap
  (:require [proprio.core :as proprio]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]])
  (:import [com.amazonaws.services.kinesis AmazonKinesis]))

(def test-config
  {:region        "us-east-1"
   :endpoint      (env :kinesis-endpoint "http://localhost:4567")
   :dynamo-url    (env :dynamo-url "http://localhost:7777")
   :app-name      "proprio-test"
   :metrics-level "NONE"})

(def test-stream "proprio-test-stream")

(defn init-stream
  "This function is here for use as a leiningen injection in the context
  of docker-compose setup."
  ([config stream]
   (cond
     (instance? AmazonKinesis config)
     (when-not (-> config (proprio/list-streams) (set) (contains? (name stream)))
       (proprio/create-stream config (name stream) 1))

     (map? config)
     (recur (proprio/make-client config) stream)

     :else
     (throw (ex-info proprio/config-error-message {:config config :stream stream}))))
  ([]
   (init-stream test-config test-stream)))
