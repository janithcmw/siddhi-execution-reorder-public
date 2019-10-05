/*
 * Copyright (c)  2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.siddhi.extension.execution.reorder;

import io.siddhi.annotation.Example;
import io.siddhi.annotation.Extension;
import io.siddhi.annotation.Parameter;
import io.siddhi.annotation.ParameterOverload;
import io.siddhi.annotation.util.DataType;
import io.siddhi.core.config.SiddhiAppContext;
import io.siddhi.core.config.SiddhiQueryContext;
import io.siddhi.core.event.ComplexEvent;
import io.siddhi.core.event.ComplexEventChunk;
import io.siddhi.core.event.stream.MetaStreamEvent;
import io.siddhi.core.event.stream.StreamEvent;
import io.siddhi.core.event.stream.StreamEventCloner;
import io.siddhi.core.event.stream.holder.StreamEventClonerHolder;
import io.siddhi.core.event.stream.populater.ComplexEventPopulater;
import io.siddhi.core.exception.SiddhiAppCreationException;
import io.siddhi.core.executor.ExpressionExecutor;
import io.siddhi.core.query.processor.ProcessingMode;
import io.siddhi.core.query.processor.Processor;
import io.siddhi.core.query.processor.SchedulingProcessor;
import io.siddhi.core.query.processor.stream.StreamProcessor;
import io.siddhi.core.util.Scheduler;
import io.siddhi.core.util.config.ConfigReader;
import io.siddhi.core.util.snapshot.state.State;
import io.siddhi.core.util.snapshot.state.StateFactory;
import io.siddhi.query.api.definition.AbstractDefinition;
import io.siddhi.query.api.definition.Attribute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The following code conducts reordering of an out-of-order event stream.
 * This implements the K-Slack based disorder handling algorithm which was originally described in
 * https://www2.informatik.uni-erlangen.de/publication/download/IPDPS2013.pdf
 */
@Extension(
        name = "kslack",
        namespace = "reorder",
        description = "Stream processor performs reordering of out-of-order events using " +
                "[K-Slack algorithm](https://www2.informatik.uni-erlangen.de/publication/download/IPDPS2013.pdf).",
        parameters = {
                @Parameter(name = "timestamp",
                        description = "The event timestamp on which the events should be ordered.",
                        type = {DataType.LONG},
                        dynamic = true),
                @Parameter(name = "timeout",
                        description = "A timeout value in milliseconds, where the buffered events who are older " +
                                "than the given timeout period get flushed every second.",
                        defaultValue = "`-1` (timeout is infinite)",
                        type = {DataType.LONG},
                        optional = true),
                @Parameter(name = "max.k",
                        description = "The maximum K-Slack window threshold ('K' parameter).",
                        defaultValue = "`9,223,372,036,854,775,807` (The maximum Long value)",
                        type = {DataType.LONG},
                        optional = true),
                @Parameter(name = "discard.late.arrival",
                        description = "If set to `true` the processor would discarded the out-of-order " +
                                "events arriving later than the K-Slack window, and in otherwise it allows " +
                                "the late arrivals to proceed.",
                        defaultValue = "false",
                        type = {DataType.BOOL},
                        optional = true)
        },
        parameterOverloads = {
                @ParameterOverload(parameterNames = {"timestamp"}),
                @ParameterOverload(parameterNames = {"timestamp", "timeout"}),
                @ParameterOverload(parameterNames = {"timestamp", "discard.late.arrival"}),
                @ParameterOverload(parameterNames = {"timestamp", "timeout", "max.k"}),
                @ParameterOverload(parameterNames = {"timestamp", "timeout", "discard.late.arrival"}),
                @ParameterOverload(parameterNames = {"timestamp", "timeout", "max.k", "discard.late.arrival"})
        },
        examples = @Example(
                syntax = "define stream StockStream (eventTime long, symbol string, volume long);\n\n" +
                        "@info(name = 'query1')\n" +
                        "from StockStream#reorder:kslack(eventTime, 5000L)\n" +
                        "select eventTime, symbol, volume\n" +
                        "insert into OutputStream;",
                description = "The query reorders events based on the 'eventTime' attribute value, and " +
                        "it forcefully flushes all the events who have arrived older " +
                        "than the given 'timeout' value (`5000` milliseconds) every second.")
)
public class KSlackExtension extends StreamProcessor<KSlackExtension.KSlackState> implements SchedulingProcessor {
    private ExpressionExecutor timestampExecutor;
    private long maxK = Long.MAX_VALUE;
    private long timeoutDuration = -1L;
    private boolean expireFlag = false;
    private Scheduler scheduler;
    private ReentrantLock lock = new ReentrantLock();
    private SiddhiAppContext siddhiAppContext;
    private boolean needScheduling = false;

