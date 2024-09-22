/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.orc;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.orc.geometry.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;

import java.io.File;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 *
 */
public class TestOrcGeometry {
    Path workDir = new Path(System.getProperty("test.tmp.dir",
            "target" + File.separator + "test" + File.separator + "tmp"));
    Configuration conf;
    FileSystem fs;
    Path testFilePath;

    public TestOrcGeometry() {
    }

    @BeforeEach
    public void openFileSystem(TestInfo testInfo) throws Exception {
        conf = new Configuration();
        fs = FileSystem.getLocal(conf);
        testFilePath = new Path(workDir, "TestOrcGeometry." +
                testInfo.getTestMethod().get().getName() + ".orc");
        fs.delete(testFilePath, false);
    }

    @Test
    public void testTimestampWriter() throws Exception {
        TypeDescription schema = TypeDescription.createGeometry()
                .withGeometryType(new TypeDescription.GeometryType(
                        TypeDescription.GeometryType.GeometryEncoding.WKB,
                        TypeDescription.GeometryType.Edges.PLANNER,
                        "EPSG:4326",
                        "PROJJSON",
                        null));
        Writer writer = OrcFile.createWriter(testFilePath,
                OrcFile.writerOptions(conf).setSchema(schema).stripeSize(100000)
                        .bufferSize(10000));
        GeometryFactory geometryFactory = new GeometryFactory();
        WKBWriter wkbWriter = new WKBWriter();
        WKBReader wkbReader = new WKBReader();

        VectorizedRowBatch batch = schema.createRowBatch();
        BytesColumnVector geos = (BytesColumnVector) batch.cols[0];
        long sum  = 0;
        for (int i = 0; i < 100; i++) {
            byte[] bytes = wkbWriter.write(geometryFactory.createPoint(new Coordinate(i, i)));
            sum += bytes.length;
            geos.setVal(batch.size++, bytes);
        }
        writer.addRowBatch(batch);
        writer.close();

        Reader reader = OrcFile.createReader(testFilePath, OrcFile.readerOptions(conf).filesystem(fs));
        RecordReader rows = reader.rows();
        batch = reader.getSchema().createRowBatch();
        geos = (BytesColumnVector) batch.cols[0];
        int idx = 0;
        while (rows.nextBatch(batch)) {
            for(int r=0; r < batch.size; ++r) {
                Geometry geom = wkbReader.read(Arrays.copyOfRange(geos.vector[r], geos.start[r], geos.start[r] + geos.length[r]));
                assertEquals(geom.getGeometryType(), "Point");
                assertEquals(geom, geometryFactory.createPoint(new Coordinate(idx, idx)));
                idx += 1;
            }
        }
        rows.close();
        ColumnStatistics[] statistics = reader.getStatistics();
        GeometryColumnStatistics geometryColumnStatistics = (GeometryColumnStatistics) statistics[0];
        BoundingBox boundingBox = geometryColumnStatistics.getBoundingBox();
        assertEquals(boundingBox.getXMin(), 0.0);
        assertEquals(boundingBox.getYMin(), 0.0);
        assertEquals(boundingBox.getXMax(), 99.0);
        assertEquals(boundingBox.getYMax(), 99.0);
    }
}
