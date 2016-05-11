/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/
package quarks.test.topology;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Ignore;
import org.junit.Test;

import com.google.gson.JsonObject;

import quarks.function.BiFunction;
import quarks.function.Function;
import quarks.function.Functions;
import quarks.function.ToIntFunction;
import quarks.topology.TStream;
import quarks.topology.Topology;
import quarks.topology.plumbing.PlumbingStreams;
import quarks.topology.plumbing.StreamScope;
import quarks.topology.plumbing.StreamScope.Sample;
import quarks.topology.plumbing.StreamScopeRegistry;
import quarks.topology.plumbing.Valve;
import quarks.topology.tester.Condition;

@Ignore
public abstract class PlumbingTest extends TopologyAbstractTest {
	

	@Test
    public void testBlockingDelay() throws Exception {
		// Timing variances on shared machines can cause this test to fail
		assumeTrue(!Boolean.getBoolean("quarks.build.ci"));

        Topology topology = newTopology();
        
        TStream<String> strings = topology.strings("a", "b", "c", "d");
        
        TStream<Long> starts = strings.map(v -> System.currentTimeMillis());
        
        // delay stream
        starts = PlumbingStreams.blockingDelay(starts, 300, TimeUnit.MILLISECONDS);
        
        // calculate display
        starts = starts.modify(v -> System.currentTimeMillis() - v);
        
        starts = starts.filter(v -> v >= 300);
        
        Condition<Long> tc = topology.getTester().tupleCount(starts, 4);
        complete(topology, tc);
        assertTrue("valid:" + tc.getResult(), tc.valid());
    }

    @Test
    public void testBlockingThrottle() throws Exception {
		// Timing variances on shared machines can cause this test to fail
    	assumeTrue(!Boolean.getBoolean("quarks.build.ci"));

        Topology topology = newTopology();
        
        TStream<String> strings = topology.strings("a", "b", "c", "d");

        TStream<Long> emittedDelays = strings.map(v -> 0L);
        
        // throttle stream
        long[] lastEmittedTimestamp = { 0 };
        emittedDelays = PlumbingStreams.blockingThrottle(emittedDelays, 300, TimeUnit.MILLISECONDS)
                .map(t -> {
                    // compute the delay since the last emitted tuple
                    long now = System.currentTimeMillis();
                    if (lastEmittedTimestamp[0] == 0)
                        lastEmittedTimestamp[0] = now;
                    t = now - lastEmittedTimestamp[0];
                    lastEmittedTimestamp[0] = now;
                    // System.out.println("### "+t);
                    return t;
                    })
                .map(t -> {
                    // simulate 200ms downstream processing delay
                    try {
                        Thread.sleep(TimeUnit.MILLISECONDS.toMillis(200));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    } return t;
                    }) ;

        // should end up with throttled delays close to 300 (not 500 like
        // a blockingDelay() under these same conditions would yield)
        emittedDelays = emittedDelays.filter(v -> v <= 320);
        
        Condition<Long> tc = topology.getTester().tupleCount(emittedDelays, 4);
        complete(topology, tc);
        assertTrue("valid:" + tc.getResult(), tc.valid());
    }

    @Test
    public void testOneShotDelay() throws Exception {

        Topology topology = newTopology();
        
        TStream<String> strings = topology.strings("a", "b", "c", "d");
        
        TStream<Long> starts = strings.map(v -> System.currentTimeMillis());
        
        // delay stream
        starts = PlumbingStreams.blockingOneShotDelay(starts, 300, TimeUnit.MILLISECONDS);
        
        // calculate display
        starts = starts.modify(v -> System.currentTimeMillis() - v);
        
        // the first tuple shouldn't satisfy the predicate
        starts = starts.filter(v -> v < 300);
        
        Condition<Long> tc = topology.getTester().tupleCount(starts, 3);
        complete(topology, tc);
        assertTrue("valid:" + tc.getResult(), tc.valid());
    }

    public static class TimeAndId {
    	private static AtomicInteger ids = new AtomicInteger();
    	long ms;
    	final int id;
    	
    	public TimeAndId() {
    		this.ms = System.currentTimeMillis();
    		this.id = ids.incrementAndGet();
    	}
    	public TimeAndId(TimeAndId tai) {
    		this.ms = System.currentTimeMillis() - tai.ms;
    		this.id = tai.id;
    	}
    	@Override
    	public String toString() {
    		return "TAI:" + id + "@" + ms;
    	}
    	
    }
    
