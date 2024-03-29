// Copyright 2020 JanusGraph Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.janusgraph.graphdb.tinkerpop.optimize.step;

import org.apache.commons.collections.iterators.EmptyIterator;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.Profiling;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.ReducingBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.MutableMetrics;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.janusgraph.core.JanusGraphTransaction;
import org.janusgraph.core.MixedIndexAggQuery;
import org.janusgraph.core.PropertyKey;
import org.janusgraph.core.RelationType;
import org.janusgraph.graphdb.internal.ElementCategory;
import org.janusgraph.graphdb.query.graph.GraphCentricQuery;
import org.janusgraph.graphdb.query.graph.JointIndexQuery;
import org.janusgraph.graphdb.query.graph.MixedIndexAggQueryBuilder;
import org.janusgraph.graphdb.query.profile.QueryProfiler;
import org.janusgraph.graphdb.tinkerpop.optimize.JanusGraphTraversalUtil;
import org.janusgraph.graphdb.tinkerpop.profile.TP3ProfileWrapper;
import org.janusgraph.graphdb.types.MixedIndexType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

import static org.janusgraph.graphdb.database.util.IndexRecordUtil.key2Field;

/**
 * A custom step similar to {@link org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep} but
 * uses mixed index query to directly fetch number of satisfying elements without actually fetching the elements.
 *
 * @author Boxuan Li (liboxuan@connect.hku.hk)
 */
public class JanusGraphMixedIndexAggStep<S> extends ReducingBarrierStep<S, Number> implements Profiling {

    private final ArrayList<HasContainer> hasContainers = new ArrayList<>();
    private MixedIndexAggQuery mixedIndexAggQuery = null;
    private boolean done;
    private final Aggregation aggregation;

    public JanusGraphMixedIndexAggStep(JanusGraphStep janusGraphStep, Traversal.Admin<?, ?> traversal, Aggregation agg) {
        super(traversal);
        JanusGraphTransaction tx = JanusGraphTraversalUtil.getTx(traversal);
        aggregation = agg;
        final MixedIndexAggQueryBuilder aggregationQueryBuilder = (MixedIndexAggQueryBuilder) tx.mixedIndexAggQuery();

        final GraphCentricQuery query = janusGraphStep.buildGlobalGraphCentricQuery();

        if (query != null && query.getIndexQuery().isFitted()) {
            final String fieldName = agg.getFieldName();
            boolean isIndexed = false;
            if (fieldName == null) {
                isIndexed = true;
            } else {
                MixedIndexType indexType = (MixedIndexType)query.getIndexQuery().getBackendQuery().getQuery(0).getIndex();
                Optional<? extends Class<?>> dataType = Arrays.stream(indexType.getFieldKeys())
                    .filter(f -> f.getFieldKey().name().equals(fieldName))
                    .map(f -> f.getFieldKey().dataType())
                    .filter(Number.class::isAssignableFrom)
                    .findFirst();
                if (dataType.isPresent()) {
                    isIndexed = true;
                    aggregation.setDataType(dataType.get());
                    RelationType rt = tx.getRelationType(fieldName);
                    if (rt instanceof PropertyKey)
                        aggregation.setFieldName(key2Field(indexType, (PropertyKey) rt));
                }
            }
            if (isIndexed) {
                final JointIndexQuery indexQuery = query.getIndexQuery().getBackendQuery();
                mixedIndexAggQuery = aggregationQueryBuilder.constructIndex(indexQuery,
                    Vertex.class.isAssignableFrom(janusGraphStep.getReturnClass()) ? ElementCategory.VERTEX : ElementCategory.EDGE);

            }
        }
    }

    @Override
    public Number projectTraverser(Traverser.Admin<S> traverser) {
        return traverser.bulk();
    }

    @Override
    public Traverser.Admin<Number> processNextStart() {
        if (!this.done) {
            this.done = true;
            return getTraversal().getTraverserGenerator().generate(this.mixedIndexAggQuery.execute(aggregation), (Step) this, 1L);
        } else {
            return getTraversal().getTraverserGenerator().generate(EmptyIterator.INSTANCE.next(), (Step) this, 1L);
        }
    }

    @Override
    public String toString() {
        if (this.hasContainers.isEmpty()) {
            return super.toString();
        }
        return StringFactory.stepString(this, this.hasContainers);
    }

    @Override
    public void setMetrics(final MutableMetrics metrics) {
        QueryProfiler queryProfiler = new TP3ProfileWrapper(metrics);
        mixedIndexAggQuery.observeWith(queryProfiler);
    }

    public MixedIndexAggQuery getMixedIndexAggQuery() {
        return mixedIndexAggQuery;
    }
}
