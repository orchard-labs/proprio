(ns proprio.core
  (:require [clojure.tools.logging :as log]
            [morph.core :as morph]
            [cheshire.core :as json])
  (:import [com.amazonaws
            PredefinedClientConfigurations]
           [com.amazonaws.auth
            AWSStaticCredentialsProvider
            AWSCredentialsProvider
            BasicAWSCredentials
            DefaultAWSCredentialsProviderChain]
           [com.amazonaws.auth.profile
            ProfileCredentialsProvider]
           [com.amazonaws.services.kinesis
            AmazonKinesis
            AmazonKinesisClientBuilder]
           [com.amazonaws.services.kinesis.clientlibrary.lib.worker
            KinesisClientLibConfiguration
            InitialPositionInStream
            Worker$Builder]
           [com.amazonaws.services.kinesis.clientlibrary.types
            InitializationInput
            ProcessRecordsInput
            ShutdownInput]
           [com.amazonaws.services.kinesis.clientlibrary.exceptions
            ThrottlingException
            ShutdownException
            InvalidStateException]
           [com.amazonaws.services.kinesis.metrics.interfaces
            MetricsLevel]
           [com.amazonaws.services.kinesis.clientlibrary.interfaces.v2
            IRecordProcessor
            IRecordProcessorFactory]
           [com.amazonaws.services.kinesis.clientlibrary.interfaces
            IRecordProcessorCheckpointer]
           [com.amazonaws.services.kinesis.model
            ListStreamsRequest
            ListShardsRequest
            PutRecordRequest
            PutRecordsRequest
            PutRecordsRequestEntry
            Record]
           [com.amazonaws.client.builder
            AwsClientBuilder$EndpointConfiguration]
           [java.nio ByteBuffer]
           [java.util UUID]))

(def config-error-message
  "config must be a configuration map or an implementation of AmazonKinesis")

(defn credential-provider
  [{:keys [access-key secret-key profile]}]
  (cond
    (and (not-empty access-key) (not-empty secret-key))
    (AWSStaticCredentialsProvider. (BasicAWSCredentials. access-key secret-key))

    (not-empty profile)
    (ProfileCredentialsProvider. profile)

    :else
    (DefaultAWSCredentialsProviderChain.)))

;; Use SDK for outbound

(defn aws-sdk-client-configuration
  [opts]
  ;; Tune the non-worker client here
  (doto (PredefinedClientConfigurations/defaultConfig)
    (.setUseReaper true)))

(defn make-client
  ([{:keys [region endpoint] :as opts}]
   {:pre [(some? region) (some? endpoint)]}
   (make-client (credential-provider opts) opts))
  ([^AWSCredentialsProvider provider {:keys [region endpoint] :as opts}]
   (try
     (.getCredentials provider)
     (catch Throwable e
       (throw (ex-info "Couldn't load credentials" {:type ::auth-error} e))))

   (let [endpoint-config (AwsClientBuilder$EndpointConfiguration. endpoint region)]
     (.. (AmazonKinesisClientBuilder/standard)
         (withCredentials provider)
         (withClientConfiguration (aws-sdk-client-configuration opts))
         (withEndpointConfiguration endpoint-config)
         (build)))))

(defn list-streams
  [config]
  (cond
    (instance? AmazonKinesis config)
    (.. config
        (listStreams (ListStreamsRequest.))
        (getStreamNames))

    (map? config)
    (recur (make-client config))

    :else
    (throw (ex-info config-error-message {:config config}))))

(defn list-shards
  [config stream]
  (cond
    (instance? AmazonKinesis config)
    (let [request (.. (ListShardsRequest.)
                      (withStreamName stream))]
      (.. config
          (listShards request)
          (getShards)))

    (map? config)
    (recur (make-client config) stream)

    :else
    (throw (ex-info config-error-message {:config config :stream stream}))))

(defn create-stream
  [config stream shard-count]
  (cond
    (instance? AmazonKinesis config)
    (.createStream config stream (.intValue shard-count))

    (map? config)
    (recur (make-client config) stream shard-count)

    :else
    (throw (ex-info config-error-message {:config config :stream stream}))))

(defn delete-stream
  [config stream]
  (cond
    (instance? AmazonKinesis config)
    (.deleteStream config (name stream))

    (map? config)
    (recur (make-client config) stream)

    :else
    (throw (ex-info config-error-message {:config config :stream stream}))))

(defn describe-stream
  [config stream]
  (cond
    (instance? AmazonKinesis config)
    (.describeStream config (name stream))

    (map? config)
    (recur (make-client config) stream)

    :else
    (throw (ex-info config-error-message {:config config :stream stream}))))