    @Override
    public void start() {
        if (timeoutDuration != -1L) {
            KSlackState state = stateHolder.getState();
            try {
                if (state.lastScheduledTimestamp < 0) {
                    state.lastScheduledTimestamp = this.siddhiAppContext.getTimestampGenerator().currentTime() +
                            timeoutDuration;
                    scheduler.notifyAt(state.lastScheduledTimestamp);
                }
            } finally {
                stateHolder.returnState(state);
            }
        }
    }

    @Override
    public void stop() {
        //Do nothing
    }

    @Override
    protected void process(ComplexEventChunk<StreamEvent> streamEventChunk, Processor nextProcessor,
                           StreamEventCloner streamEventCloner, ComplexEventPopulater complexEventPopulater,
                           KSlackState state) {
        ComplexEventChunk<StreamEvent> complexEventChunk = new ComplexEventChunk<StreamEvent>(true);
        synchronized (state) {
            try {
                lock.lock();
                while (streamEventChunk.hasNext()) {
                    StreamEvent event = streamEventChunk.next();

                    if (event.getType() != ComplexEvent.Type.TIMER) {

                        streamEventChunk.remove();
                        //We might have the rest of the events linked to this event forming a chain.

                        long timestamp = (Long) timestampExecutor.execute(event);

                        if (expireFlag) {
                            if (timestamp < state.lastSentTimeStamp) {
                                continue;
                            }
                        }

                        if (needScheduling) {
                            long currentTime = this.siddhiAppContext.getTimestampGenerator().currentTime();
                            state.lastScheduledTimestamp = state.lastScheduledTimestamp +
                                    Math.round(Math.ceil((currentTime - state.lastScheduledTimestamp) / 1000.0)) * 1000;
                            scheduler.notifyAt(state.lastScheduledTimestamp);
                            needScheduling = false;
                        }
                        ArrayList<StreamEvent> eventList;
                        eventList = state.eventTreeMap.get(timestamp);
                        if (eventList == null) {
                            eventList = new ArrayList<StreamEvent>();
                        }
                        eventList.add(event);
                        state.eventTreeMap.put(timestamp, eventList);

                        if (timestamp > state.greatestTimestamp) {
                            state.greatestTimestamp = timestamp;
                            long minTimestamp = state.eventTreeMap.firstKey();
                            long timeDifference = state.greatestTimestamp - minTimestamp;

                            if (timeDifference > state.k) {
                                if (timeDifference < maxK) {
                                    state.k = timeDifference;
                                } else {
                                    state.k = maxK;
                                }
                            }

                            Iterator<Map.Entry<Long, ArrayList<StreamEvent>>> entryIterator =
                                    state.eventTreeMap.entrySet().iterator();
                            while (entryIterator.hasNext()) {
                                Map.Entry<Long, ArrayList<StreamEvent>> entry = entryIterator.next();
                                ArrayList<StreamEvent> list = state.expiredEventTreeMap.get(entry.getKey());

                                if (list != null) {
                                    list.addAll(entry.getValue());
                                } else {
                                    state.expiredEventTreeMap.put(entry.getKey(), entry.getValue());
                                }
                            }
                            state.eventTreeMap = new TreeMap<Long, ArrayList<StreamEvent>>();
                            entryIterator = state.expiredEventTreeMap.entrySet().iterator();
                            while (entryIterator.hasNext()) {
                                Map.Entry<Long, ArrayList<StreamEvent>> entry = entryIterator.next();
                                if (entry.getKey() + state.k <= state.greatestTimestamp) {
                                    entryIterator.remove();
                                    ArrayList<StreamEvent> timeEventList = entry.getValue();
                                    state.lastSentTimeStamp = entry.getKey();

                                    for (StreamEvent aTimeEventList : timeEventList) {
                                        complexEventChunk.add(aTimeEventList);
                                    }
                                } else {
                                    break;
                                }
                            }
                        }
                    } else {
                        if (timeoutDuration != -1L) {
                            if (state.expiredEventTreeMap.size() > 0) {
                                onTimerEvent(state.expiredEventTreeMap, nextProcessor, event.getTimestamp());
                            }
                            if (state.expiredEventTreeMap.size() > 0) {
                                state.lastScheduledTimestamp = state.lastScheduledTimestamp + 1000;
                                scheduler.notifyAt(state.lastScheduledTimestamp);
                                needScheduling = false;
                            } else {
                                needScheduling = true;
                            }
                        }
                    }
                }
            } catch (ArrayIndexOutOfBoundsException ec) {
                //This happens due to user specifying an invalid field index.
                throw new SiddhiAppCreationException("The very first parameter must be an Integer with a valid " +
                        " field index (0 to (fieldsLength-1)).");
            } finally {
                lock.unlock();
            }
        }
        if (nextProcessor != null) {
            nextProcessor.process(complexEventChunk);
        }
    }

