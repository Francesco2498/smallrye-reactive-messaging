package io.smallrye.reactive.messaging.kafka.locals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.base.KafkaCompanionTestBase;
import io.smallrye.reactive.messaging.kafka.base.KafkaMapBasedConfig;
import io.smallrye.reactive.messaging.providers.locals.LocalContextMetadata;
import io.vertx.mutiny.core.Vertx;

public class LocalPropagationAckTest extends KafkaCompanionTestBase {

    private KafkaMapBasedConfig dataconfig() {
        return kafkaConfig("mp.messaging.incoming.data")
                .put("value.deserializer", IntegerDeserializer.class.getName())
                .put("auto.offset.reset", "earliest")
                .put("topic", topic);
    }

    @BeforeEach
    void setUp() {
        companion.produceIntegers().usingGenerator(i -> new ProducerRecord<>(topic, i + 1), 5)
                .awaitCompletion();
    }

    @Test
    public void testChannelWithAckOnMessageContextThrottled() {
        IncomingChannelWithAckOnMessageContext bean = runApplication(dataconfig(),
                IncomingChannelWithAckOnMessageContext.class);
        bean.process(i -> i + 1);
        await().until(() -> bean.getResults().size() >= 5);
        assertThat(bean.getResults()).containsExactly(2, 3, 4, 5, 6);
    }

    @Test
    public void testChannelWithAckOnMessageContextLatest() {
        IncomingChannelWithAckOnMessageContext bean = runApplication(dataconfig()
                .with("commit-strategy", "latest"),
                IncomingChannelWithAckOnMessageContext.class);
        bean.process(i -> i + 1);
        await().until(() -> bean.getResults().size() >= 5);
        assertThat(bean.getResults()).containsExactly(2, 3, 4, 5, 6);
    }

    @Test
    public void testIncomingChannelWithNackOnMessageContextFailStop() {
        IncomingChannelWithAckOnMessageContext bean = runApplication(dataconfig(),
                IncomingChannelWithAckOnMessageContext.class);
        bean.process(i -> {
            throw new RuntimeException("boom");
        });

        await().until(() -> bean.getResults().size() >= 1);
        assertThat(bean.getResults()).containsExactly(1);
    }

    @Test
    public void testIncomingChannelWithNackOnMessageContextIgnore() {
        IncomingChannelWithAckOnMessageContext bean = runApplication(dataconfig()
                .with("failure-strategy", "ignore"),
                IncomingChannelWithAckOnMessageContext.class);
        bean.process(i -> {
            throw new RuntimeException("boom");
        });

        await().until(() -> bean.getResults().size() >= 5);
        assertThat(bean.getResults()).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    public void testIncomingChannelWithNackOnMessageContextDeadLetterQueue() {
        IncomingChannelWithAckOnMessageContext bean = runApplication(dataconfig()
                .with("failure-strategy", "dead-letter-queue"),
                IncomingChannelWithAckOnMessageContext.class);
        bean.process(i -> {
            throw new RuntimeException("boom");
        });

        await().until(() -> bean.getResults().size() >= 5);
        assertThat(bean.getResults()).containsExactly(1, 2, 3, 4, 5);
    }

    @ApplicationScoped
    public static class IncomingChannelWithAckOnMessageContext {

        private final List<Integer> list = new CopyOnWriteArrayList<>();

        @Inject
        @Channel("data")
        Multi<Message<Integer>> incoming;

        void process(Function<Integer, Integer> mapper) {
            incoming.onItem()
                    .transformToUniAndConcatenate(msg -> Uni.createFrom()
                            .item(() -> msg.withPayload(mapper.apply(msg.getPayload())))
                            .chain(m -> Uni.createFrom().completionStage(m.ack()).replaceWith(m))
                            .onFailure().recoverWithUni(t -> Uni.createFrom().completionStage(msg.nack(t))
                                    .onItemOrFailure().transform((unused, throwable) -> msg)))
                    .subscribe().with(m -> {
                        m.getMetadata(LocalContextMetadata.class).map(LocalContextMetadata::context).ifPresent(context -> {
                            if (Vertx.currentContext().getDelegate() == context) {
                                list.add(m.getPayload());
                            }
                        });
                    });
        }

        List<Integer> getResults() {
            return list;
        }
    }

}