/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.tests.integration.compat.kafka;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.tests.integration.suites.PulsarTestSuite;
import org.testng.annotations.Test;

@Slf4j
public class KafkaApiTest extends PulsarTestSuite {

    @Test(timeOut = 30000)
    public void testSimpleProducerConsumer() throws Exception {
        String topic = "persistent://public/default/testSimpleProducerConsumer";

        Properties producerProperties = new Properties();
        producerProperties.put("bootstrap.servers", pulsarCluster.getPlainTextServiceUrl());
        producerProperties.put("key.serializer", IntegerSerializer.class.getName());
        producerProperties.put("value.serializer", StringSerializer.class.getName());
        Producer<Integer, String> producer = new KafkaProducer<>(producerProperties);

        Properties consumerProperties = new Properties();
        consumerProperties.put("bootstrap.servers", pulsarCluster.getPlainTextServiceUrl());
        consumerProperties.put("group.id", "my-subscription-name");
        consumerProperties.put("key.deserializer", IntegerDeserializer.class.getName());
        consumerProperties.put("value.deserializer", StringDeserializer.class.getName());
        consumerProperties.put("enable.auto.commit", "true");
        Consumer<Integer, String> consumer = new KafkaConsumer<>(consumerProperties);
        consumer.subscribe(Arrays.asList(topic));

        List<Long> offsets = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            RecordMetadata md = producer.send(new ProducerRecord<Integer, String>(topic, i, "hello-" + i)).get();
            offsets.add(md.offset());
            log.info("Published message at {}", Long.toHexString(md.offset()));
        }

        producer.flush();
        producer.close();

        AtomicInteger received = new AtomicInteger();
        while (received.get() < 10) {
            ConsumerRecords<Integer, String> records = consumer.poll(100);
            records.forEach(record -> {
                assertEquals(record.key().intValue(), received.get());
                assertEquals(record.value(), "hello-" + received.get());
                assertEquals(record.offset(), offsets.get(received.get()).longValue());

                received.incrementAndGet();
            });

            consumer.commitSync();
        }

