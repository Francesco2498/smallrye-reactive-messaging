package io.smallrye.reactive.messaging.acknowledgement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.*;
import org.junit.Before;
import org.junit.Test;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.ProcessingException;
import io.smallrye.reactive.messaging.WeldTestBaseWithoutTails;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.vertx.core.impl.ConcurrentHashSet;

public class SynchronousPayloadProcessorAckTest extends WeldTestBaseWithoutTails {

    @Before
    public void deployBase() {
        addBeanClass(EmitterBean.class, Sink.class);
    }

    @Test
    public void testThatMessagesAreAckedAfterSuccessfulProcessingOfPayload() throws InterruptedException {
        addBeanClass(SuccessfulPayloadProcessor.class);
        initialize();
        Emitter<String> emitter = get(EmitterBean.class).emitter();
        Sink sink = get(Sink.class);

        Set<String> acked = new ConcurrentHashSet<>();
        Set<String> nacked = new ConcurrentHashSet<>();

        run(acked, nacked, emitter);

        await().until(() -> sink.list().size() == 10);
        assertThat(acked).hasSize(10);
        assertThat(nacked).hasSize(0);
    }

    @Test
    public void testThatMessagesAreAckedAfterSuccessfulProcessingOfMessage() throws InterruptedException {
        addBeanClass(SuccessfulMessageToPayloadProcessor.class);
        initialize();
        Emitter<String> emitter = get(EmitterBean.class).emitter();
        Sink sink = get(Sink.class);

        Set<String> acked = new ConcurrentHashSet<>();
        Set<String> nacked = new ConcurrentHashSet<>();

        run(acked, nacked, emitter);

        await().until(() -> sink.list().size() == 10);
        assertThat(acked).hasSize(10);
        assertThat(nacked).hasSize(0);
    }

    @Test
    public void testThatMessagesAreNackedAfterFailingProcessingOfPayload() throws InterruptedException {
        addBeanClass(FailingPayloadProcessor.class);
        initialize();
        Emitter<String> emitter = get(EmitterBean.class).emitter();
        Sink sink = get(Sink.class);

        Set<String> acked = new ConcurrentHashSet<>();
        Set<String> nacked = new ConcurrentHashSet<>();

        List<Throwable> throwables = run(acked, nacked, emitter);

        await().until(() -> sink.list().size() == 8);
        assertThat(acked).hasSize(9);
        assertThat(nacked).hasSize(1);
        assertThat(throwables).hasSize(1).allSatisfy(t -> assertThat(t).isInstanceOf(ProcessingException.class)
                .hasCauseInstanceOf(InvocationTargetException.class).hasStackTraceContaining("b"));
    }

    @Test
    public void testThatMessagesAreNackedAfterFailingProcessingOfMessage() throws InterruptedException {
        addBeanClass(FailingMessageToPayloadProcessor.class);
        initialize();
        Emitter<String> emitter = get(EmitterBean.class).emitter();
        Sink sink = get(Sink.class);

        Set<String> acked = new ConcurrentHashSet<>();
        Set<String> nacked = new ConcurrentHashSet<>();

        List<Throwable> throwables = run(acked, nacked, emitter);

        await().until(() -> sink.list().size() == 8);
        assertThat(acked).hasSize(9);
        assertThat(nacked).hasSize(1);
        assertThat(throwables).hasSize(1).allSatisfy(t -> assertThat(t).isInstanceOf(ProcessingException.class)
                .hasCauseInstanceOf(InvocationTargetException.class).hasStackTraceContaining("b"));
    }

    @Test
    public void testThatMessagesAreAckedAfterSuccessfulBlockingProcessingOfPayload() throws InterruptedException {
        addBeanClass(SuccessfulBlockingPayloadProcessor.class);
        initialize();
        Emitter<String> emitter = get(EmitterBean.class).emitter();
        Sink sink = get(Sink.class);

        Set<String> acked = new ConcurrentHashSet<>();
        Set<String> nacked = new ConcurrentHashSet<>();

        run(acked, nacked, emitter);

        await().until(() -> sink.list().size() == 10);
        assertThat(acked).hasSize(10);
        assertThat(nacked).hasSize(0);
    }

    @Test
    public void testThatMessagesAreAckedAfterSuccessfulBlockingProcessingOfMessage() throws InterruptedException {
        addBeanClass(SuccessfulBlockingMessageToPayloadProcessor.class);
        initialize();
        Emitter<String> emitter = get(EmitterBean.class).emitter();
        Sink sink = get(Sink.class);

        Set<String> acked = new ConcurrentHashSet<>();
        Set<String> nacked = new ConcurrentHashSet<>();

        run(acked, nacked, emitter);

        await().until(() -> sink.list().size() == 10);
        assertThat(acked).hasSize(10);
        assertThat(nacked).hasSize(0);
    }

    @Test
    public void testThatMessagesAreNackedAfterFailingBlockingProcessingOfPayload() throws InterruptedException {
        addBeanClass(FailingBlockingPayloadProcessor.class);
        initialize();
        Emitter<String> emitter = get(EmitterBean.class).emitter();
        Sink sink = get(Sink.class);

        Set<String> acked = new ConcurrentHashSet<>();
        Set<String> nacked = new ConcurrentHashSet<>();

        List<Throwable> throwables = run(acked, nacked, emitter);

        await().until(() -> sink.list().size() == 8);
        assertThat(acked).hasSize(9);
        assertThat(nacked).hasSize(1);
        assertThat(throwables).hasSize(1).allSatisfy(t -> assertThat(t).isInstanceOf(ProcessingException.class)
                .hasCauseInstanceOf(InvocationTargetException.class).hasStackTraceContaining("b"));
    }

