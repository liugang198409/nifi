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

import groovy.xml.MarkupBuilder
import org.apache.nifi.controller.AbstractControllerService
import org.apache.nifi.flowfile.FlowFile
import org.apache.nifi.logging.ComponentLog
import org.apache.nifi.schema.access.SchemaNotFoundException
import org.apache.nifi.serialization.RecordSetWriter
import org.apache.nifi.serialization.RecordSetWriterFactory
import org.apache.nifi.serialization.WriteResult
import org.apache.nifi.serialization.record.Record
import org.apache.nifi.serialization.record.RecordSet
import org.apache.nifi.stream.io.NonCloseableOutputStream


class GroovyRecordSetWriter implements RecordSetWriter {

    @Override
    WriteResult write(Record r, OutputStream out) throws IOException {
        new OutputStreamWriter(new NonCloseableOutputStream(out)).with {osw ->
            new MarkupBuilder(osw).record {
                r.schema.fieldNames.each {fieldName ->
                    "$fieldName" r.getValue(fieldName)
                }
            }
        }
        WriteResult.of(0, [:])
    }

    @Override
    String getMimeType() {
        return 'application/xml'
    }

    @Override
    WriteResult write(final RecordSet rs, final OutputStream rawOut) throws IOException {
        int count = 0

        new OutputStreamWriter(new NonCloseableOutputStream(rawOut)).with {osw ->
            new MarkupBuilder(osw).recordSet {

                Record r
                while (r = rs.next()) {
                    count++

                    record {
                        rs.schema.fieldNames.each {fieldName ->
                            "$fieldName" r.getValue(fieldName)
                        }
                    }
                }
            }
        }
        WriteResult.of(count, [:])
    }
}

class GroovyRecordSetWriterFactory extends AbstractControllerService implements RecordSetWriterFactory {

    @Override
    RecordSetWriter createWriter(ComponentLog logger, FlowFile flowFile, InputStream flowFileContent) throws SchemaNotFoundException, IOException {
        return new GroovyRecordSetWriter()
    }
}

writer = new GroovyRecordSetWriterFactory()