    @Test
    public void testPressureReliever() throws Exception {
		// Timing variances on shared machines can cause this test to fail
		assumeTrue(!Boolean.getBoolean("quarks.build.ci"));

        Topology topology = newTopology();
        
        TStream<TimeAndId> raw = topology.poll(() -> new TimeAndId(), 10, TimeUnit.MILLISECONDS);
           
        
        TStream<TimeAndId> pr = PlumbingStreams.pressureReliever(raw, Functions.unpartitioned(), 5);
        
        // insert a blocking delay acting as downstream operator that cannot keep up
        TStream<TimeAndId> slow = PlumbingStreams.blockingDelay(pr, 200, TimeUnit.MILLISECONDS);
        
        // calculate the delay
        TStream<TimeAndId> slowM = slow.modify(v -> new TimeAndId(v));
        
        // Also process raw that should be unaffected by the slow path
        TStream<String> processed = raw.asString();
        
        
        Condition<Long> tcSlowCount = topology.getTester().atLeastTupleCount(slow, 20);
        Condition<List<TimeAndId>> tcRaw = topology.getTester().streamContents(raw);
        Condition<List<TimeAndId>> tcSlow = topology.getTester().streamContents(slow);
        Condition<List<TimeAndId>> tcSlowM = topology.getTester().streamContents(slowM);
        Condition<List<String>> tcProcessed = topology.getTester().streamContents(processed);
        complete(topology, tcSlowCount);
        
        assertTrue(tcProcessed.getResult().size() > tcSlowM.getResult().size());
        for (TimeAndId delay : tcSlowM.getResult())
            assertTrue(delay.ms < 300);

        // Must not lose any tuples in the non relieving path
        Set<TimeAndId> uniq = new HashSet<>(tcRaw.getResult());
        assertEquals(tcRaw.getResult().size(), uniq.size());

        // Must not lose any tuples in the non relieving path
        Set<String> uniqProcessed = new HashSet<>(tcProcessed.getResult());
        assertEquals(tcProcessed.getResult().size(), uniqProcessed.size());
        
        assertEquals(uniq.size(), uniqProcessed.size());
           
        // Might lose tuples, but must not have send duplicates
        uniq = new HashSet<>(tcSlow.getResult());
        assertEquals(tcSlow.getResult().size(), uniq.size());
    }
    
    @Test
    public void testPressureRelieverWithInitialDelay() throws Exception {

        Topology topology = newTopology();
        
        
        TStream<String> raw = topology.strings("A", "B", "C", "D", "E", "F", "G", "H");
        
        TStream<String> pr = PlumbingStreams.pressureReliever(raw, v -> 0, 100);
        
        TStream<String> pr2 = PlumbingStreams.blockingOneShotDelay(pr, 5, TimeUnit.SECONDS);
        
        Condition<Long> tcCount = topology.getTester().tupleCount(pr2, 8);
        Condition<List<String>> contents = topology.getTester().streamContents(pr2, "A", "B", "C", "D", "E", "F", "G", "H");
        complete(topology, tcCount);
        
        assertTrue(tcCount.valid());
        assertTrue(contents.valid());
    }
    
    @Test
    public void testValveState() throws Exception {
        Valve<Integer> valve = new Valve<>();
        assertTrue(valve.isOpen());
        
        valve = new Valve<>(true);
        assertTrue(valve.isOpen());
        
        valve = new Valve<>(false);
        assertFalse(valve.isOpen());
        
        valve.setOpen(true);
        assertTrue(valve.isOpen());
        
        valve.setOpen(false);
        assertFalse(valve.isOpen());
    }
    
    @Test
    public void testValveInitiallyOpen() throws Exception {
        Topology top = newTopology("testValve");

        TStream<Integer> values = top.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        
        Valve<Integer> valve = new Valve<>();
        AtomicInteger cnt = new AtomicInteger();
        TStream<Integer> filtered = values
                                    .peek(tuple -> {
                                        // reject 4,5,6
                                        int curCnt = cnt.incrementAndGet();
                                        if (curCnt > 6)
                                            valve.setOpen(true);
                                        else if (curCnt > 3)
                                            valve.setOpen(false);
                                        })
                                    .filter(valve);

        Condition<Long> count = top.getTester().tupleCount(filtered, 7);
        Condition<List<Integer>> contents = top.getTester().streamContents(filtered, 1,2,3,7,8,9,10 );
        complete(top, count);
        assertTrue(contents.getResult().toString(), contents.valid());
    }
    
    @Test
    public void testValveInitiallyClosed() throws Exception {
        Topology top = newTopology("testValve");
        
        TStream<Integer> values = top.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        
        Valve<Integer> valve = new Valve<>(false);
        
        AtomicInteger cnt = new AtomicInteger();
        TStream<Integer> filtered = values
                                    .peek(tuple -> {
                                        // reject all but 4,5,6
                                        int curCnt = cnt.incrementAndGet();
                                        if (curCnt > 6)
                                            valve.setOpen(false);
                                        else if (curCnt > 3)
                                            valve.setOpen(true);
                                        })
                                    .filter(valve);

        Condition<Long> count = top.getTester().tupleCount(filtered, 3);
        Condition<List<Integer>> contents = top.getTester().streamContents(filtered, 4,5,6 );
        complete(top, count);
        assertTrue(contents.getResult().toString(), contents.valid());
    }
    
    private Function<Integer,JsonObject> fakeAnalytic(int channel, long period, TimeUnit unit) {
      return value -> { 
        try {
          Thread.sleep(unit.toMillis(period));
          JsonObject jo = new JsonObject();
          jo.addProperty("channel", channel);
          jo.addProperty("result", value);
          return jo;
        } catch (InterruptedException e) {
          throw new RuntimeException("channel="+channel+" interrupted", e);
        }
      };
    }

