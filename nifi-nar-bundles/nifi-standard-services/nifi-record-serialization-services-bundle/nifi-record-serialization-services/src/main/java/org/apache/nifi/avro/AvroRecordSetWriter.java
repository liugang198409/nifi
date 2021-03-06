/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.avro;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.avro.Schema;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.schema.access.SchemaField;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.RecordSetWriter;
import org.apache.nifi.serialization.RecordSetWriterFactory;
import org.apache.nifi.serialization.SchemaRegistryRecordSetWriter;
import org.apache.nifi.serialization.record.RecordSchema;

@Tags({"avro", "result", "set", "writer", "serializer", "record", "recordset", "row"})
@CapabilityDescription("Writes the contents of a RecordSet in Binary Avro format.")
public class AvroRecordSetWriter extends SchemaRegistryRecordSetWriter implements RecordSetWriterFactory {
    private static final Set<SchemaField> requiredSchemaFields = EnumSet.of(SchemaField.SCHEMA_TEXT, SchemaField.SCHEMA_TEXT_FORMAT);
    private static final int MAX_AVRO_SCHEMA_CACHE_SIZE = 20;

    private final Map<String, Schema> compiledAvroSchemaCache = new LinkedHashMap<String, Schema>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Schema> eldest) {
            return size() >= MAX_AVRO_SCHEMA_CACHE_SIZE;
        }
    };

    static final AllowableValue AVRO_EMBEDDED = new AllowableValue("avro-embedded", "Embed Avro Schema",
        "The FlowFile will have the Avro schema embedded into the content, as is typical with Avro");

    @Override
    public RecordSetWriter createWriter(final ComponentLog logger, final FlowFile flowFile, final InputStream in) throws IOException {
        final String strategyValue = getConfigurationContext().getProperty(SCHEMA_WRITE_STRATEGY).getValue();

        try {
            final RecordSchema recordSchema = getSchema(flowFile, in);

            final Schema avroSchema;
            try {
                if (recordSchema.getSchemaFormat().isPresent() & recordSchema.getSchemaFormat().get().equals(AvroTypeUtil.AVRO_SCHEMA_FORMAT)) {
                    final Optional<String> textOption = recordSchema.getSchemaText();
                    if (textOption.isPresent()) {
                        avroSchema = compileAvroSchema(textOption.get());
                    } else {
                        avroSchema = AvroTypeUtil.extractAvroSchema(recordSchema);
                    }
                } else {
                    avroSchema = AvroTypeUtil.extractAvroSchema(recordSchema);
                }
            } catch (final Exception e) {
                throw new SchemaNotFoundException("Failed to compile Avro Schema", e);
            }

            if (AVRO_EMBEDDED.getValue().equals(strategyValue)) {
                return new WriteAvroResultWithSchema(avroSchema);
            } else {
                return new WriteAvroResultWithExternalSchema(avroSchema, recordSchema, getSchemaAccessWriter(recordSchema));
            }
        } catch (final SchemaNotFoundException e) {
            throw new ProcessException("Could not determine the Avro Schema to use for writing the content", e);
        }
    }


    private Schema compileAvroSchema(final String text) {
        // Access to the LinkedHashMap must be done while synchronized on this.
        // However, if no compiled schema exists, we don't want to remain synchronized
        // while we compile it, as compilation can be expensive. As a result, if there is
        // not a compiled schema already, we will compile it outside of the synchronized
        // block, and then re-synchronize to update the map. All of this is functionally
        // equivalent to calling compiledAvroSchema.computeIfAbsent(text, t -> new Schema.Parser().parse(t));
        // but does so without synchronizing when not necessary.
        Schema compiled;
        synchronized (this) {
            compiled = compiledAvroSchemaCache.get(text);
        }

        if (compiled != null) {
            return compiled;
        }

        final Schema newlyCompiled = new Schema.Parser().parse(text);
        synchronized (this) {
            return compiledAvroSchemaCache.computeIfAbsent(text, t -> newlyCompiled);
        }
    }

    @Override
    protected List<AllowableValue> getSchemaWriteStrategyValues() {
        final List<AllowableValue> allowableValues = new ArrayList<>();
        allowableValues.add(AVRO_EMBEDDED);
        allowableValues.addAll(super.getSchemaWriteStrategyValues());
        return allowableValues;
    }

    @Override
    protected AllowableValue getDefaultSchemaWriteStrategy() {
        return AVRO_EMBEDDED;
    }

    @Override
    protected Set<SchemaField> getRequiredSchemaFields(final ValidationContext validationContext) {
        final String writeStrategyValue = validationContext.getProperty(SCHEMA_WRITE_STRATEGY).getValue();
        if (writeStrategyValue.equalsIgnoreCase(AVRO_EMBEDDED.getValue())) {
            return requiredSchemaFields;
        }

        return super.getRequiredSchemaFields(validationContext);
    }
}