    @Test
    public void testThatMessagesAreNackedAfterFailingBlockingProcessingOfMessage() throws InterruptedException {
        addBeanClass(FailingBlockingMessageToPayloadProcessor.class);
        initialize();
        Emitter<String> emitter = get(EmitterBean.class).emitter();
        Sink sink = get(Sink.class);

        Set<String> acked = new ConcurrentHashSet<>();
        Set<String> nacked = new ConcurrentHashSet<>();

        List<Throwable> throwables = run(acked, nacked, emitter);

        await().until(() -> sink.list().size() == 8);
        assertThat(acked).hasSize(9);
        assertThat(nacked).hasSize(1);
        assertThat(throwables).hasSize(1).allSatisfy(t -> assertThat(t).isInstanceOf(ProcessingException.class)
                .hasCauseInstanceOf(InvocationTargetException.class).hasStackTraceContaining("b"));
    }

    private List<Throwable> run(Set<String> acked, Set<String> nacked, Emitter<String> emitter)
            throws InterruptedException {
        List<Throwable> reasons = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Multi.createFrom().items("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
                .onItem()
                .produceCompletionStage(i -> CompletableFuture.runAsync(() -> emitter.send(Message.of(i, Metadata.empty(),
                        () -> {
                            acked.add(i);
                            return CompletableFuture.completedFuture(null);
                        }, t -> {
                            reasons.add(t);
                            nacked.add(i);
                            return CompletableFuture.completedFuture(null);
                        })))
                        .thenApply(x -> i))
                .merge()
                .subscribe().with(
                        x -> {
                            // noop
                        },
                        f -> {
                            // noop
                        },
                        done::countDown);

        done.await(10, TimeUnit.SECONDS);
        return reasons;
    }

    @ApplicationScoped
    public static class Sink {
        List<String> list = new CopyOnWriteArrayList<>();

        @Incoming("out")
        public void consume(String s) {
            list.add(s);
        }

        public List<String> list() {
            return list;
        }
    }

    @ApplicationScoped
    public static class SuccessfulPayloadProcessor {

        @Incoming("data")
        @Outgoing("out")
        public String process(String s) {
            return s.toUpperCase();
        }

    }

    @ApplicationScoped
    public static class SuccessfulMessageToPayloadProcessor {

        @Incoming("data")
        @Outgoing("out")
        @Acknowledgment(Acknowledgment.Strategy.POST_PROCESSING)
        public String process(Message<String> s) {
            return s.getPayload().toUpperCase();
        }

    }

    @ApplicationScoped
    public static class FailingPayloadProcessor {

        @Incoming("data")
        @Outgoing("out")
        public String process(String s) {
            if (s.equalsIgnoreCase("b")) {
                // nacked.
                throw new IllegalArgumentException("b");
            }

            if (s.equalsIgnoreCase("e")) {
                // acked but not forwarded
                return null;
            }

            return s.toUpperCase();
        }

    }

    @ApplicationScoped
    public static class FailingMessageToPayloadProcessor {

        @Incoming("data")
        @Outgoing("out")
        @Acknowledgment(Acknowledgment.Strategy.POST_PROCESSING)
        public String process(Message<String> m) {
            String s = m.getPayload();
            if (s.equalsIgnoreCase("b")) {
                // nacked.
                throw new IllegalArgumentException("b");
            }

            if (s.equalsIgnoreCase("e")) {
                // acked but not forwarded
                return null;
            }

            return s.toUpperCase();
        }

    }

    @ApplicationScoped
    public static class SuccessfulBlockingPayloadProcessor {

        @Incoming("data")
        @Outgoing("out")
        @Blocking
        public String process(String s) {
            return CompletableFuture.supplyAsync(s::toUpperCase).join();
        }

    }

    @ApplicationScoped
    public static class SuccessfulBlockingMessageToPayloadProcessor {

        @Incoming("data")
        @Outgoing("out")
        @Blocking
        @Acknowledgment(Acknowledgment.Strategy.POST_PROCESSING)
        public String process(Message<String> m) {
            String s = m.getPayload();
            return CompletableFuture.supplyAsync(s::toUpperCase).join();
        }

    }

    @ApplicationScoped
    public static class FailingBlockingPayloadProcessor {

        @Incoming("data")
        @Outgoing("out")
        @Blocking
        public String process(String s) {
            return CompletableFuture.supplyAsync(() -> {
                if (s.equalsIgnoreCase("b")) {
                    // nacked.
                    throw new IllegalArgumentException("b");
                }

                if (s.equalsIgnoreCase("e")) {
                    // acked but not forwarded
                    return null;
                }

                return s.toUpperCase();
            }).join();
        }

    }

    @ApplicationScoped
    public static class FailingBlockingMessageToPayloadProcessor {

        @Incoming("data")
        @Outgoing("out")
        @Blocking
        @Acknowledgment(Acknowledgment.Strategy.POST_PROCESSING)
        public String process(Message<String> m) {
            String s = m.getPayload();
            return CompletableFuture.supplyAsync(() -> {
                if (s.equalsIgnoreCase("b")) {
                    // nacked.
                    throw new IllegalArgumentException("b");
                }

                if (s.equalsIgnoreCase("e")) {
                    // acked but not forwarded
                    return null;
                }

                return s.toUpperCase();
            }).join();
        }

    }
}