    private Function<TStream<Integer>,TStream<JsonObject>> fakePipeline(int channel, long period, TimeUnit unit) {
      return stream -> stream.map(fakeAnalytic(channel, period, unit)).filter(t->true).tag("pipeline-ch"+channel);
    }
    
    @Test
    public void testConcurrentMap() throws Exception {
        Topology top = newTopology("testConcurrentMap");
        
        int ch = 0;
        List<Function<Integer,JsonObject>> mappers = new ArrayList<>();
        mappers.add(fakeAnalytic(ch++, 100, TimeUnit.MILLISECONDS));
        mappers.add(fakeAnalytic(ch++, 100, TimeUnit.MILLISECONDS));
        mappers.add(fakeAnalytic(ch++, 100, TimeUnit.MILLISECONDS));
        mappers.add(fakeAnalytic(ch++, 100, TimeUnit.MILLISECONDS));
        mappers.add(fakeAnalytic(ch++, 100, TimeUnit.MILLISECONDS));
        mappers.add(fakeAnalytic(ch++, 100, TimeUnit.MILLISECONDS));
        // a couple much faster just in case something's amiss with queues
        mappers.add(fakeAnalytic(ch++, 3, TimeUnit.MILLISECONDS));
        mappers.add(fakeAnalytic(ch++, 13, TimeUnit.MILLISECONDS));
        
        Function<List<JsonObject>,Integer> combiner = list -> {
            int sum = 0;
            int cnt = 0;
            System.out.println("combiner: "+list);
            for(JsonObject jo : list) {
              assertEquals(cnt++, jo.get("channel").getAsInt());
              sum += jo.get("result").getAsInt();
            }
            return sum;
        };

        TStream<Integer> values = top.of(1, 2, 3);
        Integer[] resultTuples = new Integer[]{
            1*mappers.size(),
            2*mappers.size(),
            3*mappers.size(),
        };
        
        TStream<Integer> result = PlumbingStreams.concurrentMap(values, mappers, combiner);
        
        Condition<Long> count = top.getTester().tupleCount(result, 3);
        Condition<List<Integer>> contents = top.getTester().streamContents(result, resultTuples );

        long begin = System.currentTimeMillis();
        complete(top, count);
        long end = System.currentTimeMillis();

        assertTrue(contents.getResult().toString(), contents.valid());
        
        long actDuration = end - begin;
        long expMinSerialDuration = resultTuples.length * mappers.size() * 100;
        long expMinDuration = resultTuples.length * 100;
        
        System.out.println(top.getName()+" expMinDuration="+expMinDuration+" actDuration="+actDuration+" expMinSerialDuration="+expMinSerialDuration);
        
        // a gross level performance check w/concurrent channels
        if (Boolean.getBoolean("quarks.build.ci"))
          System.err.println(top.getName()+" WARNING skipped performance check on 'ci' system use");
        else
          assertTrue("expMinSerialDuration="+expMinSerialDuration+" actDuration="+actDuration, 
              actDuration < 0.5 * expMinSerialDuration);
    }
    
    @Test
    public void testConcurrent() throws Exception {
        Topology top = newTopology("testConcurrent");
        
        int ch = 0;
        List<Function<TStream<Integer>,TStream<JsonObject>>> pipelines = new ArrayList<>();
        pipelines.add(fakePipeline(ch++, 100, TimeUnit.MILLISECONDS));
        pipelines.add(fakePipeline(ch++, 100, TimeUnit.MILLISECONDS));
        pipelines.add(fakePipeline(ch++, 100, TimeUnit.MILLISECONDS));
        pipelines.add(fakePipeline(ch++, 100, TimeUnit.MILLISECONDS));
        pipelines.add(fakePipeline(ch++, 100, TimeUnit.MILLISECONDS));
        
        Function<List<JsonObject>,Integer> combiner = list -> {
            int sum = 0;
            int cnt = 0;
            System.out.println("combiner: "+list);
            for(JsonObject jo : list) {
              assertEquals(cnt++, jo.get("channel").getAsInt());
              sum += jo.get("result").getAsInt();
            }
            return sum;
        };
        
        TStream<Integer> values = top.of(1, 2, 3);
        Integer[] resultTuples = new Integer[]{
            1*pipelines.size(),
            2*pipelines.size(),
            3*pipelines.size(),
        };
        
        TStream<Integer> result = PlumbingStreams.concurrent(values, pipelines, combiner).tag("result");
        
        Condition<Long> count = top.getTester().tupleCount(result, 3);
        Condition<List<Integer>> contents = top.getTester().streamContents(result, resultTuples );

        long begin = System.currentTimeMillis();
        complete(top, count);
        long end = System.currentTimeMillis();
        
        assertTrue(contents.getResult().toString(), contents.valid());
        
        long actDuration = end - begin;
        long expMinSerialDuration = resultTuples.length * pipelines.size() * 100;
        long expMinDuration = resultTuples.length * 100;
        
        System.out.println(top.getName()+" expMinDuration="+expMinDuration+" actDuration="+actDuration+" expMinSerialDuration="+expMinSerialDuration);
        
        // a gross level performance check w/concurrent channels
        if (Boolean.getBoolean("quarks.build.ci"))
          System.err.println(top.getName()+" WARNING skipped performance check on 'ci' system use");
        else
          assertTrue("expMinSerialDuration="+expMinSerialDuration+" actDuration="+actDuration, 
              actDuration < 0.5 * expMinSerialDuration);
    }

