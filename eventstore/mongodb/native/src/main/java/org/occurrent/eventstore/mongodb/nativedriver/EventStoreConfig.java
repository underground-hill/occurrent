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

package org.occurrent.eventstore.mongodb.nativedriver;

import com.mongodb.TransactionOptions;
import com.mongodb.client.FindIterable;
import io.cloudevents.CloudEvent;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.occurrent.mongodb.timerepresentation.TimeRepresentation;

import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Function;

/**
 * Configuration for the synchronous java driver MongoDB EventStore
 */
public class EventStoreConfig {
    private static final boolean ENABLE_TRANSACTIONAL_READS_BY_DEFAULT = true;
    private static final Function<FindIterable<Document>, FindIterable<Document>> DEFAULT_QUERY_OPTIONS_FUNCTION = Function.identity();

    public final TransactionOptions transactionOptions;
    public final TimeRepresentation timeRepresentation;
    public final boolean enableTransactionalReads;
    public final Function<FindIterable<Document>, FindIterable<Document>> queryOptions;

    /**
     * Create an {@link EventStoreConfig} indicating to the event store that it should represent time according to the supplied
     * {@code timeRepresentation}. It'll use default {@link TransactionOptions}.
     *
     * @param timeRepresentation How the time field in the {@link CloudEvent} should be represented.
     * @see #EventStoreConfig(TimeRepresentation, TransactionOptions)
     * @see TimeRepresentation
     */
    public EventStoreConfig(TimeRepresentation timeRepresentation) {
        this(timeRepresentation, null);
    }

    /**
     * Create an {@link EventStoreConfig} indicating to the event store that it should represent time according to the supplied
     * {@code timeRepresentation}. Also configure the default {@link TransactionOptions} that the event store will use
     * when starting transactions.
     *
     * @param timeRepresentation How the time field in the {@link CloudEvent} should be represented.
     * @param transactionOptions The default {@link TransactionOptions} that the event store will use when starting transactions.
     * @see #EventStoreConfig(TimeRepresentation, TransactionOptions)
     * @see TimeRepresentation
     */
    public EventStoreConfig(TimeRepresentation timeRepresentation, TransactionOptions transactionOptions) {
        this(timeRepresentation, transactionOptions, ENABLE_TRANSACTIONAL_READS_BY_DEFAULT, DEFAULT_QUERY_OPTIONS_FUNCTION);
    }

