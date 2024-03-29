/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.translators;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.connector.source.SourceSplit;
import org.apache.flink.streaming.api.graph.SimpleTransformationTranslator;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.streaming.api.graph.TransformationTranslator;
import org.apache.flink.streaming.api.operators.SourceOperatorFactory;
import org.apache.flink.streaming.api.transformations.SourceTransformation;

import java.util.Collection;
import java.util.Collections;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A {@link TransformationTranslator} for the {@link SourceTransformation}.
 *
 * @param <OUT> The type of the elements that this source produces.
 */
@Internal
public class SourceTransformationTranslator<OUT, SplitT extends SourceSplit, EnumChkT>
        extends SimpleTransformationTranslator<OUT, SourceTransformation<OUT, SplitT, EnumChkT>> {

    @Override
    protected Collection<Integer> translateForBatchInternal(
            final SourceTransformation<OUT, SplitT, EnumChkT> transformation,
            final Context context) {

        return translateInternal(
                transformation, context, false /* don't emit progressive watermarks */);
    }

    @Override
    protected Collection<Integer> translateForStreamingInternal(
            final SourceTransformation<OUT, SplitT, EnumChkT> transformation,
            final Context context) {

        return translateInternal(transformation, context, true /* emit progressive watermarks */);
    }

    //多易教育: 针对SourceTransformation的转译方法
    // 通过env.fromSource()创建的SourceTransformation，就会走到这里
    private Collection<Integer> translateInternal(
            final SourceTransformation<OUT, SplitT, EnumChkT> transformation,  //多易教育: 本方法只接受 SourceTransformation
            final Context context,
            //多易教育: 流批模式的转译差别就在此处，流为true，批为false
            boolean emitProgressiveWatermarks) {
        checkNotNull(transformation);
        checkNotNull(context);

        final StreamGraph streamGraph = context.getStreamGraph();
        final String slotSharingGroup = context.getSlotSharingGroup();
        final int transformationId = transformation.getId();
        final ExecutionConfig executionConfig = streamGraph.getExecutionConfig();
        //多易教育: 与传统架构不同；
        // 传统架构中的operatorFactory是在Transformation构造时指定的（SimpleUdfStreamOperatorFactory,与map/filter等相同），
        // 而新架构中，是在Translator中生成: SourceOperatorFactory
        SourceOperatorFactory<OUT> operatorFactory =
                new SourceOperatorFactory<>(
                        transformation.getSource(),
                        transformation.getWatermarkStrategy(),
                        emitProgressiveWatermarks);

        operatorFactory.setChainingStrategy(transformation.getChainingStrategy());
        //多易教育: 添加sourceNode
        // 内部依然是addOperator()，只是传入的可执行类为： SourceOperatorStreamTask.class
        streamGraph.addSource(
                transformationId,
                slotSharingGroup,
                transformation.getCoLocationGroupKey(),
                operatorFactory,
                null,  //多易教育: 源节点的输入类型为null
                transformation.getOutputType(),
                "Source: " + transformation.getName());

        //多易教育: 如果transformation设置了并行度，则使用；否则使用executionConfig的并行度配置
        final int parallelism =
                transformation.getParallelism() != ExecutionConfig.PARALLELISM_DEFAULT
                        ? transformation.getParallelism()
                        : executionConfig.getParallelism();

        streamGraph.setParallelism(transformationId, parallelism);
        streamGraph.setMaxParallelism(transformationId, transformation.getMaxParallelism());
        return Collections.singleton(transformationId);
    }
}