    private BiFunction<Integer,Integer,JsonObject> fakeParallelAnalytic(long period, TimeUnit unit) {
      return (value,channel) -> { 
        try {
          Thread.sleep(unit.toMillis(period));  // simulate work for this period
          JsonObject jo = new JsonObject();
          jo.addProperty("channel", channel);
          jo.addProperty("result", value);
          return jo;
        } catch (InterruptedException e) {
          throw new RuntimeException("channel="+channel+" interrupted", e);
        }
      };
    }
    
    private BiFunction<TStream<Integer>,Integer,TStream<JsonObject>> fakeParallelPipeline(long period, TimeUnit unit) {
      return (stream,channel) -> stream
          .map(value -> fakeParallelAnalytic(period, unit).apply(value,channel))
          .filter(t->true)
          .tag("pipeline-ch"+channel);
    }
    
    private Function<JsonObject,JsonObject> fakeJsonAnalytic(int channel, long period, TimeUnit unit) {
      return jo -> { 
        try {
          Thread.sleep(unit.toMillis(period));  // simulate work for this period
          return jo;
        } catch (InterruptedException e) {
          throw new RuntimeException("channel="+channel+" interrupted", e);
        }
      };
    }
    
    @SuppressWarnings("unused")
    private BiFunction<TStream<JsonObject>,Integer,TStream<JsonObject>> fakeParallelPipelineTiming(long period, TimeUnit unit) {
      return (stream,channel) -> stream
          .map(jo -> { jo.addProperty("startPipelineMsec", System.currentTimeMillis());
                       return jo; })
          .map(fakeJsonAnalytic(channel, period, unit))
          .filter(t->true)
          .map(jo -> { jo.addProperty("endPipelineMsec", System.currentTimeMillis());
                      return jo; })
          .tag("pipeline-ch"+channel);
    }
    
    @Test
    public void testParallelMap() throws Exception {
        Topology top = newTopology("testParallelMap");
        
        BiFunction<Integer,Integer,JsonObject> mapper = 
            fakeParallelAnalytic(100, TimeUnit.MILLISECONDS);
        
        int width = 5;
        ToIntFunction<Integer> splitter = tuple -> tuple % width;
        
        Integer[] resultTuples = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        TStream<Integer> values = top.of(resultTuples);
        
        TStream<JsonObject> result = PlumbingStreams.parallelMap(values, width, splitter, mapper).tag("result");
        TStream<Integer> result2 = result.map(jo -> {
            int r = jo.get("result").getAsInt();
            assertEquals(splitter.applyAsInt(r), jo.get("channel").getAsInt());
            return r;
          });
        
        Condition<Long> count = top.getTester().tupleCount(result, resultTuples.length);
        Condition<List<Integer>> contents = top.getTester().contentsUnordered(result2, resultTuples);
    
        long begin = System.currentTimeMillis();
        complete(top, count);
        long end = System.currentTimeMillis();
        
        assertTrue(contents.getResult().toString(), contents.valid());
        
        long actDuration = end - begin;
        long expMinSerialDuration = resultTuples.length * 100;
        long expMinDuration = (resultTuples.length / width) * 100;
        
        System.out.println(top.getName()+" expMinDuration="+expMinDuration+" actDuration="+actDuration+" expMinSerialDuration="+expMinSerialDuration);
        
        // a gross level performance check w/parallel channels
        if (Boolean.getBoolean("quarks.build.ci"))
          System.err.println(top.getName()+" WARNING skipped performance check on 'ci' system use");
        else
          assertTrue("expMinSerialDuration="+expMinSerialDuration+" actDuration="+actDuration, 
              actDuration < 0.5 * expMinSerialDuration);
    }
    
    @Test
    public void testParallel() throws Exception {
        Topology top = newTopology("testParallel");
        
        BiFunction<TStream<Integer>,Integer,TStream<JsonObject>> pipeline = 
            fakeParallelPipeline(100, TimeUnit.MILLISECONDS);
        
        int width = 5;
        ToIntFunction<Integer> splitter = tuple -> tuple % width;
        
        Integer[] resultTuples = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        TStream<Integer> values = top.of(resultTuples);
        
        TStream<JsonObject> result = PlumbingStreams.parallel(values, width, splitter, pipeline).tag("result");
        TStream<Integer> result2 = result.map(jo -> {
            int r = jo.get("result").getAsInt();
            assertEquals(splitter.applyAsInt(r), jo.get("channel").getAsInt());
            return r;
          });
        
        Condition<Long> count = top.getTester().tupleCount(result, resultTuples.length);
        Condition<List<Integer>> contents = top.getTester().contentsUnordered(result2, resultTuples);
        
        long begin = System.currentTimeMillis();
        complete(top, count);
        long end = System.currentTimeMillis();
        
        assertTrue(contents.getResult().toString(), contents.valid());
        
        long actDuration = end - begin;
        long expMinSerialDuration = resultTuples.length * 100;
        long expMinDuration = (resultTuples.length / width) * 100;
        
        System.out.println(top.getName()+" expMinDuration="+expMinDuration+" actDuration="+actDuration+" expMinSerialDuration="+expMinSerialDuration);
        
        // a gross level performance check w/parallel channels
        if (Boolean.getBoolean("quarks.build.ci"))
          System.err.println(top.getName()+" WARNING skipped performance check on 'ci' system use");
        else
          assertTrue("expMinSerialDuration="+expMinSerialDuration+" actDuration="+actDuration, 
              actDuration < 0.5 * expMinSerialDuration);
    }
    
