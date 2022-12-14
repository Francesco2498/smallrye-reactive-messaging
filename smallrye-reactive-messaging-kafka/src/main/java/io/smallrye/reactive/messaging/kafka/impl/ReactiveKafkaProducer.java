package io.smallrye.reactive.messaging.kafka.impl;

import static io.smallrye.reactive.messaging.kafka.i18n.KafkaExceptions.ex;
import static io.smallrye.reactive.messaging.kafka.i18n.KafkaLogging.log;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import jakarta.enterprise.inject.Instance;

import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serializer;

import io.smallrye.common.annotation.CheckReturnValue;
import io.smallrye.common.annotation.Identifier;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.KafkaConnectorOutgoingConfiguration;
import io.smallrye.reactive.messaging.kafka.SerializationFailureHandler;
import io.smallrye.reactive.messaging.kafka.fault.SerializerWrapper;
import io.vertx.core.Context;

public class ReactiveKafkaProducer<K, V> implements io.smallrye.reactive.messaging.kafka.KafkaProducer<K, V> {

    private final AtomicBoolean closed = new AtomicBoolean(true);
    private final String clientId;
    private final Uni<Producer<K, V>> producerUni;
    private final AtomicReference<Producer<K, V>> producerRef = new AtomicReference<>();

    private final ExecutorService kafkaWorker;

    private final Map<String, Object> kafkaConfiguration;
    private final String channel;
    private final int closetimeout;

    private Consumer<Throwable> reportFailure;

    public ReactiveKafkaProducer(KafkaConnectorOutgoingConfiguration config,
            Instance<SerializationFailureHandler<?>> serializationFailureHandlers,
            Consumer<Throwable> reportFailure,
            BiConsumer<Producer<?, ?>, Map<String, Object>> onProducerCreated) {
        this(getKafkaProducerConfiguration(config), config.getChannel(), config.getCloseTimeout(),
                config.getLazyClient(),
                createSerializationFailureHandler(config.getChannel(),
                        config.getKeySerializationFailureHandler().orElse(null),
                        serializationFailureHandlers),
                createSerializationFailureHandler(config.getChannel(),
                        config.getValueSerializationFailureHandler().orElse(null),
                        serializationFailureHandlers),
                onProducerCreated);
        this.reportFailure = reportFailure;
    }

    public String getClientId() {
        return clientId;
    }

    public ReactiveKafkaProducer(Map<String, Object> kafkaConfiguration, String channel, int closeTimeout,
            boolean lazyClient,
            SerializationFailureHandler<K> keySerializationFailureHandler,
            SerializationFailureHandler<V> valueSerializationFailureHandler,
            BiConsumer<Producer<?, ?>, Map<String, Object>> onProducerCreated) {
        this.kafkaConfiguration = kafkaConfiguration;
        this.channel = channel;
        this.closetimeout = closeTimeout;
        this.clientId = kafkaConfiguration.get(ProducerConfig.CLIENT_ID_CONFIG).toString();

        String keySerializerCN = (String) kafkaConfiguration.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        String valueSerializerCN = (String) kafkaConfiguration.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);

        if (valueSerializerCN == null) {
            throw ex.missingValueSerializer(this.channel, this.channel);
        }

        Serializer<K> keySerializer = new SerializerWrapper<>(keySerializerCN, true,
                keySerializationFailureHandler);
        Serializer<V> valueSerializer = new SerializerWrapper<>(valueSerializerCN, false,
                valueSerializationFailureHandler);

        // Configure the underlying serializers
        keySerializer.configure(kafkaConfiguration, true);
        valueSerializer.configure(kafkaConfiguration, false);