    @Override
    protected StateFactory<KSlackState> init(MetaStreamEvent metaStreamEvent, AbstractDefinition abstractDefinition,
                                             ExpressionExecutor[] expressionExecutors, ConfigReader configReader,
                                             StreamEventClonerHolder streamEventClonerHolder,
                                             boolean outputExpectsExpiredEvents,
                                             boolean findToBeExecuted, SiddhiQueryContext siddhiQueryContext) {
        this.siddhiAppContext = siddhiQueryContext.getSiddhiAppContext();
        if (attributeExpressionLength > 4) {
            throw new SiddhiAppCreationException("Maximum four input parameters can be specified for KSlack. " +
                    " Timestamp field (long), k-slack buffer expiration time-out window (long), Max_K size (long), "
                    + "and boolean  flag to indicate whether the late events should get discarded. But found " +
                    attributeExpressionLength + " attributes.");
        }

        //This is the most basic case. Here we do not use a timer. The basic K-slack algorithm is implemented.
        if (attributeExpressionExecutors.length == 1) {
            if (attributeExpressionExecutors[0].getReturnType() == Attribute.Type.LONG) {
                timestampExecutor = attributeExpressionExecutors[0];
            } else {
                throw new SiddhiAppCreationException("Invalid parameter type found for the first argument of " +
                        "reorder:kslack() function. Required LONG, but found " +
                        attributeExpressionExecutors[0].getReturnType());
            }
            //In the following case we have the timer operating in background. But we do not impose a K-slack window
            // length or  or to drop late events.
        } else if (attributeExpressionExecutors.length == 2) {
            if (attributeExpressionExecutors[0].getReturnType() == Attribute.Type.LONG) {
                timestampExecutor = attributeExpressionExecutors[0];
            } else {
                throw new SiddhiAppCreationException("Invalid parameter type found for the first argument of " +
                        " reorder:kslack() function. Required LONG, but found " +
                        attributeExpressionExecutors[0].getReturnType());
            }

            if (attributeExpressionExecutors[1].getReturnType() == Attribute.Type.LONG) {
                timeoutDuration = (Long) attributeExpressionExecutors[1].execute(null);

            } else if (attributeExpressionExecutors[1].getReturnType() == Attribute.Type.BOOL) {
                expireFlag = (Boolean) attributeExpressionExecutors[1].execute(null);
            } else {
                throw new SiddhiAppCreationException("Invalid parameter type found for the second argument of " +
                        " reorder:kslack() function. Required LONG or BOOL, but found " +
                        attributeExpressionExecutors[1].getReturnType());
            }

            //In the third case we have both the timer operating in the background and we have also specified a K-slack
            // window length or to drop late events.
        } else if (attributeExpressionExecutors.length == 3) {
            if (attributeExpressionExecutors[0].getReturnType() == Attribute.Type.LONG) {
                timestampExecutor = attributeExpressionExecutors[0];
            } else {
                throw new SiddhiAppCreationException("Invalid parameter type found for the first argument of " +
                        " reorder:kslack() function. Required LONG, but found " +
                        attributeExpressionExecutors[0].getReturnType());
            }

            if (attributeExpressionExecutors[1].getReturnType() == Attribute.Type.LONG) {
                timeoutDuration = (Long) attributeExpressionExecutors[1].execute(null);
            } else {
                throw new SiddhiAppCreationException("Invalid parameter type found for the second argument of " +
                        " reorder:kslack() function. Required LONG, but found " +
                        attributeExpressionExecutors[1].getReturnType());
            }

            if (attributeExpressionExecutors[2].getReturnType() == Attribute.Type.LONG) {
                maxK = (Long) attributeExpressionExecutors[2].execute(null);
            } else if (attributeExpressionExecutors[2].getReturnType() == Attribute.Type.BOOL) {
                expireFlag = (Boolean) attributeExpressionExecutors[2].execute(null);
            } else {
                throw new SiddhiAppCreationException("Invalid parameter type found for the third argument of " +
                        " reorder:kslack() function. Required LONG or BOOL, but found " +
                        attributeExpressionExecutors[2].getReturnType());
            }

            //In the fourth case we have an additional boolean flag other than the above three parameters. If the flag
            // is set to true any out-of-order events which arrive after the expiration of K-slack are discarded.
        } else if (attributeExpressionExecutors.length == 4) {
            if (attributeExpressionExecutors[0].getReturnType() == Attribute.Type.LONG) {
                timestampExecutor = attributeExpressionExecutors[0];
            } else {
                throw new SiddhiAppCreationException("Invalid parameter type found for the first argument of " +
                        " reorder:kslack() function. Required LONG, but found " +
                        attributeExpressionExecutors[0].getReturnType());
            }

            if (attributeExpressionExecutors[1].getReturnType() == Attribute.Type.LONG) {
                timeoutDuration = (Long) attributeExpressionExecutors[1].execute(null);
            } else {
                throw new SiddhiAppCreationException("Invalid parameter type found for the second argument of " +
                        " reorder:kslack() function. Required LONG, but found " +
                        attributeExpressionExecutors[1].getReturnType());
            }

            if (attributeExpressionExecutors[2].getReturnType() == Attribute.Type.LONG) {
                maxK = (Long) attributeExpressionExecutors[2].execute(null);
            } else {
                throw new SiddhiAppCreationException("Invalid parameter type found for the third argument of " +
                        " reorder:kslack() function. Required LONG, but found " +
                        attributeExpressionExecutors[2].getReturnType());
            }

            if (attributeExpressionExecutors[3].getReturnType() == Attribute.Type.BOOL) {
                expireFlag = (Boolean) attributeExpressionExecutors[3].execute(null);
            } else {
                throw new SiddhiAppCreationException("Invalid parameter type found for the fourth argument of " +
                        " reorder:kslack() function. Required BOOL, but found " +
                        attributeExpressionExecutors[3].getReturnType());
            }
        }

        if (attributeExpressionExecutors[0].getReturnType() == Attribute.Type.LONG) {
            timestampExecutor = attributeExpressionExecutors[0];
        } else {
            throw new SiddhiAppCreationException("Return type expected by KSlack is LONG but found " +
                    attributeExpressionExecutors[0].getReturnType());
        }
        return KSlackState::new;
    }