    @Test
    public void testParallelBalanced() throws Exception {
        // May need tweak validation sensitivity or add this:
        // Timing variances on shared machines can cause this test to fail
        // assumeTrue(!Boolean.getBoolean("quarks.build.ci"));

        Topology top = newTopology("testParallelBalanced");
        
        // arrange for even channels to process ~2x as many as odd channels.
        BiFunction<TStream<Integer>,Integer,TStream<JsonObject>> pipeline =
            (stream,ch) -> {
              long delay = (ch % 2 == 0) ? 10 : 20;
              return stream.map(fakeAnalytic(ch, delay, TimeUnit.MILLISECONDS));
            };
        
        int width = 4;
        int tupCnt = 60;
        Integer[] resultTuples = new Integer[tupCnt];
        for (int i = 0; i < tupCnt; i++)
          resultTuples[i] = i;
        AtomicInteger[] chCounts = new AtomicInteger[width];
        for (int ch = 0; ch < width; ch++)
          chCounts[ch] = new AtomicInteger();
        
        TStream<Integer> values = top.of(resultTuples);
        
        TStream<JsonObject> result = PlumbingStreams.parallelBalanced(values, width, pipeline).tag("result");
        TStream<Integer> result2 = result.map(jo -> {
            int r = jo.get("result").getAsInt();
            int ch = jo.get("channel").getAsInt();
            chCounts[ch].incrementAndGet();
            return r;
          });
        
        Condition<Long> count = top.getTester().tupleCount(result, resultTuples.length);
        Condition<List<Integer>> contents = top.getTester().contentsUnordered(result2, resultTuples);
        
        long begin = System.currentTimeMillis();
        complete(top, count);
        long end = System.currentTimeMillis();
        
        assertTrue(contents.getResult().toString(), contents.valid());
        
        long actDuration = end - begin;
        long expMinSerialDuration = resultTuples.length * 20;
        long expMinDuration = (resultTuples.length / width) * 20;
        
        System.out.println(top.getName()+" expMinDuration="+expMinDuration+" actDuration="+actDuration+" expMinSerialDuration="+expMinSerialDuration);
        System.out.println(top.getName()+" chCounts="+Arrays.asList(chCounts));
        
        // a gross level performance check w/parallel channels
        if (Boolean.getBoolean("quarks.build.ci"))
          System.err.println(top.getName()+" WARNING skipped performance check on 'ci' system use");
        else
          assertTrue("expMinSerialDuration="+expMinSerialDuration+" actDuration="+actDuration, 
              actDuration < 0.5 * expMinSerialDuration);
        
        int evenChCounts = 0;
        int oddChCounts = 0;
        for (int ch = 0; ch < width; ch++) {
          assertTrue(chCounts[ch].get() != 0);
          if (ch % 2 == 0)
            evenChCounts += chCounts[ch].get();
          else
            oddChCounts += chCounts[ch].get();
        }
        assertTrue(oddChCounts > 0.4 * evenChCounts
            && oddChCounts < 0.6 * evenChCounts);
    }
    
//    @Test
//    public void testParallelTiming() throws Exception {
//        Topology top = newTopology("testParallelTiming");
//        
//        BiFunction<TStream<JsonObject>,Integer,TStream<JsonObject>> pipeline = 
//            fakeParallelPipelineTiming(100, TimeUnit.MILLISECONDS);
//        
//        int width = 5;
//        // ToIntFunction<Integer> splitter = tuple -> tuple % width;
//        ToIntFunction<JsonObject> splitter = jo -> jo.get("result").getAsInt() % width;
//        
//        Integer[] resultTuples = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
//        TStream<Integer> values = top.of(resultTuples);
//        
//        TStream<JsonObject> inStream = values.map(value -> {
//            JsonObject jo = new JsonObject();
//            jo.addProperty("result", value);
//            jo.addProperty("channel", splitter.applyAsInt(jo));
//            jo.addProperty("enterParallelMsec", System.currentTimeMillis());
//            return jo;
//          });
//        TStream<JsonObject> result = PlumbingStreams.parallel(inStream, width, splitter, pipeline).tag("result");
//        TStream<Integer> result2 = result.map(jo -> {
//            jo.addProperty("exitParallelMsec", System.currentTimeMillis());
//            System.out.println("ch="+jo.get("channel").getAsInt()
//                +" endPipeline-startPipeline="
//                  +(jo.get("endPipelineMsec").getAsLong()
//                    - jo.get("startPipelineMsec").getAsLong())
//                +" exitParallel-startPipeine="
//                  +(jo.get("exitParallelMsec").getAsLong()
//                      - jo.get("startPipelineMsec").getAsLong()));
//            int r = jo.get("result").getAsInt();
//            assertEquals(splitter.applyAsInt(jo), jo.get("channel").getAsInt());
//            return r;
//          });
//        
//        Condition<Long> count = top.getTester().tupleCount(result, resultTuples.length);
//        Condition<List<Integer>> contents = top.getTester().contentsUnordered(result2, resultTuples);
//        long begin = System.currentTimeMillis();
//        complete(top, count);
//        long end = System.currentTimeMillis();
//        assertTrue(contents.getResult().toString(), contents.valid());
//        
//        long actDuration = end - begin;
//        
//        long expMinSerialDuration = resultTuples.length * 100;
//        long expMinDuration = (resultTuples.length / width) * 100;
//        
//        System.out.println(top.getName()+" expMinDuration="+expMinDuration+" actDuration="+actDuration+" expMinSerialDuration="+expMinSerialDuration);
//        
//        // a gross level performance check w/parallel channels
//        assertTrue("expMinSerialDuration="+expMinSerialDuration+" actDuration="+actDuration, 
//            actDuration < 0.5 * expMinSerialDuration);
//    }

