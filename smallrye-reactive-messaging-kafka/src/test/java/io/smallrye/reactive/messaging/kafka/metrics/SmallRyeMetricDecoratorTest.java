package io.smallrye.reactive.messaging.kafka.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.enterprise.util.AnnotationLiteral;

import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.RegistryType;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.eclipse.microprofile.reactive.streams.operators.PublisherBuilder;
import org.eclipse.microprofile.reactive.streams.operators.ReactiveStreams;
import org.junit.jupiter.api.Test;

import io.smallrye.metrics.setup.MetricCdiInjectionExtension;
import io.smallrye.reactive.messaging.PublisherDecorator;
import io.smallrye.reactive.messaging.kafka.base.WeldTestBase;
import io.smallrye.reactive.messaging.metrics.MetricDecorator;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

public class SmallRyeMetricDecoratorTest extends WeldTestBase {

    @Test
    void testOnlyMetricDecoratorAvailable() {
        container = weld.initialize();
        Instance<PublisherDecorator> decorators = container.select(PublisherDecorator.class);
        assertThat(decorators).hasSize(1);
        assertThat(decorators.get()).isInstanceOf(MetricDecorator.class);
    }

    @Test
    public void testMicroProfileMetrics() {
        weld.addExtensions(MetricCdiInjectionExtension.class);
        MetricsTestBean bean = runApplication(config(), MetricsTestBean.class);

        await().until(() -> bean.received().size() == 6);

        assertEquals(MetricsTestBean.TEST_MESSAGES.size(), getCounter("source").getCount());

        // Between source and sink, each message is duplicated so we expect double the count for sink
        assertEquals(MetricsTestBean.TEST_MESSAGES.size() * 2, getCounter("sink").getCount());
    }

    private MapBasedConfig config() {
        return new MapBasedConfig().put("smallrye.messaging.metrics.mp.enabled", true);
    }

    private Counter getCounter(String channelName) {
        MetricRegistry registry = container.select(MetricRegistry.class, RegistryTypeLiteral.BASE).get();
        return registry.counter("mp.messaging.message.count", new Tag("channel", channelName));
    }

    @SuppressWarnings("serial")
    private static class RegistryTypeLiteral extends AnnotationLiteral<RegistryType> implements RegistryType {
        public static final RegistryTypeLiteral BASE = new RegistryTypeLiteral(MetricRegistry.Type.BASE);

        private MetricRegistry.Type registryType;

        public RegistryTypeLiteral(MetricRegistry.Type registryType) {
            this.registryType = registryType;
        }

        @Override
        public MetricRegistry.Type type() {
            return registryType;
        }
    }

    @ApplicationScoped
    public static class MetricsTestBean {

        public static final List<String> TEST_MESSAGES = Arrays.asList("foo", "bar", "baz");

        final List<String> received = new ArrayList<>();

        @Outgoing("source")
        public PublisherBuilder<String> source() {
            return ReactiveStreams.fromIterable(TEST_MESSAGES);
        }

        @Incoming("source")
        @Outgoing("sink")
        public PublisherBuilder<String> duplicate(String input) {
            return ReactiveStreams.of(input, input);
        }

        @Incoming("sink")
        public void sink(String message) {
            received.add(message);
        }

        public List<String> received() {
            return received;
        }
    }
}
