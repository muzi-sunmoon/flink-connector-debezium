package io.yanwu.flink.connector.debezium;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.embedded.EmbeddedEngine;
import io.debezium.engine.DebeziumEngine;
import io.debezium.relational.history.HistoryRecord;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.OperatorStateStore;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.calcite.shaded.com.google.common.collect.Maps;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.kafka.connect.source.SourceRecord;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

@Slf4j
public class DebeziumSource extends RichSourceFunction<ChangeRecord>
        implements CheckpointedFunction {

    private String namespace;
    private DebeziumEngine<SourceRecord> engine;

    private final Properties properties;

    protected transient volatile ListState<DebeziumOffset> offsetState;
    protected transient volatile ListState<HistoryRecord> historyState;
    protected volatile DebeziumOffset offset = new DebeziumOffset();

    private final ObjectMapper mapper = new ObjectMapper();

    public DebeziumSource(Properties properties) {
        namespace = properties.getProperty("name");
        this.properties = this.mergeDefaultProperties(properties);
    }

    @Override
    public void snapshotState(FunctionSnapshotContext functionSnapshotContext) throws Exception {
        if (offset.isEmpty()) {
            return;
        }
        offsetState.clear();
        offsetState.add(offset);

        historyState.clear();
        historyState.addAll(FlinkDatabaseHistory.historyRecords);
    }

    @Override
    public void initializeState(FunctionInitializationContext context) throws Exception {
        OperatorStateStore store = context.getOperatorStateStore();

        this.offsetState = store.getUnionListState(new ListStateDescriptor<>(
                "flink-connector-debezium-offset",
                TypeInformation.of(DebeziumOffset.class)));

        this.historyState = store.getUnionListState(new ListStateDescriptor<>(
                "flink-connector-debezium-history",
                TypeInformation.of(HistoryRecord.class)));

        if (!context.isRestored()) {
            return;
        }

        this.offsetState.get().forEach(o ->
                offset.update(o.getKey(), o.getValue())
        );
        this.historyState.get().forEach(o ->
                FlinkDatabaseHistory.historyRecords.add(o)
        );

        log.warn("restored from {}", offset);
    }

    @Override
    public void run(SourceContext<ChangeRecord> sourceContext) {
        // 强行更改成恢复模式
        this.recoveryModelAdapter();
        this.engine = EmbeddedEngine.create()
                .using(this.properties)
                .notifying(sourceRecord -> {
                    log.debug("receive a message {}", sourceRecord);
                    // update offset
                    this.UpdateOffset(sourceRecord);

                    ChangeRecord changeRecord = new ChangeRecord(sourceRecord);
                    if (!changeRecord.isSupportType()) {
                        return;
                    }

                    sourceContext.collectWithTimestamp(changeRecord, changeRecord.getTsMs());
                })
                .build();

        this.engine.run();

    }

    private void recoveryModelAdapter() {
        if (offset.isEmpty()) {
            return;
        }
        this.properties.put(FlinkOffsetBackingStore.FLINK_DEBEZIUM_OFFSET_KEY, new String(offset.getKey()));
        this.properties.put(FlinkOffsetBackingStore.FLINK_DEBEZIUM_OFFSET_VALUE, new String(offset.getValue()));
        this.properties.put("snapshot.mode", "schema_only_recovery");
    }

    @SneakyThrows
    private void UpdateOffset(SourceRecord sourceRecord) {
        if (Objects.isNull(sourceRecord.sourcePartition()) || Objects.isNull(sourceRecord.sourceOffset())) {
            // do nothing
            return;
        }
        Map<String, Object> map = Maps.newHashMap();
        map.put("schema", null);
        map.put("payload", Arrays.asList(namespace, sourceRecord.sourcePartition()));
        byte[] offsetKey = mapper.writeValueAsBytes(map);
        byte[] offsetValue = mapper.writeValueAsBytes(sourceRecord.sourceOffset());
        offset.update(offsetKey, offsetValue);
    }

    @SneakyThrows
    @Override
    public void cancel() {
        if (!Objects.isNull(this.engine)) {
            this.engine.close();
        }
    }

    private Properties mergeDefaultProperties(Properties properties) {
        Properties merge = new Properties();
        merge.putAll(properties);
        merge.putIfAbsent("offset.storage", FlinkOffsetBackingStore.class.getName());
        merge.putIfAbsent("include.schema.changes", false);
        merge.putIfAbsent("timezone.transfer.enabled", true);
        merge.putIfAbsent("snapshot.locking.mode", "none");
        return merge;
    }

}