    @Test
    public void testGate() throws Exception {
        Topology topology = newTopology("testGate");

        TStream<String> raw = topology.strings("a", "b", "c", "d", "e");

        Semaphore semaphore = new Semaphore(1);
        raw = PlumbingStreams.gate(raw, semaphore);

        ArrayList<Integer> resultAvailablePermits = new ArrayList<>();
        ArrayList<Integer> arrayResult = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            arrayResult.add(0);
            arrayResult.add(1);
        }

        raw.sink(t -> {
            //Add 0 to list because semaphore.acquire() in sync has occurred
            resultAvailablePermits.add(semaphore.availablePermits());
            semaphore.release();
            //Add 1 to list because semaphore.release() has executed
            resultAvailablePermits.add(semaphore.availablePermits());
        });

        Condition<List<String>> contents = topology.getTester()
            .streamContents(raw, "a", "b", "c", "d", "e");
        complete(topology, contents);

        assertTrue("valid:" + contents.getResult(), contents.valid());
        assertTrue("valid:" + resultAvailablePermits, resultAvailablePermits.equals(arrayResult));
    }

    @Test
    public void testGateWithLocking() throws Exception {
        Topology topology = newTopology("testGateWithLocking");

        TStream<String> raw = topology.strings("a", "b", "c", "d", "e");

        Semaphore semaphore = new Semaphore(3);
        raw = PlumbingStreams.gate(raw, semaphore);

        ArrayList<Integer> resultAvailablePermits = new ArrayList<>();
        ArrayList<Integer> arrayResult = new ArrayList<>();
        arrayResult.add(2);
        arrayResult.add(1);
        arrayResult.add(0);

        raw.sink(t -> {
            //Add number of availablePermits
            resultAvailablePermits.add(semaphore.availablePermits());
        });

        Condition<List<String>> contents = topology.getTester().streamContents(raw, "a", "b", "c");
        complete(topology, contents, 1000, TimeUnit.MILLISECONDS);

        assertTrue("valid:" + contents.getResult(), contents.valid());
        assertTrue("valid:" + resultAvailablePermits, resultAvailablePermits.equals(arrayResult));
    }
    
    @Test
    public void testStreamScopeFn() throws Exception {

        StreamScope<Integer> ss = new StreamScope<>();

        List<Sample<Integer>> samples; 
        Sample<Integer> sample;

        assertFalse(ss.isEnabled());
        assertNotNull(ss.bufferMgr());
        assertNotNull(ss.triggerMgr());
        assertEquals(0, ss.getSampleCount());
        samples = ss.getSamples();
        assertNotNull(samples);
        assertEquals(0, samples.size());
        
        // ---------------- no capture when not enabled
        ss.accept(1);
        assertEquals(0, ss.getSampleCount());
        assertEquals(0, ss.getSamples().size());
        
        ss.setEnabled(true);
        
        // ---------------- single capture
        // note: getSamples() removes captured tuples
        ss.accept(100);
        assertEquals(1, ss.getSampleCount());
        samples = ss.getSamples();
        assertEquals(1, samples.size());
        sample = samples.get(0);
        assertEquals(100, sample.tuple().intValue());
        assertTrue(sample.timestamp() != 0);
        assertTrue(sample.nanoTime() != 0);
        assertEquals(0, ss.getSampleCount());
        assertEquals(0, ss.getSamples().size());
        
        // ---------------- next capture/get; different lists
        List<Sample<Integer>> savedSamples = samples;
        ss.accept(101);
        assertEquals(1, ss.getSampleCount());
        samples = ss.getSamples();
        assertEquals(101, samples.get(0).tuple().intValue());
        assertTrue(samples != savedSamples);
        assertEquals(0, ss.getSampleCount());
        assertEquals(0, ss.getSamples().size());
        
        // ---------------- multi capture
        ss.accept(100);
        ss.accept(101);
        ss.accept(102);
        assertEquals(3, ss.getSampleCount());
        samples = ss.getSamples();
        assertEquals(100, samples.get(0).tuple().intValue());
        assertEquals(101, samples.get(1).tuple().intValue());
        assertEquals(102, samples.get(2).tuple().intValue());
        assertEquals(0, ss.getSampleCount());
        assertEquals(0, ss.getSamples().size());
        
        // ----------------  disable => clears capture buffer
        ss.accept(100);
        ss.accept(101);
        ss.accept(102);
        assertEquals(3, ss.getSampleCount());
        ss.setEnabled(false);
        assertEquals(0, ss.getSampleCount());
        assertEquals(0, ss.getSamples().size());

        ss.setEnabled(true);

        // ---------------- buffer control at the limit (no eviction)
        ss.bufferMgr().setMaxRetentionCount(3);
        ss.accept(100);
        ss.accept(101);
        ss.accept(102);
        assertEquals(3, ss.getSampleCount());
        samples = ss.getSamples();
        assertEquals(100, samples.get(0).tuple().intValue());
        assertEquals(101, samples.get(1).tuple().intValue());
        assertEquals(102, samples.get(2).tuple().intValue());
        assertEquals(0, ss.getSampleCount());
        assertEquals(0, ss.getSamples().size());
        
        // ---------------- buffer control with eviction
        ss.bufferMgr().setMaxRetentionCount(2);
        ss.accept(100);
        ss.accept(101);
        ss.accept(102);
        assertEquals(2, ss.getSampleCount());
        samples = ss.getSamples();
        assertEquals(101, samples.get(0).tuple().intValue());
        assertEquals(102, samples.get(1).tuple().intValue());
        assertEquals(0, ss.getSampleCount());
        assertEquals(0, ss.getSamples().size());
        ss.bufferMgr().setMaxRetentionCount(10);
        
        // ---------------- trigger byCount
        ss.triggerMgr().setByCount(3);
        ss.accept(100);
        ss.accept(101);
        ss.accept(102);
        ss.accept(103);
        ss.accept(104);
        ss.accept(105);
        ss.accept(106);
        assertEquals(3, ss.getSampleCount());
        samples = ss.getSamples();
        assertEquals(100, samples.get(0).tuple().intValue());
        assertEquals(103, samples.get(1).tuple().intValue());
        assertEquals(106, samples.get(2).tuple().intValue());
        
        // ---------------- trigger byPredicate
        ss.triggerMgr().setByPredicate(t -> t % 2 == 0);
        ss.accept(100);
        ss.accept(101);
        ss.accept(102);
        ss.accept(103);
        ss.accept(104);
        assertEquals(3, ss.getSampleCount());
        samples = ss.getSamples();
        assertEquals(100, samples.get(0).tuple().intValue());
        assertEquals(102, samples.get(1).tuple().intValue());
        assertEquals(104, samples.get(2).tuple().intValue());
        
        // ---------------- trigger byTime
        ss.triggerMgr().setByTime(100, TimeUnit.MILLISECONDS);
        ss.accept(100);
        ss.accept(101);
        ss.accept(102);
        Thread.sleep(110);
        ss.accept(103);
        ss.accept(104);
        ss.accept(105);
        Thread.sleep(110);
        ss.accept(106);
        assertEquals(3, ss.getSampleCount());
        samples = ss.getSamples();
        assertEquals(100, samples.get(0).tuple().intValue());
        assertEquals(103, samples.get(1).tuple().intValue());
        assertEquals(106, samples.get(2).tuple().intValue());
        
        // ---------------- trigger continuous
        ss.triggerMgr().setContinuous();
        ss.accept(100);
        ss.accept(101);
        ss.accept(102);
        assertEquals(3, ss.getSampleCount());
        samples = ss.getSamples();
        assertEquals(100, samples.get(0).tuple().intValue());
        assertEquals(101, samples.get(1).tuple().intValue());
        assertEquals(102, samples.get(2).tuple().intValue());
        
        // ---------------- trigger pause
        ss.accept(100);
        ss.accept(101);
        ss.triggerMgr().setPaused(true);
        assertTrue(ss.triggerMgr().isPaused());
        ss.accept(102);
        ss.accept(103);
        ss.triggerMgr().setPaused(false);
        assertFalse(ss.triggerMgr().isPaused());
        ss.accept(104);
        assertEquals(3, ss.getSampleCount());
        samples = ss.getSamples();
        assertEquals(100, samples.get(0).tuple().intValue());
        assertEquals(101, samples.get(1).tuple().intValue());
        assertEquals(104, samples.get(2).tuple().intValue());
        
        // ---------------- trigger pauseOn
        
        ss.triggerMgr().setPauseOn(t -> t == 102);
        ss.accept(100);
        ss.accept(101);
        ss.accept(102);
        ss.accept(103);
        ss.accept(104);
        ss.accept(105);
        assertTrue(ss.triggerMgr().isPaused());
        assertEquals(3, ss.getSampleCount());
        samples = ss.getSamples();
        assertEquals(100, samples.get(0).tuple().intValue());
        assertEquals(101, samples.get(1).tuple().intValue());
        assertEquals(102, samples.get(2).tuple().intValue());
        ss.triggerMgr().setPaused(false);
        ss.accept(1000);
        ss.accept(1010);
        ss.accept(102);
        ss.accept(1030);
        assertEquals(3, ss.getSampleCount());
        samples = ss.getSamples();
        assertEquals(1000, samples.get(0).tuple().intValue());
        assertEquals(1010, samples.get(1).tuple().intValue());
        assertEquals(102, samples.get(2).tuple().intValue());
        
    }

    @Test
    public void testStreamScopeRegistry() throws Exception {

      StreamScope<Integer> ss1 = new StreamScope<>();
      StreamScope<Integer> ss2 = new StreamScope<>();
      
      StreamScopeRegistry rgy = new StreamScopeRegistry();
      
      assertNotNull(rgy.getNames());
      assertEquals(0, rgy.getNames().size());
      assertNotNull(rgy.getStreamScopes());
      assertEquals(0, rgy.getStreamScopes().size());
      assertNull(rgy.lookup("xyzzy"));
      rgy.unregister("xyzzy");
      rgy.unregister(ss1);
      
      // ---------- name generation / parse functions
      String alias1Name = StreamScopeRegistry.nameByStreamAlias("alias1");
      assertNotNull(alias1Name);
      String alias2Name = StreamScopeRegistry.nameByStreamAlias("alias2");
      assertNotNull(alias2Name);
      assertFalse(alias1Name.equals(alias2Name));
      String alias1 = StreamScopeRegistry.streamAliasFromName(alias1Name);
      assertEquals("alias1", alias1);
      
      String id1Name = StreamScopeRegistry.nameByStreamId("id1");
      assertNotNull(id1Name);
      String id2Name = StreamScopeRegistry.nameByStreamId("id2");
      assertNotNull(id2Name);
      assertFalse(id1Name.equals(id2Name));
      String id1 = StreamScopeRegistry.streamIdFromName(id1Name);
      assertEquals("id1", id1);

      assertFalse(StreamScopeRegistry.nameByStreamAlias("1")
          .equals(StreamScopeRegistry.nameByStreamId("1")));
      
      // ---------- register
      rgy.register(alias1Name, ss1);
      rgy.register(alias2Name, ss2);
      rgy.register(id2Name, ss2);

      // ---------- lookup
      assertSame(ss1, rgy.lookup(alias1Name));
      assertSame(ss2, rgy.lookup(alias2Name));
      assertSame(ss2, rgy.lookup(id2Name));
     
      // ----------- getNames
      assertEquals(3, rgy.getNames().size());
      assertTrue(rgy.getNames().contains(alias1Name));
      assertFalse(rgy.getNames().contains(id1Name));
      assertTrue(rgy.getNames().contains(alias2Name));
      assertTrue(rgy.getNames().contains(id2Name));
      
      // ----------- getStreamScopes
      assertEquals(2, rgy.getStreamScopes().keySet().size());
      assertTrue(rgy.getStreamScopes().keySet().contains(ss1));
      assertTrue(rgy.getStreamScopes().keySet().contains(ss2));
      assertEquals(1, rgy.getStreamScopes().get(ss1).size());
      assertTrue(rgy.getStreamScopes().get(ss1).contains(alias1Name));
      assertEquals(2, rgy.getStreamScopes().get(ss2).size());
      assertTrue(rgy.getStreamScopes().get(ss2).contains(alias2Name));
      assertTrue(rgy.getStreamScopes().get(ss2).contains(id2Name));
      
      // ---------- unregister
      rgy.unregister(alias1Name);
      assertNull(rgy.lookup(alias1Name));
      assertEquals(2, rgy.getNames().size());
      assertFalse(rgy.getNames().contains(alias1Name));
      assertFalse(rgy.getStreamScopes().keySet().contains(ss1));
      assertTrue(rgy.getStreamScopes().keySet().contains(ss2));
      
      rgy.unregister(alias2Name);
      assertNull(rgy.lookup(alias2Name));
      assertEquals(1, rgy.getNames().size());
      assertFalse(rgy.getNames().contains(alias2Name));
      assertTrue(rgy.getStreamScopes().keySet().contains(ss2));
      assertSame(ss2, rgy.lookup(id2Name));
      rgy.unregister(id2Name);
      assertEquals(0, rgy.getNames().size());
      assertEquals(0, rgy.getStreamScopes().keySet().size());
      
      rgy.register(alias2Name, ss2);
      rgy.register(id2Name, ss2);
      rgy.unregister(ss2);
      assertEquals(0, rgy.getNames().size());
      assertEquals(0, rgy.getStreamScopes().keySet().size());
    }

}
