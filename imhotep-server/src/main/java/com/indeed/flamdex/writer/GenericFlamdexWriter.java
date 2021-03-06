/*
 * Copyright (C) 2014 Indeed Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
 package com.indeed.flamdex.writer;

import com.indeed.flamdex.api.DocIdStream;
import com.indeed.flamdex.reader.FlamdexMetadata;
import com.indeed.flamdex.api.FlamdexReader;
import com.indeed.flamdex.api.IntTermIterator;
import com.indeed.flamdex.api.StringTermIterator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author jplaisance
 */
public final class GenericFlamdexWriter implements FlamdexWriter {

    private static final int DOC_ID_BUFFER_SIZE = 32;

    private final String outputDirectory;

    private final IntFieldWriterFactory intFieldWriterFactory;

    private final StringFieldWriterFactory stringFieldWriterFactory;

    private long maxDocs;

    private final int formatVersion;

    private final Set<String> intFields;
    private final Set<String> stringFields;

    public GenericFlamdexWriter(String outputDirectory, IntFieldWriterFactory intFieldWriterFactory, StringFieldWriterFactory stringFieldWriterFactory, long numDocs, int formatVersion) throws IOException {
        this(outputDirectory, intFieldWriterFactory, stringFieldWriterFactory, numDocs, formatVersion, true);
    }

    public GenericFlamdexWriter(String outputDirectory, IntFieldWriterFactory intFieldWriterFactory, StringFieldWriterFactory stringFieldWriterFactory, long numDocs, int formatVersion, boolean create) throws IOException {
        this.outputDirectory = outputDirectory;
        this.intFieldWriterFactory = intFieldWriterFactory;
        this.stringFieldWriterFactory = stringFieldWriterFactory;
        this.maxDocs = numDocs;
        this.formatVersion = formatVersion;
        if (create) {
            intFields = new HashSet<String>();
            stringFields = new HashSet<String>();
        } else {
            final FlamdexMetadata metadata = FlamdexMetadata.readMetadata(outputDirectory);
            if (metadata.numDocs != numDocs) {
                throw new IllegalArgumentException("numDocs does not match numDocs in existing index");
            }
            intFields = new HashSet<String>(metadata.intFields);
            stringFields = new HashSet<String>(metadata.stringFields);
        }
    }

    @Override
    public IntFieldWriter getIntFieldWriter(String field) throws IOException {
        return getIntFieldWriter(field, false);
    }

    public IntFieldWriter getIntFieldWriter(String field, boolean blowAway) throws IOException {
        if (!blowAway && intFields.contains(field)) {
            throw new IllegalArgumentException("already added int field "+field);
        }
        intFields.add(field);
        return intFieldWriterFactory.create(outputDirectory, field, maxDocs);
    }

    @Override
    public StringFieldWriter getStringFieldWriter(String field) throws IOException {
        return getStringFieldWriter(field, false);
    }

    public StringFieldWriter getStringFieldWriter(String field, boolean blowAway) throws IOException {
        if (!blowAway && stringFields.contains(field)) {
            throw new IllegalArgumentException("already added string field "+field);
        }
        stringFields.add(field);
        return stringFieldWriterFactory.create(outputDirectory, field, maxDocs);
    }

    @Override
    public String getOutputDirectory() {
        return this.outputDirectory;
    }
    
    @Override
    public void resetMaxDocs(long numDocs) {
        this.maxDocs = numDocs;
    }

    @Override
    public void close() throws IOException {
        final List<String> intFieldsList = new ArrayList<String>(intFields);
        Collections.sort(intFieldsList);

        final List<String> stringFieldsList = new ArrayList<String>(stringFields);
        Collections.sort(stringFieldsList);

        FlamdexMetadata metadata = new FlamdexMetadata((int)maxDocs, intFieldsList, stringFieldsList, formatVersion);
        FlamdexMetadata.writeMetadata(outputDirectory, metadata);
    }

    public static void writeFlamdex(final String indexDir, final FlamdexReader fdx, final IntFieldWriterFactory intFieldWriterFactory, final StringFieldWriterFactory stringFieldWriterFactory, int formatVersion,
                                    final List<String> intFields, final List<String> stringFields) throws IOException {
        final DocIdStream dis = fdx.getDocIdStream();
        final int[] docIdBuf = new int[DOC_ID_BUFFER_SIZE];

        final GenericFlamdexWriter w = new GenericFlamdexWriter(indexDir, intFieldWriterFactory, stringFieldWriterFactory, fdx.getNumDocs(), formatVersion);

        for (final String intField : intFields) {
            final IntFieldWriter ifw = w.getIntFieldWriter(intField);
            final IntTermIterator iter = fdx.getIntTermIterator(intField);
            while (iter.next()) {
                ifw.nextTerm(iter.term());
                dis.reset(iter);
                while (true) {
                    final int n = dis.fillDocIdBuffer(docIdBuf);
                    for (int i = 0; i < n; ++i) {
                        ifw.nextDoc(docIdBuf[i]);
                    }
                    if (n < docIdBuf.length) break;
                }
            }
            iter.close();
            ifw.close();
        }

        for (final String stringField : stringFields) {
            final StringFieldWriter sfw = w.getStringFieldWriter(stringField);
            final StringTermIterator iter = fdx.getStringTermIterator(stringField);
            while (iter.next()) {
                sfw.nextTerm(iter.term());
                dis.reset(iter);
                while (true) {
                    final int n = dis.fillDocIdBuffer(docIdBuf);
                    for (int i = 0; i < n; ++i) {
                        sfw.nextDoc(docIdBuf[i]);
                    }
                    if (n < docIdBuf.length) break;
                }
            }
            sfw.close();
        }

        dis.close();
        w.close();
    }
}