        kafkaWorker = Executors.newSingleThreadExecutor(KafkaSendingThread::new);
        producerUni = Uni.createFrom().item(() -> producerRef.updateAndGet(p -> {
            if (p != null) {
                return p;
            } else {
                Producer<K, V> producer = new KafkaProducer<>(kafkaConfiguration, keySerializer, valueSerializer);
                if (kafkaConfiguration.containsKey(ProducerConfig.TRANSACTIONAL_ID_CONFIG)) {
                    producer.initTransactions();
                }
                onProducerCreated.accept(producer, kafkaConfiguration);
                closed.set(false);
                return producer;
            }
        })).onFailure().invoke(throwable -> {
            log.unableToInitializeProducer(channel, throwable);
            if (reportFailure != null) {
                reportFailure.accept(throwable);
            }
        }).memoize().until(closed::get)
                .runSubscriptionOn(kafkaWorker);
        if (!lazyClient) {
            producerUni.await().indefinitely();
        }
    }

    private Uni<Producer<K, V>> withProducerOnSendingThread() {
        return producerUni;
    }

    @Override
    @CheckReturnValue
    public <T> Uni<T> runOnSendingThread(Function<Producer<K, V>, T> action) {
        return withProducerOnSendingThread()
                .map(action);
    }

    @Override
    @CheckReturnValue
    public Uni<Void> runOnSendingThread(java.util.function.Consumer<Producer<K, V>> action) {
        return withProducerOnSendingThread()
                .invoke(action)
                .replaceWithVoid();
    }

    @Override
    @CheckReturnValue
    public Uni<RecordMetadata> send(ProducerRecord<K, V> record) {
        return withProducerOnSendingThread()
                .chain(c -> Uni.createFrom().emitter(em -> c.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        if (record.topic() != null) {
                            log.unableToWrite(this.channel, record.topic(), exception);
                        } else {
                            log.unableToWrite(this.channel, exception);
                        }
                        em.fail(exception);
                    } else {
                        em.complete(metadata);
                    }
                })));
    }

    @Override
    @CheckReturnValue
    public Uni<Void> flush() {
        return runOnSendingThread((Consumer<Producer<K, V>>) Producer::flush);
    }

    @Override
    @CheckReturnValue
    public Uni<List<PartitionInfo>> partitionsFor(String topic) {
        return runOnSendingThread(producer -> {
            return producer.partitionsFor(topic);
        });
    }

    @Override
    @CheckReturnValue
    public Uni<Void> initTransactions() {
        return runOnSendingThread((Consumer<Producer<K, V>>) Producer::initTransactions);
    }

    @Override
    @CheckReturnValue
    public Uni<Void> beginTransaction() {
        return runOnSendingThread((Consumer<Producer<K, V>>) Producer::beginTransaction);
    }

    @Override
    @CheckReturnValue
    public Uni<Void> commitTransaction() {
        return runOnSendingThread((Consumer<Producer<K, V>>) Producer::commitTransaction);
    }

    @Override
    @CheckReturnValue
    public Uni<Void> abortTransaction() {
        return runOnSendingThread((Consumer<Producer<K, V>>) Producer::abortTransaction)
                .onItem().invoke(() -> log.transactionAborted(clientId, channel));
    }

    @Override
    @CheckReturnValue
    public Uni<Void> sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets,
            ConsumerGroupMetadata groupMetadata) {
        return runOnSendingThread(producer -> {
            producer.sendOffsetsToTransaction(offsets, groupMetadata);
        });
    }

    @SuppressWarnings({ "unchecked" })
    private static <T> SerializationFailureHandler<T> createSerializationFailureHandler(String channelName,
            String failureHandlerName, Instance<SerializationFailureHandler<?>> deserializationFailureHandlers) {
        if (failureHandlerName == null) {
            return null;
        }

        Instance<SerializationFailureHandler<?>> matching = deserializationFailureHandlers
                .select(Identifier.Literal.of(failureHandlerName));

        if (matching.isUnsatisfied()) {
            throw ex.unableToFindSerializationFailureHandler(failureHandlerName, channelName);
        } else if (matching.stream().count() > 1) {
            throw ex.unableToFindSerializationFailureHandler(failureHandlerName, channelName,
                    (int) matching.stream().count());
        } else if (matching.stream().count() == 1) {
            return (SerializationFailureHandler<T>) matching.get();
        } else {
            return null;
        }
    }

    private static Map<String, Object> getKafkaProducerConfiguration(KafkaConnectorOutgoingConfiguration configuration) {
        Map<String, Object> map = new HashMap<>();
        JsonHelper.asJsonObject(configuration.config())
                .forEach(e -> map.put(e.getKey(), e.getValue().toString()));

        // Acks must be a string, even when "1".
        map.put(ProducerConfig.ACKS_CONFIG, configuration.getAcks());

        if (!map.containsKey(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)) {
            log.configServers(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, configuration.getBootstrapServers());
            map.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, configuration.getBootstrapServers());
        }

        if (!map.containsKey(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)) {
            log.keySerializerOmitted();
            map.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, configuration.getKeySerializer());
        }

        // Producer id generation:
        // 1. If no client id set in the config, the default prefix is "kafka-producer-"
        // 2. If a client id set in the config, the default prefix is ""

        map.compute(ProducerConfig.CLIENT_ID_CONFIG, (k, configured) -> {
            if (configured == null) {
                return configuration.getClientIdPrefix().orElse("kafka-producer-") + configuration.getChannel();
            } else {
                return configuration.getClientIdPrefix().orElse("") + configured;
            }
        });

        if (!map.containsKey(ProducerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG)) {
            // If no backoff is set, use 10s, it avoids high load on disconnection.
            map.put(ProducerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, "10000");
        }

        ConfigurationCleaner.cleanupProducerConfiguration(map);

        return map;
    }

    public String get(String attribute) {
        return (String) kafkaConfiguration.get(attribute);
    }

    @Override
    public Producer<K, V> unwrap() {
        return producerRef.get();
    }

    @Override
    public Map<String, ?> configuration() {
        return kafkaConfiguration;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            int timeout = this.closetimeout;
            Uni<Void> uni = runOnSendingThread(p -> {
                if (System.getSecurityManager() == null) {
                    p.close(Duration.ofMillis(timeout));
                } else {
                    AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                        p.close(Duration.ofMillis(timeout));
                        return null;
                    });
                }
            }).onItem().invoke(kafkaWorker::shutdown);

            if (Context.isOnEventLoopThread()) {
                // We can't block, just forget the result
                uni.subscribeAsCompletionStage();
            } else {
                uni.await().atMost(Duration.ofMillis(timeout * 2L));
            }
        }
    }

}