        consumer.close();
    }

    @Test
    public void testSimpleConsumer() throws Exception {
        String topic = "testSimpleConsumer";

        Properties props = new Properties();
        props.put("bootstrap.servers", pulsarCluster.getPlainTextServiceUrl());
        props.put("group.id", "my-subscription-name");
        props.put("enable.auto.commit", "false");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());

        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Arrays.asList(topic));

        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsarCluster.getPlainTextServiceUrl()).build();
        org.apache.pulsar.client.api.Producer<byte[]> pulsarProducer = pulsarClient.newProducer().topic(topic).create();

        for (int i = 0; i < 10; i++) {
            pulsarProducer.newMessage().key(Integer.toString(i)).value(("hello-" + i).getBytes()).send();
        }

        AtomicInteger received = new AtomicInteger();
        while (received.get() < 10) {
            ConsumerRecords<String, String> records = consumer.poll(100);
            records.forEach(record -> {
                assertEquals(record.key(), Integer.toString(received.get()));
                assertEquals(record.value(), "hello-" + received.get());

                received.incrementAndGet();
            });

            consumer.commitSync();
        }

        consumer.close();
    }

    @Test
    public void testConsumerAutoCommit() throws Exception {
        String topic = "testConsumerAutoCommit";

        Properties props = new Properties();
        props.put("bootstrap.servers", pulsarCluster.getPlainTextServiceUrl());
        props.put("group.id", "my-subscription-name");
        props.put("enable.auto.commit", "true");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());

        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Arrays.asList(topic));

        @Cleanup
        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsarCluster.getPlainTextServiceUrl()).build();
        org.apache.pulsar.client.api.Producer<byte[]> pulsarProducer = pulsarClient.newProducer().topic(topic).create();

        for (int i = 0; i < 10; i++) {
            pulsarProducer.newMessage().key(Integer.toString(i)).value(("hello-" + i).getBytes()).send();
        }

        AtomicInteger received = new AtomicInteger();
        while (received.get() < 10) {
            ConsumerRecords<String, String> records = consumer.poll(100);
            records.forEach(record -> {
                assertEquals(record.key(), Integer.toString(received.get()));
                assertEquals(record.value(), "hello-" + received.get());
                received.incrementAndGet();
            });
        }

        consumer.close();

        // Re-open consumer and verify every message was acknowledged
        Consumer<String, String> consumer2 = new KafkaConsumer<>(props);
        consumer2.subscribe(Arrays.asList(topic));

        ConsumerRecords<String, String> records = consumer2.poll(100);
        assertEquals(records.count(), 0);
        consumer2.close();
    }

    @Test
    public void testConsumerManualOffsetCommit() throws Exception {
        String topic = "testConsumerManualOffsetCommit";

        Properties props = new Properties();
        props.put("bootstrap.servers", pulsarCluster.getPlainTextServiceUrl());
        props.put("group.id", "my-subscription-name");
        props.put("enable.auto.commit", "false");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());

        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Arrays.asList(topic));

        @Cleanup
        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsarCluster.getPlainTextServiceUrl()).build();
        org.apache.pulsar.client.api.Producer<byte[]> pulsarProducer = pulsarClient.newProducer().topic(topic).create();

        for (int i = 0; i < 10; i++) {
            pulsarProducer.newMessage().key(Integer.toString(i)).value(("hello-" + i).getBytes()).send();
        }

        AtomicInteger received = new AtomicInteger();
        while (received.get() < 10) {
            ConsumerRecords<String, String> records = consumer.poll(100);
            records.forEach(record -> {
                assertEquals(record.key(), Integer.toString(received.get()));
                assertEquals(record.value(), "hello-" + received.get());

                Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                offsets.put(new TopicPartition(record.topic(), record.partition()),
                        new OffsetAndMetadata(record.offset()));
                consumer.commitSync(offsets);

                received.incrementAndGet();
            });
        }

        consumer.close();

        // Re-open consumer and verify every message was acknowledged
        Consumer<String, String> consumer2 = new KafkaConsumer<>(props);
        consumer2.subscribe(Arrays.asList(topic));

        ConsumerRecords<String, String> records = consumer2.poll(100);
        assertEquals(records.count(), 0);
        consumer2.close();
    }

    @Test
    public void testPartitions() throws Exception {
        String topic = "testPartitions";

        // Create 8 partitions in topic
        @Cleanup
        PulsarAdmin admin = PulsarAdmin.builder().serviceHttpUrl(pulsarCluster.getHttpServiceUrl()).build();
        admin.topics().createPartitionedTopic(topic, 8);

        Properties props = new Properties();
        props.put("bootstrap.servers", pulsarCluster.getPlainTextServiceUrl());
        props.put("group.id", "my-subscription-name");
        props.put("enable.auto.commit", "true");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());

        @Cleanup
        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsarCluster.getPlainTextServiceUrl()).build();
        org.apache.pulsar.client.api.Producer<byte[]> pulsarProducer = pulsarClient.newProducer().topic(topic)
                .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition).create();

        // Create 2 Kakfa consumer and verify each gets half of the messages
        List<Consumer<String, String>> consumers = new ArrayList<>();
        for (int c = 0; c < 2; c++) {
            Consumer<String, String> consumer = new KafkaConsumer<>(props);
            consumer.subscribe(Arrays.asList(topic));
            consumers.add(consumer);
        }

        int N = 8 * 3;

        for (int i = 0; i < N; i++) {
            pulsarProducer.newMessage().key(Integer.toString(i)).value(("hello-" + i).getBytes()).send();
        }

        consumers.forEach(consumer -> {
            int expectedMessaged = N / consumers.size();

            for (int i = 0; i < expectedMessaged;) {
                ConsumerRecords<String, String> records = consumer.poll(100);
                i += records.count();
            }

            // No more messages for this consumer
            ConsumerRecords<String, String> records = consumer.poll(100);
            assertEquals(records.count(), 0);
        });

        consumers.forEach(Consumer::close);
    }

    @Test
    public void testConsumerSeek() throws Exception {
        String topic = "testSimpleConsumer";

        Properties props = new Properties();
        props.put("bootstrap.servers", pulsarCluster.getPlainTextServiceUrl());
        props.put("group.id", "my-subscription-name");
        props.put("enable.auto.commit", "false");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        props.put("pulsar.consumer.acknowledgments.group.time.millis", "0");

        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Arrays.asList(topic));

        @Cleanup
        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsarCluster.getPlainTextServiceUrl()).build();
        org.apache.pulsar.client.api.Producer<byte[]> pulsarProducer = pulsarClient.newProducer().topic(topic).create();

        for (int i = 0; i < 10; i++) {
            pulsarProducer.newMessage().key(Integer.toString(i)).value(("hello-" + i).getBytes()).send();
        }

        AtomicInteger received = new AtomicInteger();
        while (received.get() < 10) {
            ConsumerRecords<String, String> records = consumer.poll(100);
            records.forEach(record -> {
                assertEquals(record.key(), Integer.toString(received.get()));
                assertEquals(record.value(), "hello-" + received.get());

                received.incrementAndGet();
            });

            consumer.commitSync();
        }

        consumer.seekToBeginning(Collections.emptyList());

        Thread.sleep(500);

        // Messages should be available again
        received.set(0);
        while (received.get() < 10) {
            ConsumerRecords<String, String> records = consumer.poll(100);
            records.forEach(record -> {
                assertEquals(record.key(), Integer.toString(received.get()));
                assertEquals(record.value(), "hello-" + received.get());

                received.incrementAndGet();
            });

            consumer.commitSync();
        }

        consumer.close();
    }

    @Test
    public void testConsumerSeekToEnd() throws Exception {
        String topic = "testSimpleConsumer";

        Properties props = new Properties();
        props.put("bootstrap.servers", pulsarCluster.getPlainTextServiceUrl());
        props.put("group.id", "my-subscription-name");
        props.put("enable.auto.commit", "false");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        props.put("pulsar.consumer.acknowledgments.group.time.millis", "0");

        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Arrays.asList(topic));

        @Cleanup
        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsarCluster.getPlainTextServiceUrl()).build();
        org.apache.pulsar.client.api.Producer<byte[]> pulsarProducer = pulsarClient.newProducer().topic(topic).create();

        for (int i = 0; i < 10; i++) {
            pulsarProducer.newMessage().key(Integer.toString(i)).value(("hello-" + i).getBytes()).send();
        }

        AtomicInteger received = new AtomicInteger();
        while (received.get() < 10) {
            ConsumerRecords<String, String> records = consumer.poll(100);
            records.forEach(record -> {
                assertEquals(record.key(), Integer.toString(received.get()));
                assertEquals(record.value(), "hello-" + received.get());

                received.incrementAndGet();
            });

            consumer.commitSync();
        }

        consumer.seekToEnd(Collections.emptyList());
        Thread.sleep(500);

        consumer.close();

        // Recreate the consumer
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Arrays.asList(topic));

        ConsumerRecords<String, String> records = consumer.poll(100);
        // Since we are at the end of the topic, there should be no messages
        assertEquals(records.count(), 0);

        consumer.close();
    }

    @Test
    public void testSimpleProducer() throws Exception {
        String topic = "testSimpleProducer";

        @Cleanup
        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsarCluster.getPlainTextServiceUrl()).build();
        org.apache.pulsar.client.api.Consumer<byte[]> pulsarConsumer = pulsarClient.newConsumer().topic(topic)
                .subscriptionName("my-subscription")
                .subscribe();

        Properties props = new Properties();
        props.put("bootstrap.servers", pulsarCluster.getPlainTextServiceUrl());

        props.put("key.serializer", IntegerSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        Producer<Integer, String> producer = new KafkaProducer<>(props);

        for (int i = 0; i < 10; i++) {
            producer.send(new ProducerRecord<Integer, String>(topic, i, "hello-" + i));
        }

        producer.flush();
        producer.close();

        for (int i = 0; i < 10; i++) {
            Message<byte[]> msg = pulsarConsumer.receive(1, TimeUnit.SECONDS);
            assertEquals(new String(msg.getData()), "hello-" + i);
            pulsarConsumer.acknowledge(msg);
        }
    }

    @Test(timeOut = 10000)
    public void testProducerCallback() throws Exception {
        String topic = "testProducerCallback";

        @Cleanup
        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsarCluster.getPlainTextServiceUrl()).build();
        org.apache.pulsar.client.api.Consumer<byte[]> pulsarConsumer = pulsarClient.newConsumer()
                .topic(topic)
                .subscriptionName("my-subscription")
                .subscribe();

        Properties props = new Properties();
        props.put("bootstrap.servers", pulsarCluster.getPlainTextServiceUrl());

        props.put("key.serializer", IntegerSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        Producer<Integer, String> producer = new KafkaProducer<>(props);

        CountDownLatch counter = new CountDownLatch(10);

        for (int i = 0; i < 10; i++) {
            producer.send(new ProducerRecord<Integer, String>(topic, i, "hello-" + i), (metadata, exception) -> {
                assertEquals(metadata.topic(), topic);
                assertNull(exception);

                counter.countDown();
            });
        }

        counter.await();

        for (int i = 0; i < 10; i++) {
            Message<byte[]> msg = pulsarConsumer.receive(1, TimeUnit.SECONDS);
            assertEquals(new String(msg.getData()), "hello-" + i);
            pulsarConsumer.acknowledge(msg);
        }

        producer.close();
    }
}
