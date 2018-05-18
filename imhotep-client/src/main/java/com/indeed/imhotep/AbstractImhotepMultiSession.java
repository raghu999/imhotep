/*
 * Copyright (C) 2018 Indeed Inc.
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
 package com.indeed.imhotep;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.io.Closer;
import com.indeed.flamdex.query.Query;
import com.indeed.flamdex.query.Term;
import com.indeed.imhotep.api.DocIterator;
import com.indeed.imhotep.api.FTGSIterator;
import com.indeed.imhotep.api.GroupStatsIterator;
import com.indeed.imhotep.api.ImhotepOutOfMemoryException;
import com.indeed.imhotep.api.ImhotepSession;
import com.indeed.imhotep.api.PerformanceStats;
import com.indeed.imhotep.api.RawFTGSIterator;
import com.indeed.imhotep.service.DocIteratorMerger;
import com.indeed.util.core.Throwables2;
import com.indeed.util.core.io.Closeables2;
import com.indeed.util.core.threads.LogOnUncaughtExceptionHandler;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongIterators;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.apache.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author jsgroth
 */
public abstract class AbstractImhotepMultiSession<T extends ImhotepSession>
    extends AbstractImhotepSession
    implements Instrumentation.Provider {

    private static final Logger log = Logger.getLogger(AbstractImhotepMultiSession.class);

    private final Instrumentation.ProviderSupport instrumentation =
        new Instrumentation.ProviderSupport();

    protected final T[] sessions;

    private final Long[] totalDocFreqBuf;

    protected final Integer[] integerBuf;

    private final Long[] longBuf;

    private final Object[] nullBuf;

    private final List<TermCount>[] termCountListBuf;

    private FTGSIterator lastIterator;

    protected final AtomicLong tempFileSizeBytesLeft;
    private long savedTempFileSizeValue;
    private long savedCPUTime;

    private boolean closed = false;

    private final class RelayObserver implements Instrumentation.Observer {
        public synchronized void onEvent(final Instrumentation.Event event) {
            instrumentation.fire(event);
        }
    }

    private final class SessionThreadFactory extends InstrumentedThreadFactory {

        private final String namePrefix;

        SessionThreadFactory(final String namePrefix) {
            this.namePrefix = namePrefix;
            addObserver(new Observer());
        }

        public Thread newThread(@Nonnull final Runnable runnable) {
            final LogOnUncaughtExceptionHandler handler =
                new LogOnUncaughtExceptionHandler(log);
            final Thread result = super.newThread(runnable);
            result.setDaemon(true);
            result.setName(namePrefix + "-" + result.getId());
            result.setUncaughtExceptionHandler(handler);
            return result;
        }

        /* Forward any events from the thread factory to observers of the
           multisession. */
        private final class Observer implements Instrumentation.Observer {
            public void onEvent(final Instrumentation.Event event) {
                event.getProperties().put(Instrumentation.Keys.THREAD_FACTORY, namePrefix);
                AbstractImhotepMultiSession.this.instrumentation.fire(event);
            }
        }
    }

    private final SessionThreadFactory localThreadFactory =
        new SessionThreadFactory(AbstractImhotepMultiSession.class.getName() + "-localThreads");
    private final SessionThreadFactory splitThreadFactory =
        new SessionThreadFactory(AbstractImhotepMultiSession.class.getName() + "-splitThreads");
    private final SessionThreadFactory mergeThreadFactory =
        new SessionThreadFactory(AbstractImhotepMultiSession.class.getName() + "-mergeThreads");

    private final ArrayList<InstrumentedThreadFactory> threadFactories =
        new ArrayList<InstrumentedThreadFactory>(Arrays.asList(localThreadFactory,
                                                               splitThreadFactory,
                                                               mergeThreadFactory));

    private final ExecutorService executor =
        Executors.newCachedThreadPool(localThreadFactory);
    private final ExecutorService getSplitBufferThreads =
        Executors.newCachedThreadPool(splitThreadFactory);
    private final ExecutorService mergeSplitBufferThreads =
        Executors.newCachedThreadPool(mergeThreadFactory);

    protected int numStats = 0;

    protected int numGroups = 2;

    protected AbstractImhotepMultiSession(final T[] sessions) {
        this(sessions, null);
    }

    @SuppressWarnings({"unchecked"})
    protected AbstractImhotepMultiSession(final T[] sessions, final AtomicLong tempFileSizeBytesLeft) {
        this.tempFileSizeBytesLeft = tempFileSizeBytesLeft;
        this.savedTempFileSizeValue = (tempFileSizeBytesLeft == null) ? 0 : tempFileSizeBytesLeft.get();
        if (sessions == null || sessions.length == 0) {
            throw new IllegalArgumentException("at least one session is required");
        }

        this.sessions = sessions;

        totalDocFreqBuf = new Long[sessions.length];
        integerBuf = new Integer[sessions.length];
        longBuf = new Long[sessions.length];
        nullBuf = new Object[sessions.length];
        termCountListBuf = new List[sessions.length];
    }

    public void addObserver(final Instrumentation.Observer observer) {
        instrumentation.addObserver(observer);
    }

    public void removeObserver(final Instrumentation.Observer observer) {
        instrumentation.removeObserver(observer);
    }

    @Override
    public long getTotalDocFreq(final String[] intFields, final String[] stringFields) {
        executeRuntimeException(totalDocFreqBuf, new ThrowingFunction<ImhotepSession, Long>() {
            @Override
            public Long apply(final ImhotepSession session) {
                return session.getTotalDocFreq(intFields, stringFields);
            }
        });
        long sum = 0L;
        for (final long totalDocFreq : totalDocFreqBuf) {
            sum += totalDocFreq;
        }
        return sum;
    }

    @Override
    public long[] getGroupStats(final int stat) {
        try( GroupStatsIterator it = getGroupStatsIterator(stat) ) {
            return LongIterators.unwrap(it, it.getNumGroups());
        } catch (final IOException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public GroupStatsIterator getGroupStatsIterator(final int stat) {
        final GroupStatsIterator[] statsBuffer = new GroupStatsIterator[sessions.length];
        executeRuntimeException(statsBuffer, new ThrowingFunction<ImhotepSession, GroupStatsIterator>() {
            @Override
            public GroupStatsIterator apply(final ImhotepSession session) {
                return session.getGroupStatsIterator(stat);
            }
        });

        if( statsBuffer.length == 1 ) {
            return statsBuffer[0];
        } else {
            return new GroupStatsIteratorCombiner(statsBuffer);
        }
    }

    @Override
    public int regroup(final GroupMultiRemapRule[] rawRules, final boolean errorOnCollisions) throws ImhotepOutOfMemoryException {
        executeMemoryException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                return session.regroup(rawRules, errorOnCollisions);
            }
        });

        numGroups = Collections.max(Arrays.asList(integerBuf));
        return numGroups;
    }

    @Override
    public int regroup(final int numRawRules, final Iterator<GroupMultiRemapRule> rawRules, final boolean errorOnCollisions) throws ImhotepOutOfMemoryException {
        final GroupMultiRemapRuleArray rulesArray =
            new GroupMultiRemapRuleArray(numRawRules, rawRules);

        return regroup(rulesArray.elements(), errorOnCollisions);
    }

    @Override
    public int regroup(final GroupRemapRule[] rawRules) throws ImhotepOutOfMemoryException {
        executeMemoryException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                return session.regroup(rawRules);
            }
        });

        numGroups = Collections.max(Arrays.asList(integerBuf));
        return numGroups;
    }

    public int regroup2(final int numRawRules, final Iterator<GroupRemapRule> iterator) throws ImhotepOutOfMemoryException {
        final GroupRemapRuleArray rulesArray = new GroupRemapRuleArray(numRawRules, iterator);
        executeMemoryException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                return session.regroup(rulesArray.elements());
            }
        });

        numGroups = Collections.max(Arrays.asList(integerBuf));
        return numGroups;
    }

    @Override
    public int regroup(final QueryRemapRule rule) throws ImhotepOutOfMemoryException {
        executeMemoryException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                return session.regroup(rule);
            }
        });

        numGroups = Collections.max(Arrays.asList(integerBuf));
        return numGroups;
    }

    @Override
    public void intOrRegroup(final String field, final long[] terms, final int targetGroup, final int negativeGroup, final int positiveGroup) throws ImhotepOutOfMemoryException {
        executeMemoryException(nullBuf, new ThrowingFunction<ImhotepSession, Object>() {
            @Override
            public Object apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                session.intOrRegroup(field, terms, targetGroup, negativeGroup, positiveGroup);
                return null;
            }
        });
    }

    @Override
    public void stringOrRegroup(final String field, final String[] terms, final int targetGroup, final int negativeGroup, final int positiveGroup) throws ImhotepOutOfMemoryException {
        executeMemoryException(nullBuf, new ThrowingFunction<ImhotepSession, Object>() {
            @Override
            public Object apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                session.stringOrRegroup(field, terms, targetGroup, negativeGroup, positiveGroup);
                return null;
            }
        });
    }

    @Override
    public void regexRegroup(final String field, final String regex, final int targetGroup, final int negativeGroup, final int positiveGroup) throws ImhotepOutOfMemoryException {
        executeMemoryException(nullBuf, new ThrowingFunction<ImhotepSession, Object>() {
            @Override
            public Object apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                session.regexRegroup(field, regex, targetGroup, negativeGroup, positiveGroup);
                return null;
            }
        });
    }

    @Override
    public void randomRegroup(final String field, final boolean isIntField, final String salt, final double p, final int targetGroup,
                              final int negativeGroup, final int positiveGroup) throws ImhotepOutOfMemoryException {
        executeMemoryException(nullBuf, new ThrowingFunction<ImhotepSession, Object>() {
            @Override
            public Object apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                session.randomRegroup(field, isIntField, salt, p, targetGroup, negativeGroup, positiveGroup);
                return null;
            }
        });
    }

    @Override
    public void randomMultiRegroup(final String field, final boolean isIntField, final String salt, final int targetGroup,
                                   final double[] percentages, final int[] resultGroups) throws ImhotepOutOfMemoryException {
        executeMemoryException(nullBuf, new ThrowingFunction<ImhotepSession, Object>() {
            @Override
            public Object apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                session.randomMultiRegroup(field, isIntField, salt, targetGroup, percentages, resultGroups);
                return null;
            }
        });
    }

    @Override
    public void randomMetricRegroup(final int stat,
                                    final String salt,
                                    final double p,
                                    final int targetGroup,
                                    final int negativeGroup,
                                    final int positiveGroup) throws ImhotepOutOfMemoryException {
        executeMemoryException(nullBuf, (ThrowingFunction<ImhotepSession, Object>) session -> {
            session.randomMetricRegroup(stat, salt, p, targetGroup, negativeGroup, positiveGroup);
            return null;
        });
    }

    @Override
    public void randomMetricMultiRegroup(final int stat,
                                         final String salt,
                                         final int targetGroup,
                                         final double[] percentages,
                                         final int[] resultGroups) throws ImhotepOutOfMemoryException {
        executeMemoryException(nullBuf, (ThrowingFunction<ImhotepSession, Object>) session -> {
            session.randomMetricMultiRegroup(stat, salt, targetGroup, percentages, resultGroups);
            return null;
        });
    }

    @Override
    public int metricRegroup(final int stat, final long min, final long max, final long intervalSize, final boolean noGutters) throws ImhotepOutOfMemoryException {
        executeMemoryException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                return session.metricRegroup(stat, min, max, intervalSize, noGutters);
            }
        });

        numGroups = Collections.max(Arrays.asList(integerBuf));
        return numGroups;
    }

    @Override
    public int metricRegroup2D(final int xStat, final long xMin, final long xMax, final long xIntervalSize,
                               final int yStat, final long yMin, final long yMax, final long yIntervalSize) throws ImhotepOutOfMemoryException {
        executeMemoryException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                return session.metricRegroup2D(xStat, xMin, xMax, xIntervalSize, yStat, yMin, yMax, yIntervalSize);
            }
        });

        numGroups = Collections.max(Arrays.asList(integerBuf));
        return numGroups;
    }

    public int metricFilter(final int stat, final long min, final long max, final boolean negate) throws ImhotepOutOfMemoryException {
        executeMemoryException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                return session.metricFilter(stat, min, max, negate);
            }
        });

        numGroups = Collections.max(Arrays.asList(integerBuf));
        return numGroups;
    }

    @Override
    public List<TermCount> approximateTopTerms(final String field, final boolean isIntField, final int k) {
        final int subSessionK = k * 2;

        executeRuntimeException(termCountListBuf, new ThrowingFunction<ImhotepSession, List<TermCount>>() {
            @Override
            public List<TermCount> apply(final ImhotepSession session) {
                return session.approximateTopTerms(field, isIntField, subSessionK);
            }
        });

        return mergeTermCountLists(termCountListBuf, field, isIntField, k);
    }

    private static List<TermCount> mergeTermCountLists(
            final List<TermCount>[] termCountListBuf,
            final String field,
            final boolean isIntField,
            final int k) {
        final List<TermCount> ret;
        if (isIntField) {
            final Long2LongMap counts = new Long2LongOpenHashMap(k * 2);
            for (final List<TermCount> list : termCountListBuf) {
                for (final TermCount termCount : list) {
                    final long termVal = termCount.getTerm().getTermIntVal();
                    final long count = termCount.getCount();
                    counts.put(termVal, counts.get(termVal) + count);
                }
            }
            ret = Lists.newArrayListWithCapacity(counts.size());
            for (final LongIterator iter = counts.keySet().iterator(); iter.hasNext(); ) {
                final Long term = iter.nextLong();
                final long count = counts.get(term);
                ret.add(new TermCount(new Term(field, true, term, ""), count));
            }
        } else {
            final Object2LongMap<String> counts = new Object2LongOpenHashMap<>(k * 2);
            for (final List<TermCount> list : termCountListBuf) {
                for (final TermCount termCount : list) {
                    final String termVal = termCount.getTerm().getTermStringVal();
                    final long count = termCount.getCount();
                    counts.put(termVal, counts.getLong(termVal) + count);
                }
            }
            ret = Lists.newArrayListWithCapacity(counts.size());
            for (final Map.Entry<String, Long> stringLongEntry : counts.entrySet()) {
                final long count = stringLongEntry.getValue();
                ret.add(new TermCount(new Term(field, false, 0, stringLongEntry.getKey()), count));
            }
        }
        Collections.sort(ret, TermCount.REVERSE_COUNT_COMPARATOR);
        final int end = Math.min(k, ret.size());
        return ret.subList(0, end);
    }

    @Override
    public int pushStat(final String statName) throws ImhotepOutOfMemoryException {
        executeMemoryException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                return session.pushStat(statName);
            }
        });

        numStats = validateNumStats(integerBuf);
        return numStats;
    }

    @Override
    public int pushStats(final List<String> statNames) throws ImhotepOutOfMemoryException {
        executeRuntimeException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession session) throws ImhotepOutOfMemoryException {
                return session.pushStats(statNames);
            }
        });

        numStats = validateNumStats(integerBuf);
        return numStats;
    }

    @Override
    public int popStat() {
        executeRuntimeException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession session) {
                return session.popStat();
            }
        });

        numStats = validateNumStats(integerBuf);
        return numStats;
    }

    @Override
    public int getNumStats() {
        executeRuntimeException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession session) {
                return session.getNumStats();
            }
        });

        numStats = validateNumStats(integerBuf);
        return numStats;
    }

    @Override
    public int getNumGroups() {
        executeRuntimeException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession imhotepSession) {
                return imhotepSession.getNumGroups();
            }
        });
        return Collections.max(Arrays.asList(integerBuf));
    }

    public long getLowerBound(final int stat) {
        executeRuntimeException(longBuf, new ThrowingFunction<ImhotepSession, Long>() {
            @Override
            public Long apply(final ImhotepSession session) {
                return session.getLowerBound(stat);
            }
        });
        return Collections.min(Arrays.asList(longBuf));
    }

    @Override
    public long getUpperBound(final int stat) {
        executeRuntimeException(longBuf, new ThrowingFunction<ImhotepSession, Long>() {
            @Override
            public Long apply(final ImhotepSession session) {
                return session.getUpperBound(stat);
            }
        });
        return Collections.max(Arrays.asList(longBuf));
    }

    @Override
    public void createDynamicMetric(final String name) throws ImhotepOutOfMemoryException {
        executeRuntimeException(nullBuf, new ThrowingFunction<ImhotepSession, Object>() {
            @Override
            public Object apply(final ImhotepSession imhotepSession) throws ImhotepOutOfMemoryException {
                imhotepSession.createDynamicMetric(name);
                return null;
            }
        });
    }

    @Override
    public void updateDynamicMetric(final String name, final int[] deltas) {
        executeRuntimeException(nullBuf, new ThrowingFunction<ImhotepSession, Object>() {
            @Override
            public Object apply(final ImhotepSession imhotepSession) throws ImhotepOutOfMemoryException {
                imhotepSession.updateDynamicMetric(name, deltas);
                return null;
            }
        });
    }

    @Override
    public void conditionalUpdateDynamicMetric(final String name, final RegroupCondition[] conditions, final int[] deltas) {
        executeRuntimeException(nullBuf, new ThrowingFunction<ImhotepSession, Object>() {
            @Override
            public Object apply(final ImhotepSession imhotepSession) {
                imhotepSession.conditionalUpdateDynamicMetric(name, conditions, deltas);
                return null;
            }
        });
    }

    @Override
    public void groupConditionalUpdateDynamicMetric(final String name, final int[] groups, final RegroupCondition[] conditions, final int[] deltas) {
        executeRuntimeException(nullBuf, new ThrowingFunction<ImhotepSession, Object>() {
            @Override
            public Object apply(final ImhotepSession imhotepSession) {
                imhotepSession.groupConditionalUpdateDynamicMetric(name, groups, conditions, deltas);
                return null;
            }
        });
    }

    @Override
    public void groupQueryUpdateDynamicMetric(final String name, final int[] groups, final Query[] conditions, final int[] deltas) {
        executeRuntimeException(nullBuf, new ThrowingFunction<ImhotepSession, Object>() {
            @Override
            public Object apply(final ImhotepSession imhotepSession) throws ImhotepOutOfMemoryException {
                imhotepSession.groupQueryUpdateDynamicMetric(name, groups, conditions, deltas);
                return null;
            }
        });
    }

    private static int validateNumStats(final Integer[] numStatBuf) {
        final int newNumStats = numStatBuf[0];
        for (int i = 1; i < numStatBuf.length; ++i) {
            if (numStatBuf[i] != newNumStats) {
                throw new RuntimeException("bug, one session did not return the same number of stats as the others");
            }
        }
        return newNumStats;
    }

    @Override
    public FTGSIterator getFTGSIterator(final String[] intFields, final String[] stringFields, final long termLimit, final int sortStat) {
        final boolean topTermsByStat = sortStat >= 0;
        final long perSplitTermLimit = topTermsByStat ? 0 : termLimit;
        return mergeFTGSIteratorsForSessions(sessions, termLimit, sortStat, s -> s.getFTGSIterator(intFields, stringFields, perSplitTermLimit));
    }

    @Override
    public FTGSIterator getSubsetFTGSIterator(final Map<String, long[]> intFields, final Map<String, String[]> stringFields) {
        return mergeFTGSIteratorsForSessions(sessions, -1, -1, s -> s.getSubsetFTGSIterator(intFields, stringFields));
    }

    public final DocIterator getDocIterator(final String[] intFields, final String[] stringFields) throws ImhotepOutOfMemoryException {
        final Closer closer = Closer.create();
        try {
            final List<DocIterator> docIterators = Lists.newArrayList();
            for (final ImhotepSession session : sessions) {
                docIterators.add(closer.register(session.getDocIterator(intFields, stringFields)));
            }
            return new DocIteratorMerger(docIterators, intFields.length, stringFields.length);
        } catch (final Throwable t) {
            Closeables2.closeQuietly(closer, log);
            throw Throwables2.propagate(t, ImhotepOutOfMemoryException.class);
        }
    }

    public RawFTGSIterator getFTGSIteratorSplit(final String[] intFields, final String[] stringFields, final int splitIndex, final int numSplits, final long termLimit) {
        final RawFTGSIterator[] splits = new RawFTGSIterator[sessions.length];
        try {
            executeSessions(getSplitBufferThreads, splits, imhotepSession -> imhotepSession.getFTGSIteratorSplit(intFields, stringFields, splitIndex, numSplits, termLimit));
        } catch (final Throwable t) {
            Closeables2.closeAll(log, splits);
            throw Throwables.propagate(t);
        }
        RawFTGSIterator merger = new RawFTGSMerger(Arrays.asList(splits), numStats, null);
        if(termLimit > 0) {
            merger = new TermLimitedRawFTGSIterator(merger, termLimit);
        }
        return merger;
    }

    @Override
    public RawFTGSIterator getSubsetFTGSIteratorSplit(final Map<String, long[]> intFields, final Map<String, String[]> stringFields, final int splitIndex, final int numSplits) {
        final RawFTGSIterator[] splits = new RawFTGSIterator[sessions.length];
        try {
            executeSessions(getSplitBufferThreads, splits, imhotepSession -> imhotepSession.getSubsetFTGSIteratorSplit(intFields, stringFields, splitIndex, numSplits));
        } catch (final Throwable t) {
            Closeables2.closeAll(log, splits);
            throw Throwables.propagate(t);
        }
        return new RawFTGSMerger(Arrays.asList(splits), numStats, null);
    }

    @Override
    public RawFTGSIterator[] getSubsetFTGSIteratorSplits(
            final Map<String, long[]> intFields,
            final Map<String, String[]> stringFields) {
        throw new UnsupportedOperationException();
    }

    @Override
    public RawFTGSIterator mergeFTGSSplit(final String[] intFields, final String[] stringFields, final String sessionId, final InetSocketAddress[] nodes, final int splitIndex, final long termLimit, final int sortStat) {
        final boolean topTermsByStat = sortStat >= 0;
        final long perSplitTermLimit = topTermsByStat ? 0 : termLimit;

        final ImhotepRemoteSession[] remoteSessions = getRemoteSessions(sessionId, nodes);

        return mergeFTGSIteratorsForSessions(remoteSessions, termLimit, sortStat, s -> s.getFTGSIteratorSplit(intFields, stringFields, splitIndex, nodes.length, perSplitTermLimit));
    }

    private RawFTGSIterator mergeFTGSIteratorsForSessions(
            final ImhotepSession[] imhotepSessions,
            final long termLimit,
            final int sortStat,
            final ThrowingFunction<ImhotepSession, FTGSIterator> getIteratorFromSession
    ) {
        if (imhotepSessions.length == 1) {
            try {
                final FTGSIterator iterator = getIteratorFromSession.apply(imhotepSessions[0]);
                if (sortStat >= 0 && termLimit > 0) {
                    return FTGSIteratorUtil.getTopTermsFTGSIterator(iterator, termLimit, numStats, sortStat);
                } else {
                    if (iterator instanceof RawFTGSIterator) {
                        return (RawFTGSIterator)iterator;
                    } else {
                        return persist(iterator);
                    }
                }
            } catch (Exception e) {
                throw Throwables.propagate(e);
            }
        }
        final FTGSIterator[] iterators = new FTGSIterator[imhotepSessions.length];

        try {
            execute(mergeSplitBufferThreads, iterators, imhotepSessions, getIteratorFromSession);
        } catch (final Throwable t) {
            Closeables2.closeAll(log, iterators);
            throw Throwables.propagate(t);
        }
        return parallelMergeFTGS(iterators, termLimit, sortStat);
    }

    private ImhotepRemoteSession[] getRemoteSessions(final String sessionId, final InetSocketAddress[] nodes) {
        final ImhotepRemoteSession[] remoteSessions = new ImhotepRemoteSession[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            remoteSessions[i] = createImhotepRemoteSession(nodes[i], sessionId, tempFileSizeBytesLeft);
            remoteSessions[i].setNumStats(numStats);
            remoteSessions[i].addObserver(new RelayObserver());
        }
        return remoteSessions;
    }

    @Override
    public RawFTGSIterator mergeSubsetFTGSSplit(final Map<String, long[]> intFields, final Map<String, String[]> stringFields, final String sessionId, final InetSocketAddress[] nodes, final int splitIndex) {
        final ImhotepRemoteSession[] remoteSessions = getRemoteSessions(sessionId, nodes);
        return mergeFTGSIteratorsForSessions(remoteSessions, 0, -1, (s) -> s.getSubsetFTGSIteratorSplit(intFields, stringFields, splitIndex, nodes.length));
    }

    @Override
    public GroupStatsIterator mergeDistinctSplit(final String field, final boolean isIntField,
                                    final String sessionId, final InetSocketAddress[] nodes,
                                    final int splitIndex) {
        final String[] intFields = isIntField ? new String[]{field} : new String[0];
        final String[] stringFields = isIntField ? new String[0] : new String[]{field};
        final RawFTGSIterator iterator = mergeFTGSSplit(intFields, stringFields, sessionId, nodes, splitIndex, 0, -1);
        return FTGSIteratorUtil.calculateDistinct(iterator, getNumGroups());
    }

    protected abstract ImhotepRemoteSession createImhotepRemoteSession(InetSocketAddress address,
                                                                       String sessionId,
                                                                       AtomicLong tempFileSizeBytesLeft);

    /**
     * shuffle the split FTGSIterators such that the workload of iteration is evenly split among the split processing threads
     * @param closer
     * @param iterators
     * @return the reshuffled FTGSIterator array
     * @throws IOException
     */
    private RawFTGSIterator[] parallelDisjointSplitAndMerge(final Closer closer, final FTGSIterator[] iterators) throws IOException {
        final RawFTGSIterator[][] iteratorSplits = new RawFTGSIterator[iterators.length][];

        final int numSplits = Math.max(1, Runtime.getRuntime().availableProcessors()/2);
        try {
            execute(iteratorSplits, iterators, iterator -> new FTGSSplitter(
                    iterator,
                    numSplits,
                    numStats,
                    981044833,
                    tempFileSizeBytesLeft
            ).getFtgsIterators());
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            for (RawFTGSIterator[] splits : iteratorSplits) {
                if (splits != null) {
                    for (final RawFTGSIterator split : splits) {
                        closer.register(split);
                    }
                }
            }
        }

        final RawFTGSIterator[] mergers = new RawFTGSIterator[numSplits];
        for (int j = 0; j < numSplits; j++) {
            final List<RawFTGSIterator> splits = Lists.newArrayList();
            for (int i = 0; i < iterators.length; i++) {
                splits.add(iteratorSplits[i][j]);
            }
            mergers[j] = closer.register(new RawFTGSMerger(splits, numStats, null));
        }

        return mergers;
    }

    private RawFTGSIterator parallelMergeFTGS(final FTGSIterator[] iterators, final long termLimit, final int sortStat) {
        final Closer closer = Closer.create();
        try {
            final RawFTGSIterator[] mergers = parallelDisjointSplitAndMerge(closer, iterators);
            final RawFTGSIterator[] persistedMergers = new RawFTGSIterator[mergers.length];
            closer.register(Closeables2.forArray(log, persistedMergers));
            execute(mergeSplitBufferThreads, persistedMergers, mergers, this::persist);

            final RawFTGSIterator interleaver = new SortedFTGSInterleaver(persistedMergers);
            if (termLimit > 0) {
                if (sortStat >= 0) {
                    return FTGSIteratorUtil.getTopTermsFTGSIterator(interleaver, termLimit, numStats, sortStat);
                } else {
                    return new TermLimitedRawFTGSIterator(interleaver, termLimit);
                }
            } else {
                return interleaver;
            }
        } catch (final Throwable t) {
            Closeables2.closeQuietly(closer, log);
            throw Throwables.propagate(t);
        }
    }

    private RawFTGSIterator persist(final FTGSIterator iterator) throws IOException {
        return FTGSIteratorUtil.persist(log, iterator, numStats, tempFileSizeBytesLeft);
    }

    public RawFTGSIterator[] getFTGSIteratorSplits(final String[] intFields,
                                                   final String[] stringFields,
                                                   final long termLimit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rebuildAndFilterIndexes(final List<String> intFields,
                                final List<String> stringFields)
        throws ImhotepOutOfMemoryException {
        executeMemoryException(nullBuf, new ThrowingFunction<ImhotepSession, Object>() {
            @Override
            public Object apply(final ImhotepSession imhotepSession) throws ImhotepOutOfMemoryException {
                imhotepSession.rebuildAndFilterIndexes(intFields, stringFields);
                return null;
          }
        });
    }

    @Override
    public final void close() {
        closeWithOptionalPerformanceStats(false);
    }

    @Nullable
    private PerformanceStats[] closeWithOptionalPerformanceStats(boolean getPerformanceStats) {
        if (closed) {
            return null;
        }
        closed = true;
        final PerformanceStats[] perSessionStats;
        try {
            preClose();
        } finally {
            try {
                if(getPerformanceStats) {
                    perSessionStats = new PerformanceStats[sessions.length];
                    executeRuntimeException(perSessionStats, ImhotepSession::closeAndGetPerformanceStats);
                } else {
                    perSessionStats = null;
                    executeRuntimeException(nullBuf, imhotepSession -> { imhotepSession.close(); return null;});
                }
            } finally {
                postClose();
            }
        }
        return perSessionStats;
    }

    @Override
    public void resetGroups() {
        executeRuntimeException(nullBuf, new ThrowingFunction<ImhotepSession, Object>() {
            @Override
            public Object apply(final ImhotepSession imhotepSession) throws ImhotepOutOfMemoryException {
                imhotepSession.resetGroups();
                return null;
            }
        });
    }

    @Override
    public long getNumDocs() {
        long numDocs = 0;
        for(final ImhotepSession session: sessions) {
            numDocs += session.getNumDocs();
        }
        return numDocs;
    }

    @Override
    public PerformanceStats getPerformanceStats(final boolean reset) {
        final PerformanceStats[] stats = new PerformanceStats[sessions.length];
        executeRuntimeException(stats, imhotepSession -> imhotepSession.getPerformanceStats(reset));

        return combinePerformanceStats(reset, stats);
    }

    // Combination rules are different for local sessions vs what is done in RemoteImhotepMultiSession for remote sessions
    protected PerformanceStats combinePerformanceStats(boolean reset, PerformanceStats[] stats) {
        if(stats == null) {
            return null;
        }
        final PerformanceStats.Builder builder = PerformanceStats.builder();
        for (final PerformanceStats stat : stats) {
            if(stat != null) {
                builder.add(stat);
            }
        }
        long cpuTotalTime = builder.getCpuTime();
        for (final InstrumentedThreadFactory factory: threadFactories) {
            final InstrumentedThreadFactory.PerformanceStats factoryPerformanceStats = factory.getPerformanceStats();
            if (factoryPerformanceStats != null) {
                cpuTotalTime += factoryPerformanceStats.cpuTotalTime;
            }
        }
        builder.setCpuTime(cpuTotalTime - savedCPUTime);


        // All sessions share the same AtomicLong tempFileSizeBytesLeft,
        // so value accumulated in builder::ftgsTempFileSize is wrong
        // Calculating and setting correct value.
        final long tempFileSize = (tempFileSizeBytesLeft == null)? 0 : tempFileSizeBytesLeft.get();
        builder.setFtgsTempFileSize(savedTempFileSizeValue - tempFileSize);
        if (reset) {
           savedTempFileSizeValue = tempFileSize;
           savedCPUTime = cpuTotalTime;
        }

        return builder.build();
    }

    @Override
    public PerformanceStats closeAndGetPerformanceStats() {
        final PerformanceStats[] perSessionStats = closeWithOptionalPerformanceStats(true);
        return combinePerformanceStats(false, perSessionStats);
    }

    protected void preClose() {
        for (final InstrumentedThreadFactory factory: threadFactories) {
            try {
                factory.close();
            }
            catch (final IOException e) {
                log.warn(e);
            }
        }
        try {
            if (lastIterator != null) {
                Closeables2.closeQuietly(lastIterator, log);
                lastIterator = null;
            }
        } finally {
            getSplitBufferThreads.shutdown();
            mergeSplitBufferThreads.shutdown();
        }
    }

    protected void postClose() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                log.warn("executor did not shut down, continuing anyway");
            }
        } catch (final InterruptedException e) {
            log.warn(e);
        }
    }

    protected <R> void executeRuntimeException(final R[] ret, final ThrowingFunction<? super T, ? extends R> function) {
        try {
            executeSessions(ret, function);
        } catch (final ExecutionException e) {
            throw Throwables.propagate(e.getCause());
        }
    }

    protected <R> void executeMemoryException(final R[] ret, final ThrowingFunction<? super T, ? extends R> function) throws ImhotepOutOfMemoryException {
        try {
            executeSessions(ret, function);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof ImhotepOutOfMemoryException) {
                throw new ImhotepOutOfMemoryException(cause);
            } else {
                throw new RuntimeException(cause);
            }
        }
    }

    @Override
    public abstract void writeFTGSIteratorSplit(String[] intFields, String[] stringFields,
                                                int splitIndex, int numSplits, long termLimit, Socket socket)
        throws ImhotepOutOfMemoryException;

    protected final <E, T> void execute(final ExecutorService es,
                                        final T[] ret, final E[] things,
                                        final ThrowingFunction<? super E, ? extends T> function)
        throws ExecutionException {
        final List<Future<T>> futures = new ArrayList<>(things.length);
        for (final E thing : things) {
            futures.add(es.submit(new Callable<T>() {
                @Override
                public T call() throws Exception {
                    return function.apply(thing);
                }
            }));
        }

        Throwable t = null;

        for (int i = 0; i < futures.size(); ++i) {
            try {
                final Future<T> future = futures.get(i);
                ret[i] = future.get();
            } catch (final Throwable t2) {
                t = t2;
            }
        }
        if (t != null) {
            safeClose();
            throw Throwables2.propagate(t, ExecutionException.class);
        }
    }

    protected final <E, T> void execute(final T[] ret, final E[] things,
                                        final ThrowingFunction<? super E, ? extends T> function)
        throws ExecutionException {
        execute(executor, ret, things, function);
    }

    protected <R> void executeSessions(final ExecutorService es,
                                       final R[] ret,
                                       final ThrowingFunction<? super T, ? extends R> function)
        throws ExecutionException {
        execute(es, ret, sessions, function);
    }

    protected <R> void executeSessions(final R[] ret,
                                       final ThrowingFunction<? super T, ? extends R> function)
        throws ExecutionException {
        execute(executor, ret, sessions, function);
    }

    protected static interface ThrowingFunction<K, V> {
        V apply(K k) throws Exception;
    }

    protected void safeClose() {
        try {
            close();
        } catch (final Exception e) {
            log.error("error closing session", e);
        }
    }
}
