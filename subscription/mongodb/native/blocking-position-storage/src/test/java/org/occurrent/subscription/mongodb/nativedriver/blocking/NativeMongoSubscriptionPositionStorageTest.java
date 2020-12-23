/*
 * Copyright 2020 Johan Haleby
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.occurrent.subscription.mongodb.nativedriver.blocking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.occurrent.domain.DomainEvent;
import org.occurrent.domain.NameDefined;
import org.occurrent.domain.NameWasChanged;
import org.occurrent.eventstore.api.blocking.EventStore;
import org.occurrent.eventstore.mongodb.nativedriver.EventStoreConfig;
import org.occurrent.eventstore.mongodb.nativedriver.MongoEventStore;
import org.occurrent.mongodb.timerepresentation.TimeRepresentation;
import org.occurrent.subscription.api.blocking.PositionAwareSubscriptionModel;
import org.occurrent.subscription.api.blocking.SubscriptionPositionStorage;
import org.occurrent.subscription.mongodb.MongoFilterSpecification.MongoJsonFilterSpecification;
import org.occurrent.subscription.util.blocking.DurableSubscriptionModel;
import org.occurrent.testsupport.mongodb.FlushMongoDBExtension;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.mongodb.client.model.Aggregates.match;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.ONE_SECOND;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.occurrent.functional.CheckedFunction.unchecked;
import static org.occurrent.functional.Not.not;
import static org.occurrent.mongodb.timerepresentation.TimeRepresentation.RFC_3339_STRING;
import static org.occurrent.subscription.mongodb.MongoFilterSpecification.FULL_DOCUMENT;
import static org.occurrent.subscription.mongodb.MongoFilterSpecification.MongoBsonFilterSpecification.filter;
import static org.occurrent.time.TimeConversion.toLocalDateTime;

@SuppressWarnings("ConstantConditions")
@Testcontainers
public class NativeMongoSubscriptionPositionStorageTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.2.8");
    private static final String TIMESTAMP_TOKEN_COLLECTION = "subscriptions";

    @RegisterExtension
    FlushMongoDBExtension flushMongoDBExtension = new FlushMongoDBExtension(new ConnectionString(mongoDBContainer.getReplicaSetUrl()));

    private EventStore mongoEventStore;
    private DurableSubscriptionModel subscriptionModel;
    private ObjectMapper objectMapper;
    private ExecutorService subscriptionExecutor;
    private MongoClient mongoClient;
    private MongoDatabase database;

    @BeforeEach
    void create_mongo_event_store() {
        ConnectionString connectionString = new ConnectionString(mongoDBContainer.getReplicaSetUrl() + ".events");
        mongoClient = MongoClients.create(connectionString);
        String databaseName = connectionString.getDatabase();
        String eventCollectionName = connectionString.getCollection();
        database = mongoClient.getDatabase(databaseName);
        TimeRepresentation timeRepresentation = RFC_3339_STRING;
        mongoEventStore = new MongoEventStore(mongoClient, databaseName, eventCollectionName, new EventStoreConfig(timeRepresentation));
        subscriptionExecutor = Executors.newSingleThreadExecutor();
        subscriptionModel = newPersistentSubscription(eventCollectionName, timeRepresentation, RetryStrategy.fixed(200));
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void shutdown() throws InterruptedException {
        subscriptionModel.shutdown();
        subscriptionExecutor.shutdown();
        if (!subscriptionExecutor.awaitTermination(4, SECONDS)) {
            subscriptionExecutor.shutdownNow();
        }
        mongoClient.close();
    }

    @Test
    void blocking_spring_subscription_calls_listener_for_each_new_event() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();
        subscriptionModel.subscribe(UUID.randomUUID().toString(), state::add).waitUntilStarted();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(10), "name3");

        // When
        mongoEventStore.write("1", 0, serialize(nameDefined1));
        mongoEventStore.write("2", 0, serialize(nameDefined2));
        mongoEventStore.write("1", 1, serialize(nameWasChanged1));

        // Then
        await().atMost(2, SECONDS).with().pollInterval(Duration.of(20, MILLIS)).untilAsserted(() -> assertThat(state).hasSize(3));
    }

    @Test
    void blocking_spring_subscription_allows_resuming_events_from_where_it_left_off() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();
        String subscriberId = UUID.randomUUID().toString();
        subscriptionModel.subscribe(subscriberId, state::add).waitUntilStarted();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(10), "name3");

        // When
        mongoEventStore.write("1", 0, serialize(nameDefined1));
        // The subscription is async so we need to wait for it
        await().atMost(ONE_SECOND).until(not(state::isEmpty));
        subscriptionModel.pauseSubscription(subscriberId);
        mongoEventStore.write("2", 0, serialize(nameDefined2));
        mongoEventStore.write("1", 1, serialize(nameWasChanged1));
        subscriptionModel.subscribe(subscriberId, state::add);

        // Then
        await().atMost(2, SECONDS).with().pollInterval(Duration.of(20, MILLIS)).untilAsserted(() -> assertThat(state).hasSize(3));
    }

    @Test
    void blocking_spring_subscription_allows_resuming_events_from_where_it_left_when_first_event_for_subscription_fails_the_first_time() {
        // Given
        LocalDateTime now = LocalDateTime.now();

        // Disable retry
        subscriptionModel = newPersistentSubscription("events", RFC_3339_STRING, RetryStrategy.none());

        AtomicInteger counter = new AtomicInteger();
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();
        String subscriberId = UUID.randomUUID().toString();
        Runnable stream = () -> subscriptionModel.subscribe(subscriberId, cloudEvent -> {
            if (counter.incrementAndGet() == 1) {
                // We simulate error on first event
                throw new IllegalArgumentException("Expected");
            } else {
                state.add(cloudEvent);
            }
        }).waitUntilStarted();
        stream.run();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(10), "name3");

        // When
        mongoEventStore.write("1", 0, serialize(nameDefined1));
        // The subscription is async so we need to wait for it
        await().atMost(ONE_SECOND).and().dontCatchUncaughtExceptions().untilAtomic(counter, equalTo(1));
        // Since an exception occurred we need to run the stream again
        stream.run();
        mongoEventStore.write("2", 0, serialize(nameDefined2));
        mongoEventStore.write("1", 1, serialize(nameWasChanged1));

        // Then
        await().atMost(2, SECONDS).with().pollInterval(Duration.of(20, MILLIS)).untilAsserted(() -> assertThat(state).hasSize(3));
    }

    @Test
    void blocking_spring_subscription_allows_cancelling_subscription() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();
        String subscriberId = UUID.randomUUID().toString();
        subscriptionModel.subscribe(subscriberId, state::add).waitUntilStarted();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");

        // When
        mongoEventStore.write("1", 0, serialize(nameDefined1));
        // The subscription is async so we need to wait for it
        await().atMost(ONE_SECOND).until(not(state::isEmpty));
        subscriptionModel.cancelSubscription(subscriberId);

        // Then
        assertThat(database.getCollection(TIMESTAMP_TOKEN_COLLECTION).countDocuments()).isZero();
    }

    @Test
    void using_bson_query_for_type() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();
        String subscriberId = UUID.randomUUID().toString();
        subscriptionModel.subscribe(subscriberId, filter().type(Filters::eq, NameDefined.class.getName()), state::add).waitUntilStarted();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(3), "name3");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(4), "name4");

        // When
        mongoEventStore.write("1", 0, serialize(nameDefined1));
        mongoEventStore.write("1", 1, serialize(nameWasChanged1));
        mongoEventStore.write("2", 0, serialize(nameDefined2));
        mongoEventStore.write("2", 1, serialize(nameWasChanged2));

        // Then
        await().atMost(ONE_SECOND).until(state::size, is(2));
        assertThat(state).extracting(CloudEvent::getType).containsOnly(NameDefined.class.getName());
    }

    @Test
    void using_bson_query_dsl_composition() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();
        String subscriberId = UUID.randomUUID().toString();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(3), "name3");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(4), "name4");

        subscriptionModel.subscribe(subscriberId, filter().id(Filters::eq, nameDefined2.getEventId()).type(Filters::eq, NameDefined.class.getName()), state::add
        )
                .waitUntilStarted();

        // When
        mongoEventStore.write("1", 0, serialize(nameDefined1));
        mongoEventStore.write("1", 1, serialize(nameWasChanged1));
        mongoEventStore.write("2", 0, serialize(nameDefined2));
        mongoEventStore.write("2", 1, serialize(nameWasChanged2));

        // Then
        await().atMost(ONE_SECOND).until(state::size, is(1));
        assertThat(state).extracting(CloudEvent::getId, CloudEvent::getType).containsOnly(tuple(nameDefined2.getEventId(), NameDefined.class.getName()));
    }

    @Test
    void using_bson_query_native_mongo_filters_composition() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();
        String subscriberId = UUID.randomUUID().toString();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(3), "name3");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(4), "name4");

        subscriptionModel.subscribe(subscriberId, filter(match(and(eq("fullDocument.id", nameDefined2.getEventId()), eq("fullDocument.type", NameDefined.class.getName())))), state::add
        )
                .waitUntilStarted();

        // When
        mongoEventStore.write("1", 0, serialize(nameDefined1));
        mongoEventStore.write("1", 1, serialize(nameWasChanged1));
        mongoEventStore.write("2", 0, serialize(nameDefined2));
        mongoEventStore.write("2", 1, serialize(nameWasChanged2));

        // Then
        await().atMost(ONE_SECOND).until(state::size, is(1));
        assertThat(state).extracting(CloudEvent::getId, CloudEvent::getType).containsOnly(tuple(nameDefined2.getEventId(), NameDefined.class.getName()));
    }

    @Test
    void using_json_query_for_type() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();
        String subscriberId = UUID.randomUUID().toString();
        subscriptionModel.subscribe(subscriberId, MongoJsonFilterSpecification.filter("{ $match : { \"" + FULL_DOCUMENT + ".type\" : \"" + NameDefined.class.getName() + "\" } }"), state::add).waitUntilStarted();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(3), "name3");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(4), "name4");

        // When
        mongoEventStore.write("1", 0, serialize(nameDefined1));
        mongoEventStore.write("1", 1, serialize(nameWasChanged1));
        mongoEventStore.write("2", 0, serialize(nameDefined2));
        mongoEventStore.write("2", 1, serialize(nameWasChanged2));

        // Then
        await().atMost(ONE_SECOND).until(state::size, is(2));
        assertThat(state).extracting(CloudEvent::getType).containsOnly(NameDefined.class.getName());
    }

    private Stream<CloudEvent> serialize(DomainEvent e) {
        return Stream.of(CloudEventBuilder.v1()
                .withId(e.getEventId())
                .withSource(URI.create("http://name"))
                .withType(e.getClass().getName())
                .withTime(toLocalDateTime(e.getTimestamp()).atOffset(UTC))
                .withSubject(e.getName())
                .withDataContentType("application/json")
                .withData(unchecked(objectMapper::writeValueAsBytes).apply(e))
                .build());
    }

    private DurableSubscriptionModel newPersistentSubscription(String eventCollectionName, TimeRepresentation timeRepresentation, RetryStrategy retryStrategy) {
        PositionAwareSubscriptionModel blockingSubscriptionForMongoDB = new NativeMongoSubscriptionModel(database, eventCollectionName, timeRepresentation, subscriptionExecutor, retryStrategy);
        SubscriptionPositionStorage storage = new NativeMongoSubscriptionPositionStorage(database.getCollection(TIMESTAMP_TOKEN_COLLECTION));
        return new DurableSubscriptionModel(blockingSubscriptionForMongoDB, storage);
    }
}