(defn put-records
  "Put a collection of records onto a stream by name."
  ([config stream partition-key records tries]
   {:pre [(coll? records) (not (map? records))]}
   (cond
     (instance? AmazonKinesis config)
     (if (> tries 0)
       (let [data    (->> records
                          (map json/generate-string)
                          (map #(.getBytes %))
                          (map #(ByteBuffer/wrap %))
                          (map #(.. (PutRecordsRequestEntry.)
                                    (withPartitionKey partition-key)
                                    (withData %))))
             request (.. (PutRecordsRequest.)
                         (withStreamName (name stream))
                         (withRecords data))
             results (.. config
                         (putRecords request)
                         (getRecords))]
         (when-let [failed (->> results
                                (map #(.getErrorCode %))
                                (interleave data)
                                (partition 2)
                                (remove #(nil? (second %)))
                                (map first)
                                (seq))]
           ;; retry any failed records
           (recur config stream partition-key failed (dec tries))))
       (throw (ex-info (format "Failed to send %d records to kinesis stream [%s]"
                               (count records) (name stream))
                       {:records records :stream stream})))

     (map? config)
     (recur (make-client config) stream partition-key records tries)

     :else
     (throw (ex-info config-error-message {:config config :stream stream}))))
  ([config stream partition-key records]
   (put-records config stream partition-key records 10)))

(defn put-record
  "Put a single record onto a stream by name."
  [config stream partition-key record]
  (cond
    (instance? AmazonKinesis config)
    (let [data    (-> record
                      (json/generate-string)
                      (.getBytes)
                      (ByteBuffer/wrap))
          request (.. (PutRecordRequest.)
                      (withStreamName (name stream))
                      (withPartitionKey partition-key)
                      (withData data))]
      (.putRecord config request))

    (map? config)
    (recur (make-client config) stream partition-key record)

    :else
    (throw (ex-info config-error-message {:config config :stream stream}))))

;; Use KCL for consumer worker

(defn ^KinesisClientLibConfiguration kcl-client-config
  [{:keys [app-name endpoint dynamo-url region metrics-level]
    :or {metrics-level "NONE"}
    :as opts}
   stream-name]
  (let [worker-id (str app-name "-" (UUID/randomUUID))
        provider (credential-provider opts)]
    (.. (KinesisClientLibConfiguration. app-name (name stream-name) provider worker-id)
        (withKinesisEndpoint endpoint)
        (withDynamoDBEndpoint dynamo-url)
        (withInitialPositionInStream InitialPositionInStream/TRIM_HORIZON)
        (withRegionName region)
        (withMetricsLevel (MetricsLevel/fromName metrics-level)))))

(defn- maybe-checkpoint
  "Run a checkpoint if more time has passed than `interval` seconds
  since `last-checkpoint`."
  [last-checkpoint
   ^IRecordProcessorCheckpointer checkpointer
   {:keys [interval backoff tries]
    :or   {interval 60
           backoff  3
           tries    10}
    :as   opts}]
  (let [now      (System/currentTimeMillis)
        interval (* 1000 interval)]
    (if (> now (+ last-checkpoint interval))
      ;; Can't recur from inside a catch block, so jump through some hoops
      (let [res (try
                  (.checkpoint checkpointer)
                  :success
                  ;; during shutdown failure is a no-op
                  (catch ShutdownException _ :success)
                  (catch Throwable ex ex))]
        (cond
          ;; error was thrown, but there are remaining retries
          (and (not= :success res) (< 0 tries))
          (do (Thread/sleep (* 1000 backoff))
              (recur last-checkpoint checkpointer (update opts :tries dec)))

          ;; no more retries, and there was an error
          (instance? Throwable res)
          (throw res)

          ;; otherwise we're good
          :else now)))))

(defn ^IRecordProcessorFactory processor-factory
  ([handler decoder-fn]
   (processor-factory handler decoder-fn {}))
  ([handler decoder-fn opts]
   (reify IRecordProcessorFactory
     (createProcessor [_]
       (let [checkpoint (agent 0 :error-mode :continue
                               :error-handler
                               #(log/errorf %2 "Checkpoint agent %s threw an error" %1))]
         (reify IRecordProcessor
           (^void initialize [_ ^InitializationInput input]
            (log/info "Initializing Kinesis record processor"))
           (^void processRecords [_ ^ProcessRecordsInput input]
            (let [records (.getRecords input)]
              (doseq [^Record record records
                      :let [record-map {:partition-key (.getPartitionKey record)
                                        :sequence-number (.getSequenceNumber record)
                                        :data (decoder-fn (.getData record))}]]
                (handler record-map))
              ;; Checkpoint asynchronously
              (send-off checkpoint maybe-checkpoint (.getCheckpointer input) opts)))
           (^void shutdown [_ ^ShutdownInput input]
            (log/infof "Shutting down Kinesis record processor: %s"
                       (.getShutdownReason input)))))))))

(defn decode-json
  [^ByteBuffer byte-buffer]
  (-> (.array byte-buffer)
      (String.)
      (json/parse-string true)
      (morph/keys->kebab-case)))

(defn stream-worker
  "Returns a stop function"
  ([opts stream-name handler]
   (stream-worker opts stream-name handler decode-json))
  ([opts stream-name handler decoder-fn]
   (let [cfg (kcl-client-config opts stream-name)
         factory (processor-factory handler decoder-fn)
         worker (.. (Worker$Builder.)
                    (recordProcessorFactory factory)
                    (config cfg)
                    (build))]
     (future (.run worker))
     ;; return a stop fn which blocks until shutdown is complete
     #(.. worker startGracefulShutdown get))))