    @Override
    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public Scheduler getScheduler() {
        return this.scheduler;
    }

    private void onTimerEvent(TreeMap<Long, ArrayList<StreamEvent>> treeMap, Processor nextProcessor,
                              long currentTimestamp) {
        Iterator<Map.Entry<Long, ArrayList<StreamEvent>>> entryIterator = treeMap.entrySet().iterator();
        ComplexEventChunk<StreamEvent> complexEventChunk = new ComplexEventChunk<StreamEvent>(false);

        while (entryIterator.hasNext()) {
            Map.Entry<Long, ArrayList<StreamEvent>> entry = entryIterator.next();
            if (entry.getKey() < timeoutDuration + currentTimestamp) {
                ArrayList<StreamEvent> timeEventList = entry.getValue();
                for (StreamEvent aTimeEventList : timeEventList) {
                    complexEventChunk.add(aTimeEventList);
                }
                entryIterator.remove();
            } else {
                break;
            }
        }
        nextProcessor.process(complexEventChunk);
    }

    @Override
    public List<Attribute> getReturnAttributes() {
        return new ArrayList<>();
    }

    @Override
    public ProcessingMode getProcessingMode() {
        return ProcessingMode.BATCH;
    }

    class KSlackState extends State {
        private TreeMap<Long, ArrayList<StreamEvent>> eventTreeMap;
        private TreeMap<Long, ArrayList<StreamEvent>> expiredEventTreeMap;
        private long lastScheduledTimestamp = -1;
        private long lastSentTimeStamp = -1L;
        private long greatestTimestamp = 0; //Used to track the greatest timestamp of tuples in the stream history.
        private long k = 0; //In the beginning the K is zero.

        public KSlackState() {
            this.eventTreeMap = new TreeMap<>();
            this.expiredEventTreeMap = new TreeMap<>();
        }

        @Override
        public boolean canDestroy() {
            return false;
        }

        @Override
        public Map<String, Object> snapshot() {
            Map<String, Object> state = new HashMap<>();
            state.put("eventTreeMap", eventTreeMap);
            state.put("expiredEventTreeMap", expiredEventTreeMap);
            state.put("lastScheduledTimestamp", lastScheduledTimestamp);
            state.put("lastSentTimeStamp", lastSentTimeStamp);
            state.put("greatestTimestamp", greatestTimestamp);
            state.put("k", k);
            return state;
        }

        @Override
        public void restore(Map<String, Object> state) {
            this.eventTreeMap = (TreeMap<Long, ArrayList<StreamEvent>>) state.get("eventTreeMap");
            this.expiredEventTreeMap = (TreeMap<Long, ArrayList<StreamEvent>>) state.get("expiredEventTreeMap");
            this.lastScheduledTimestamp = (long) state.get("lastScheduledTimestamp");
            this.lastSentTimeStamp = (long) state.get("lastSentTimeStamp");
            this.greatestTimestamp = (long) state.get("greatestTimestamp");
            this.k = (long) state.get("k");
        }
    }
}
