package com.cloudera.sparkts.api.java;

import com.cloudera.sparkts.*;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.mllib.linalg.DenseVector;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.linalg.distributed.IndexedRow;
import org.apache.spark.mllib.linalg.distributed.IndexedRowMatrix;
import org.apache.spark.mllib.linalg.distributed.RowMatrix;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.Row$;
import org.apache.spark.sql.SQLContext;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;
import scala.Tuple2;
import scala.Tuple3;
import scala.collection.JavaConversions;
import scala.collection.JavaConversions$;
import scala.reflect.ClassTag;
import scala.reflect.ClassTag$;
import scala.runtime.RichInt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class JavaTimeSeriesRDDFactoryTest {
    private double[] until(int a, int b) {
        return JavaConversions.asJavaCollection(new RichInt(a).until(b))
                .stream().mapToDouble(o -> new Double((int) o))
                .toArray();
    }

    private double[] untilBy(int a, int b, int step) {
        return JavaConversions.asJavaCollection(new RichInt(a).until(b).by(step))
                .stream().mapToDouble(o -> new Double((int) o))
                .toArray();
    }

    private Row rowFrom(Timestamp timestamp, double[] data) {
        List<Object> list = new ArrayList<>(data.length + 1);
        list.add(timestamp);
        for(double d: data) {
            list.add(d);
        }
        return Row$.MODULE$.fromSeq(JavaConversions$.MODULE$.asScalaBuffer(list).toSeq());
    }

    private <T> ClassTag<T> classTagOf(Class<T> clazz) {
        return ClassTag$.MODULE$.apply(clazz);
    }

    private JavaSparkContext init() {
        SparkConf conf = new SparkConf().setMaster("local").setAppName(getClass().getName());
        TimeSeriesKryoRegistrator.registerKryoClasses(conf);
        return new JavaSparkContext(conf);
    }

    @Test
    public void testSlice() {
        JavaSparkContext sc = init();

        DateTime start = new DateTime("2015-4-9");
        UniformDateTimeIndex index = DateTimeIndexFactory.uniform(start, 10, new DayFrequency(1));
        List<Tuple3<String, UniformDateTimeIndex, Vector>> list = new ArrayList<>();
        list.add(new Tuple3<>("0.0", index, new DenseVector(until(0, 10))));
        list.add(new Tuple3<>("10.0", index, new DenseVector(until(10, 20))));
        list.add(new Tuple3<>("20.0", index, new DenseVector(until(20, 30))));

        JavaTimeSeriesRDD<String> rdd = JavaTimeSeriesRDDFactory.javaTimeSeriesRDD(
                index, sc.parallelize(list));
        JavaTimeSeriesRDD<String> slice = rdd.slice(start.plusDays(1), start.plusDays(6));

        assertEquals(DateTimeIndexFactory.uniform(start.plusDays(1), 6, new DayFrequency(1)),
                slice.index());

        Map<String, Vector> contents = slice.collectAsMap();
        assertEquals(3, contents.size());
        assertEquals(new DenseVector(until(1, 7)), contents.get("0.0"));
        assertEquals(new DenseVector(until(11, 17)), contents.get("10.0"));
        assertEquals(new DenseVector(until(21, 27)), contents.get("20.0"));

        sc.close();
    }

    @Test
    public void testFilterEndingAfter() {
        JavaSparkContext sc = init();

        DateTime start = new DateTime("2015-4-9");
        UniformDateTimeIndex index = DateTimeIndexFactory.uniform(start, 10, new DayFrequency(1));
        List<Tuple3<String, UniformDateTimeIndex, Vector>> list = new ArrayList<>();
        list.add(new Tuple3<>("0.0", index, new DenseVector(until(0, 10))));
        list.add(new Tuple3<>("10.0", index, new DenseVector(until(10, 20))));
        list.add(new Tuple3<>("20.0", index, new DenseVector(until(20, 30))));

        JavaTimeSeriesRDD<String> rdd = JavaTimeSeriesRDDFactory.javaTimeSeriesRDD(
                index, sc.parallelize(list));
        assertEquals(3, rdd.filterEndingAfter(start).count());

        sc.close();
    }

    @Test
    public void testToInstants() {
        JavaSparkContext sc = init();

        DateTime start = new DateTime("2015-4-9");
        String[] labels = new String[]{ "a", "b", "c", "d", "e" };
        double[] seeds = untilBy(0, 20, 4);
        List<Tuple2<String, Vector>> list = new ArrayList<>();
        for(int i = 0; i < seeds.length; i++) {
            double seed = seeds[i];
            list.add(new Tuple2<>(labels[i], new DenseVector(until((int) seed, (int) seed + 4))));
        }
        UniformDateTimeIndex index = DateTimeIndexFactory.uniform(start, 4, new DayFrequency(1));

        JavaPairRDD<String, Vector> rdd = sc.parallelizePairs(list, 3);
        JavaTimeSeriesRDD<String> tsRdd = JavaTimeSeriesRDDFactory.javaTimeSeriesRDD(
                index, rdd);

        List<Tuple2<DateTime, Vector>> samples = tsRdd.toInstants().collect();
        assertEquals(
            Arrays.asList(new Tuple2<>(start, new DenseVector(untilBy(0, 20, 4))),
                    new Tuple2<>(start.plusDays(1), new DenseVector(untilBy(1, 20, 4))),
                    new Tuple2<>(start.plusDays(2), new DenseVector(untilBy(2, 20, 4))),
                    new Tuple2<>(start.plusDays(3), new DenseVector(untilBy(3, 20, 4)))),
                samples);

        sc.close();
    }

    @Test
    public void testToInstantsDataFrame() {
        JavaSparkContext sc = init();

        SQLContext sqlContext = new SQLContext(sc);

        DateTime start = new DateTime("2015-4-9");
        String[] labels = new String[]{ "a", "b", "c", "d", "e" };
        double[] seeds = untilBy(0, 20, 4);
        List<Tuple2<String, Vector>> list = new ArrayList<>();
        for(int i = 0; i < seeds.length; i++) {
            double seed = seeds[i];
            list.add(new Tuple2<>(labels[i], new DenseVector(until((int) seed, (int) seed + 4))));
        }
        UniformDateTimeIndex index = DateTimeIndexFactory.uniform(start, 4, new DayFrequency(1));

        JavaPairRDD<String, Vector> rdd = sc.parallelizePairs(list, 3);
        JavaTimeSeriesRDD<String> tsRdd = JavaTimeSeriesRDDFactory.javaTimeSeriesRDD(
                index, rdd);

        DataFrame samplesDF = tsRdd.toInstantsDataFrame(sqlContext);
        Row[] sampleRows = samplesDF.collect();
        String[] columnNames = samplesDF.columns();
        String[] columnNamesTail = new String[columnNames.length - 1];
        for(int i = 0; i < columnNamesTail.length; i++) {
            columnNamesTail[i] = columnNames[i + 1];
        }

        assertEquals(labels.length + 1 /*labels + timestamp*/, columnNames.length);
        assertEquals("instant", columnNames[0]);
        assertArrayEquals(labels, columnNamesTail);

        assertArrayEquals(new Row[] {
                rowFrom(new Timestamp(start.getMillis()), untilBy(0, 20, 4)),
                rowFrom(new Timestamp(start.plusDays(1).getMillis()), untilBy(1, 20, 4)),
                rowFrom(new Timestamp(start.plusDays(2).getMillis()), untilBy(2, 20, 4)),
                rowFrom(new Timestamp(start.plusDays(3).getMillis()), untilBy(3, 20, 4))
        }, sampleRows);

        sc.close();
    }

    @Test
    public void testSaveLoad() {
        JavaSparkContext sc = init();

        DateTime start = new DateTime("2015-4-9");
        UniformDateTimeIndex index = DateTimeIndexFactory.uniform(start, 10, new DayFrequency(1));
        List<Tuple3<String, UniformDateTimeIndex, Vector>> list = new ArrayList<>();
        list.add(new Tuple3<>("0.0", index, new DenseVector(until(0, 10))));
        list.add(new Tuple3<>("10.0", index, new DenseVector(until(10, 20))));
        list.add(new Tuple3<>("20.0", index, new DenseVector(until(20, 30))));

        JavaTimeSeriesRDD<String> rdd = JavaTimeSeriesRDDFactory.javaTimeSeriesRDD(
                index, sc.parallelize(list));

        Path tempDir = null;

        try {
            tempDir = Files.createTempDirectory("saveload");
            Files.deleteIfExists(tempDir);
            String path = tempDir.toAbsolutePath().toString();
            rdd.saveAsCsv(path);
            JavaTimeSeriesRDD<String> loaded = JavaTimeSeriesRDDFactory
                    .javaTimeSeriesRDDFromCsv(path, sc);
            assertEquals(rdd.index(), loaded.index());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (tempDir != null) {
                try {
                    Files.list(tempDir).forEach((path) -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    Files.deleteIfExists(tempDir);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            sc.close();
        }
    }

    @Test
    public void testToIndexedRowMatrix() {
        JavaSparkContext sc = init();

        DateTime start = new DateTime("2015-4-9");
        String[] labels = new String[]{ "a", "b", "c", "d", "e" };
        double[] seeds = untilBy(0, 20, 4);
        List<Tuple2<String, Vector>> list = new ArrayList<>();
        for(int i = 0; i < seeds.length; i++) {
            double seed = seeds[i];
            list.add(new Tuple2<>(labels[i], new DenseVector(until((int) seed, (int) seed + 4))));
        }
        UniformDateTimeIndex index = DateTimeIndexFactory.uniform(start, 4, new DayFrequency(1));

        JavaPairRDD<String, Vector> rdd = sc.parallelizePairs(list, 3);
        JavaTimeSeriesRDD<String> tsRdd = JavaTimeSeriesRDDFactory.javaTimeSeriesRDD(
                index, rdd);

        IndexedRowMatrix indexedMatrix = tsRdd.toIndexedRowMatrix();
        JavaPairRDD<Long, double[]> indeciesDataRDD =
                new JavaRDD<>(indexedMatrix.rows(), classTagOf(IndexedRow.class))
                .mapToPair(ir -> new Tuple2<>(ir.index(), ir.vector().toArray()));
        List<double[]> rowData = indeciesDataRDD.values().collect();
        Long[] rowIndices = indeciesDataRDD.keys().collect().toArray(new Long[0]);

        assertArrayEquals(Arrays.asList(untilBy(0, 20, 4),
                untilBy(1, 20, 4),
                untilBy(2, 20, 4),
                untilBy(3, 20, 4)).toArray(),
                rowData.toArray());

        assertArrayEquals(new Long[]{ 0l, 1l, 2l, 3l }, rowIndices);

        sc.close();
    }

    @Test
    public void testToRowMatrix() {
        JavaSparkContext sc = init();

        DateTime start = new DateTime("2015-4-9");
        String[] labels = new String[]{ "a", "b", "c", "d", "e" };
        double[] seeds = untilBy(0, 20, 4);
        List<Tuple2<String, Vector>> list = new ArrayList<>();
        for(int i = 0; i < seeds.length; i++) {
            double seed = seeds[i];
            list.add(new Tuple2<>(labels[i], new DenseVector(until((int) seed, (int) seed + 4))));
        }
        UniformDateTimeIndex index = DateTimeIndexFactory.uniform(start, 4, new DayFrequency(1));

        JavaPairRDD<String, Vector> rdd = sc.parallelizePairs(list, 3);
        JavaTimeSeriesRDD<String> tsRdd = JavaTimeSeriesRDDFactory.javaTimeSeriesRDD(
                index, rdd);

        RowMatrix matrix = tsRdd.toRowMatrix();
        List<double[]> rowData = new JavaRDD<>(matrix.rows(), classTagOf(Vector.class))
                .map(v -> v.toArray()).collect();

        assertArrayEquals(Arrays.asList(untilBy(0, 20, 4),
                        untilBy(1, 20, 4),
                        untilBy(2, 20, 4),
                        untilBy(3, 20, 4)).toArray(),
                rowData.toArray());

        sc.close();
    }

    @Test
    public void testTimeSeriesRDDFromObservationsDataFrame() {
        JavaSparkContext sc = init();

        SQLContext sqlContext = new SQLContext(sc);

        DateTime start = new DateTime("2015-4-9", DateTimeZone.UTC);
        String[] labels = new String[]{ "a", "b", "c", "d", "e" };
        double[] seeds = untilBy(0, 20, 4);
        List<Tuple2<String, Vector>> list = new ArrayList<>();
        for(int i = 0; i < seeds.length; i++) {
            double seed = seeds[i];
            list.add(new Tuple2<>(labels[i], new DenseVector(until((int) seed, (int) seed + 4))));
        }
        UniformDateTimeIndex index = DateTimeIndexFactory.uniform(start, 4, new DayFrequency(1));

        JavaPairRDD<String, Vector> rdd = sc.parallelizePairs(list, 3);
        JavaTimeSeriesRDD<String> tsRdd = JavaTimeSeriesRDDFactory.javaTimeSeriesRDD(
                index, rdd);

        DataFrame obsDF = tsRdd.toObservationsDataFrame(sqlContext, "timestamp", "key", "value");
        JavaTimeSeriesRDD<String> tsRddFromDF = JavaTimeSeriesRDDFactory.javaTimeSeriesRDDFromObservations(
                index, obsDF, "timestamp", "key", "value");

        assertArrayEquals(
                tsRdd.collect().stream()
                        .sorted((kv1, kv2) -> kv1._1().compareTo(kv2._1()))
                        .collect(Collectors.toList()).toArray(),
                tsRddFromDF.collect().stream()
                        .sorted((kv1, kv2) -> kv1._1().compareTo(kv2._1()))
                        .collect(Collectors.toList()).toArray()
        );

        Row[] df1 = obsDF.collect();
        Row[] df2 = tsRddFromDF.toObservationsDataFrame(sqlContext, "timestamp", "key", "value").collect();

        Comparator<Row> comparator = (r1, r2) -> {
            int c = 0;
            c = r1.<Double>getAs(2).compareTo(r2.<Double>getAs(2));
            if(c == 0) {
                c = r1.<String>getAs(1).compareTo(r2.<String>getAs(1));
                if(c == 0) {
                    c = r1.<Timestamp>getAs(0).compareTo(r2.<Timestamp>getAs(0));
                    return c;
                } else return c;
            } else return c;
        };

        Arrays.sort(df1, comparator);
        Arrays.sort(df2, comparator);

        assertEquals(df1.length, df2.length);
        assertArrayEquals(df1, df2);

        sc.close();
    }

    @Test
    public void testRemoveInstantsWithNaNs() {
        JavaSparkContext sc = init();

        DateTime start = new DateTime("2015-4-9");
        UniformDateTimeIndex index = DateTimeIndexFactory.uniform(start, 4, new DayFrequency(1));
        List<Tuple3<String, UniformDateTimeIndex, Vector>> list = new ArrayList<>();
        list.add(new Tuple3<>("1.0", index, new DenseVector(until(1, 5))));
        list.add(new Tuple3<>("5.0", index, new DenseVector(new double[]{ 5d, Double.NaN, 7d, 8d })));
        list.add(new Tuple3<>("9.0", index, new DenseVector(new double[]{ 9d, 10d, 11d, Double.NaN })));

        JavaTimeSeriesRDD<String> rdd = JavaTimeSeriesRDDFactory.javaTimeSeriesRDD(
                index, sc.parallelize(list));

        JavaTimeSeriesRDD<String> rdd2 = rdd.removeInstantsWithNaNs();

        assertEquals(DateTimeIndexFactory.irregular(new DateTime[] { new DateTime("2015-4-9"), new DateTime("2015-4-11") }),
                rdd2.index());

        assertArrayEquals(new Vector[]{ new DenseVector(new double[] { 1.0, 3.0 }),
                new DenseVector(new double[] { 5.0, 7.0 }),
                new DenseVector(new double[] { 9.0, 11.0 }) },
                rdd2.values().collect().toArray());

        sc.close();
    }
}