    private EventStoreConfig(TimeRepresentation timeRepresentation, TransactionOptions transactionOptions, boolean enableTransactionalReads, Function<FindIterable<Document>, FindIterable<Document>> queryOptions) {
        Objects.requireNonNull(timeRepresentation, "Time representation cannot be null");
        if (transactionOptions == null) {
            this.transactionOptions = TransactionOptions.builder().build();
        } else {
            this.transactionOptions = transactionOptions;
        }
        this.timeRepresentation = timeRepresentation;
        this.enableTransactionalReads = enableTransactionalReads;
        this.queryOptions = queryOptions == null ? DEFAULT_QUERY_OPTIONS_FUNCTION : queryOptions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventStoreConfig)) return false;
        EventStoreConfig that = (EventStoreConfig) o;
        return enableTransactionalReads == that.enableTransactionalReads && Objects.equals(transactionOptions, that.transactionOptions) && timeRepresentation == that.timeRepresentation && Objects.equals(queryOptions, that.queryOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionOptions, timeRepresentation, enableTransactionalReads, queryOptions);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", EventStoreConfig.class.getSimpleName() + "[", "]")
                .add("transactionOptions=" + transactionOptions)
                .add("timeRepresentation=" + timeRepresentation)
                .add("enableTransactionalReads=" + enableTransactionalReads)
                .add("queryOptions=" + queryOptions)
                .toString();
    }

    public static final class Builder {
        private TransactionOptions transactionOptions;
        private TimeRepresentation timeRepresentation;
        private boolean enableTransactionalReads = ENABLE_TRANSACTIONAL_READS_BY_DEFAULT;
        private Function<FindIterable<Document>, FindIterable<Document>> queryOptions = DEFAULT_QUERY_OPTIONS_FUNCTION;

        /**
         * @param transactionOptions The default {@link TransactionOptions} that the event store will use when starting transactions. May be <code>null</code>.
         * @return The builder instance
         */
        public Builder transactionOptions(TransactionOptions transactionOptions) {
            this.transactionOptions = transactionOptions;
            return this;
        }

        /**
         * @param timeRepresentation Configure how the event store should represent time in MongoDB
         * @return The builder instance
         */
        public Builder timeRepresentation(TimeRepresentation timeRepresentation) {
            this.timeRepresentation = timeRepresentation;
            return this;
        }

        /**
         * Toggle whether to use transactions when reading ({@link org.occurrent.eventstore.api.blocking.EventStore#read(String)} etc) from the event store.
         * This is an advanced feature, and you almost always want to have it enabled. There are two reasons for disabling it:
         *
         * <ol>
         * <li>There's a bug/limitation on Atlas free tier clusters which yields an exception when reading large number of events in a stream in a transaction.
         * To workaround this you could disable transactional reads. The exception takes this form:
         * <pre>
         * java.lang.IllegalStateException: state should be: open
         * at com.mongodb.assertions.Assertions.isTrue(Assertions.java:79)
         * at com.mongodb.internal.session.BaseClientSessionImpl.getServerSession(BaseClientSessionImpl.java:101)
         * at com.mongodb.internal.session.ClientSessionContext.getSessionId(ClientSessionContext.java:44)
         * at com.mongodb.internal.connection.ClusterClockAdvancingSessionContext.getSessionId(ClusterClockAdvancingSessionContext.java:46)
         * at com.mongodb.internal.connection.CommandMessage.getExtraElements(CommandMessage.java:265)
         * at com.mongodb.internal.connection.CommandMessage.encodeMessageBodyWithMetadata(CommandMessage.java:155)
         * at com.mongodb.internal.connection.RequestMessage.encode(RequestMessage.java:138)
         * at com.mongodb.internal.connection.CommandMessage.encode(CommandMessage.java:59)
         * at com.mongodb.internal.connection.InternalStreamConnection.sendAndReceive(InternalStreamConnection.java:268)
         * at com.mongodb.internal.connection.UsageTrackingInternalConnection.sendAndReceive(UsageTrackingInternalConnection.java:100)
         * at com.mongodb.internal.connection.DefaultConnectionPool$PooledConnection.sendAndReceive(DefaultConnectionPool.java:490)
         * at com.mongodb.internal.connection.CommandProtocolImpl.execute(CommandProtocolImpl.java:71)
         * at com.mongodb.internal.connection.DefaultServer$DefaultServerProtocolExecutor.execute(DefaultServer.java:253)
         * at com.mongodb.internal.connection.DefaultServerConnection.executeProtocol(DefaultServerConnection.java:202)
         * at com.mongodb.internal.connection.DefaultServerConnection.command(DefaultServerConnection.java:118)
         * at com.mongodb.internal.connection.DefaultServerConnection.command(DefaultServerConnection.java:110)
         * at com.mongodb.internal.operation.QueryBatchCursor.getMore(QueryBatchCursor.java:268)
         * at com.mongodb.internal.operation.QueryBatchCursor.hasNext(QueryBatchCursor.java:141)
         * at com.mongodb.client.internal.MongoBatchCursorAdapter.hasNext(MongoBatchCursorAdapter.java:54)
         * at java.base/java.util.Iterator.forEachRemaining(Iterator.java:132)
         * at java.base/java.util.Spliterators$IteratorSpliterator.forEachRemaining(Spliterators.java:1801)
         * at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:484)
         * at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:474)
         * at java.base/java.util.stream.ReduceOps$ReduceOp.evaluateSequential(ReduceOps.java:913)
         * at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
         * </pre>
         * It's possible that this would work if you enable "no cursor timeout" on the query, but this is not allowed on Atlas free tier.
         * </li>
         * <li>You're set back by the performance penalty of transactions and are willing to sacrifice read consistency</li>
         * </ol>
         * <p>
         * If you disable transactional reads, you <i>may</i> end up with a mismatch between the version number in the {@link org.occurrent.eventstore.api.blocking.EventStream} and
         * the last event returned from the event stream. This is because Occurrent does two reads to MongoDB when reading an event stream. First it finds the current version number of the stream (A),
         * and secondly it queries for all events (B). If you disable transactional reads, then another thread might have written more events before the call to B has been made. Thus, the version number
         * received from query A might be stale. This may or may not be a problem for your domain, but it's generally recommended having transactional reads enabled.
         * <br>
         * <br>
         * <p>
         * Note that this will only affect the {@code read} methods in the event store, the {@code query} methods doesn't use transactions.
         * </p>
         *
         * @param enableTransactionalReads <code>true</code> to enable, <code>false</code> to disable.
         * @return A same {@code Builder instance}
         */
        public Builder transactionalReads(boolean enableTransactionalReads) {
            this.enableTransactionalReads = enableTransactionalReads;
            return this;
        }

        /**
         * Specify a function that can be used to configure the query options used for {@link org.occurrent.eventstore.api.blocking.EventStore#read(String)} and {@link org.occurrent.eventstore.api.blocking.EventStoreQueries}.
         * This is an advanced feature and should be used sparingly. For example, you can configure cursor timeout, whether slave is OK, etc. By default, mongodb default query options are used.
         * <br><br>
         * Note that you must <i>not</i> use this to change the query itself, i.e. don't use the {@link FindIterable#sort(Bson)} etc. Only use options such as {@link FindIterable#batchSize(int)} that doesn't change
         * the actual query or sort order.
         *
         * @param queryOptions The query options function to use, it cannot return null.
         * @return A same {@code Builder instance}
         */
        public Builder queryOptions(Function<FindIterable<Document>, FindIterable<Document>> queryOptions) {
            this.queryOptions = queryOptions;
            return this;
        }

        public EventStoreConfig build() {
            return new EventStoreConfig(timeRepresentation, transactionOptions, enableTransactionalReads, queryOptions);
        }
    }
}