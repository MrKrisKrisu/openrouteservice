/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library;
 *  if not, see <https://www.gnu.org/licenses/>.
 */
package org.heigit.ors.matrix.algorithms.core;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntObjectMap;
import com.graphhopper.GraphHopper;
import com.graphhopper.coll.GHIntObjectHashMap;
import com.graphhopper.routing.EdgeIteratorStateHelper;
import com.graphhopper.routing.QueryGraph;
import com.graphhopper.routing.ch.PreparationWeighting;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.TurnWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.CHGraph;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import org.heigit.ors.matrix.*;
import org.heigit.ors.matrix.algorithms.AbstractMatrixAlgorithm;
import org.heigit.ors.matrix.algorithms.dijkstra.DijkstraManyToMany;
import org.heigit.ors.routing.algorithms.SubGraph;
import org.heigit.ors.routing.graphhopper.extensions.core.CoreDijkstraFilter;
import org.heigit.ors.routing.graphhopper.extensions.core.CoreMatrixFilter;
import org.heigit.ors.routing.graphhopper.extensions.edgefilters.EdgeFilterSequence;
import org.heigit.ors.routing.graphhopper.extensions.edgefilters.ch.DownwardSearchEdgeFilter;
import org.heigit.ors.routing.graphhopper.extensions.storages.AveragedMultiTreeSPEntry;
import org.heigit.ors.routing.graphhopper.extensions.storages.MultiTreeSPEntryItem;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

public class CoreMatrixAlgorithm extends AbstractMatrixAlgorithm {
    protected int coreNodeLevel;
    protected int turnRestrictedNodeLevel;
    protected boolean approximate = false;
    protected int maxNodes;
    protected int maxVisitedNodes = Integer.MAX_VALUE;
    protected int visitedNodes;
    protected boolean finishedFrom;
    protected EdgeExplorer upAndCoreExplorer;
    protected EdgeExplorer targetGraphExplorer;
    PriorityQueue<AveragedMultiTreeSPEntry> upwardQueue;
    IntHashSet coreEntryPoints;
    IntHashSet coreExitPoints;
    IntObjectMap<AveragedMultiTreeSPEntry> bestWeightMap;
    IntObjectMap<List<AveragedMultiTreeSPEntry>> bestWeightMapCore;
    IntObjectMap<AveragedMultiTreeSPEntry> targetMap;
    IntHashSet targetSet;
    private MultiTreeMetricsExtractor pathMetricsExtractor;
    private CoreDijkstraFilter additionalCoreEdgeFilter;
    private int treeEntrySize;
    private CHGraph chGraph;
    private SubGraph targetGraph;
    private boolean hasTurnWeighting = false;
    private TurnWeighting turnWeighting;
    private boolean swap = false;

    @Override
    public void init(MatrixRequest req, GraphHopper gh, Graph graph, FlagEncoder encoder, Weighting weighting) {
        if (weighting instanceof TurnWeighting) {
            hasTurnWeighting = true;
            turnWeighting = (TurnWeighting) weighting;
        }
        weighting = new PreparationWeighting(weighting);
        super.init(req, gh, graph, encoder, weighting);
        try {
            chGraph = graph instanceof CHGraph ? (CHGraph) graph : (CHGraph) ((QueryGraph) graph).getMainGraph();
        } catch (ClassCastException e) {
            throw new ClassCastException(e.getMessage());
        }
        coreNodeLevel = chGraph.getNodes() + 1;
        turnRestrictedNodeLevel = coreNodeLevel + 1;
        maxNodes = graph.getNodes();
        pathMetricsExtractor = new MultiTreeMetricsExtractor(req.getMetrics(), graph, this.encoder, weighting, req.getUnits());
        initCollections(10);
        initFilter(null, chGraph);
    }

    public void init(MatrixRequest req, GraphHopper gh, Graph graph, FlagEncoder encoder, Weighting weighting, EdgeFilter additionalEdgeFilter) {
        this.init(req, gh, graph, encoder, weighting);
        initFilter(additionalEdgeFilter, chGraph);
    }

    public void init(MatrixRequest req, Graph graph, FlagEncoder encoder, Weighting weighting, EdgeFilter additionalEdgeFilter) {
        this.init(req, null, graph, encoder, weighting);
        initFilter(additionalEdgeFilter, chGraph);
    }

    private void initFilter(EdgeFilter additionalEdgeFilter, Graph graph) {
        CoreDijkstraFilter levelFilter = new CoreMatrixFilter((CHGraph) graph);
        if (additionalEdgeFilter != null)
            levelFilter.addRestrictionFilter(additionalEdgeFilter);

        this.setEdgeFilter(levelFilter);
    }

    protected void initCollections(int size) {
        upwardQueue = new PriorityQueue<>(size);
        coreEntryPoints = new IntHashSet(size);
        coreExitPoints = new IntHashSet(size);
        targetSet = new IntHashSet(size);
        bestWeightMap = new GHIntObjectHashMap<>(size);
        bestWeightMapCore = new GHIntObjectHashMap<>(size);
        targetMap = new GHIntObjectHashMap<>(size);
    }

    @Override
    public MatrixResult compute(MatrixLocations srcData, MatrixLocations dstData, int metrics) throws Exception {
        // Search is more efficient for dstData.size > srcData.size, so check if they should be swapped
        checkSwapSrcDst(srcData, dstData);
        if(swap){
            MatrixLocations tmp = srcData;
            srcData = dstData;
            dstData = tmp;
        }
        this.treeEntrySize = srcData.size();

        prepareTargetGraph(dstData.getNodeIds());
        targetSet.addAll(dstData.getNodeIds());
        float[] times = null;
        float[] distances = null;
        float[] weights = null;

        int tableSize = srcData.size() * dstData.size();
        if (MatrixMetricsType.isSet(metrics, MatrixMetricsType.DURATION))
            times = new float[tableSize];
        if (MatrixMetricsType.isSet(metrics, MatrixMetricsType.DISTANCE))
            distances = new float[tableSize];
        if (MatrixMetricsType.isSet(metrics, MatrixMetricsType.WEIGHT))
            weights = new float[tableSize];

        if (!isValid(srcData, dstData)) {
            for (int srcIndex = 0; srcIndex < srcData.size(); srcIndex++)
                pathMetricsExtractor.setEmptyValues(srcIndex, dstData, times, distances, weights);
        } else {

            this.additionalCoreEdgeFilter.setInCore(false);
            this.upAndCoreExplorer = swap ? graph.createEdgeExplorer(DefaultEdgeFilter.inEdges(this.encoder)) : graph.createEdgeExplorer(DefaultEdgeFilter.outEdges(this.encoder));
            runPhaseOutsideCore(srcData);

            this.additionalCoreEdgeFilter.setInCore(true);

            runPhaseInsideCore();

            boolean outputNodeData = false;
            if(outputNodeData) {
                try {
                    int nodeToInspect = dstData.getNodeId(0);
                    int nodeToGoTo = srcData.getNodeId(0);
                    AveragedMultiTreeSPEntry goalNode = targetMap.get(nodeToInspect);

                    if (goalNode != null) {
                        MultiTreeSPEntryItem item = goalNode.getItem(0);
                        int node = goalNode.getAdjNode();
                        while (node != nodeToGoTo) {
                            System.out.print("[");
                            System.out.print(this.graph.getNodeAccess().getLon(node));
                            System.out.print(",");
                            System.out.print(this.graph.getNodeAccess().getLat(node));
                            System.out.print("],");
                            System.out.println();
                            node = item.getParent().getAdjNode();
                            item = item.getParent().getItem(0);
                        }
                        System.out.print("[");
                        System.out.print(this.graph.getNodeAccess().getLon(node));
                        System.out.print(",");
                        System.out.print(this.graph.getNodeAccess().getLat(node));
                        System.out.print("],");
                        System.out.println();
                    }
                    if (goalNode != null) {
                        MultiTreeSPEntryItem item = goalNode.getItem(0);
                        int node = goalNode.getAdjNode();
                        System.out.println("Node " + node + " level " + chGraph.getLevel(node) + " weight " + item.getWeight());
                        while (node != nodeToGoTo) {
                            node = item.getParent().getAdjNode();
                            item = item.getParent().getItem(0);
                            System.out.println("Node " + node + " level " + chGraph.getLevel(node) + " weight " + item.getWeight());
                        }
                    }
                } catch (Exception e) {
                }
            }

            extractMetrics(srcData, dstData, times, distances, weights);
        }

        if(swap){
            MatrixLocations tmp = srcData;
            srcData = dstData;
            dstData = tmp;
            float[][] results = swapResults(srcData, dstData, times, distances, weights);
            times = results[0];
            distances = results[1];
            weights = results[2];
        }
        MatrixResult mtxResult = new MatrixResult(srcData.getLocations(), dstData.getLocations());

        if (MatrixMetricsType.isSet(metrics, MatrixMetricsType.DURATION))
            mtxResult.setTable(MatrixMetricsType.DURATION, times);
        if (MatrixMetricsType.isSet(metrics, MatrixMetricsType.DISTANCE))
            mtxResult.setTable(MatrixMetricsType.DISTANCE, distances);
        if (MatrixMetricsType.isSet(metrics, MatrixMetricsType.WEIGHT))
            mtxResult.setTable(MatrixMetricsType.WEIGHT, weights);

        return mtxResult;
    }

    /**
     * Search is more efficient for low source count and high destination count than the other way around.
     * If there are more sources than destinations, they get swapped and all calculations are done backwards.
     * The final result gets unswapped to return correct results.
     * @param srcData original Source data
     * @param dstData original Destination data
     * @return
     */
    private void checkSwapSrcDst(MatrixLocations srcData, MatrixLocations dstData){
        if(srcData.size() > dstData.size())
            swap = true;
    }

    private void updateTarget(AveragedMultiTreeSPEntry update) {
        int nodeId = update.getAdjNode();
        if(targetSet.contains(nodeId)) {
            if (!targetMap.containsKey(nodeId)) {
                AveragedMultiTreeSPEntry newTarget = new AveragedMultiTreeSPEntry(nodeId, EdgeIterator.NO_EDGE, Double.POSITIVE_INFINITY, true, null, update.getSize());
                newTarget.setOriginalEdge(EdgeIterator.NO_EDGE);
                newTarget.setSubItemOriginalEdgeIds(EdgeIterator.NO_EDGE);
                targetMap.put(nodeId, newTarget);
            }
            AveragedMultiTreeSPEntry target = targetMap.get(nodeId);
            for (int i = 0; i < treeEntrySize; ++i) {
                MultiTreeSPEntryItem targetItem = target.getItem(i);
                double targetWeight = targetItem.getWeight();

                MultiTreeSPEntryItem msptSubItem = update.getItem(i);
                double updateWeight = msptSubItem.getWeight();

                if (targetWeight > updateWeight) {
                    targetItem.setWeight(updateWeight);
                    targetItem.setEdge(msptSubItem.getEdge());
                    targetItem.setOriginalEdge(msptSubItem.getOriginalEdge());
                    targetItem.setParent(msptSubItem.getParent());
//                    targetItem.setUpdate(true);
                }
            }
        }
    }

    private boolean isValid(MatrixLocations srcData, MatrixLocations dstData) {
        return !(!srcData.hasValidNodes() || !dstData.hasValidNodes());
    }

    private void extractMetrics(MatrixLocations srcData, MatrixLocations dstData, float[] times, float[] distances, float[] weights) throws Exception {
        AveragedMultiTreeSPEntry[] destTrees = new AveragedMultiTreeSPEntry[dstData.size()];
        for (int i = 0; i < dstData.size(); i++)
            destTrees[i] = targetMap.get(dstData.getNodeIds()[i]);

        AveragedMultiTreeSPEntry[] originalDestTrees = new AveragedMultiTreeSPEntry[dstData.size()];

        int j = 0;
        for (int i = 0; i < dstData.size(); i++) {
            if (dstData.getNodeIds()[i] != -1) {
                originalDestTrees[i] = destTrees[j];
                ++j;
            } else {
                originalDestTrees[i] = null;
            }
        }
        pathMetricsExtractor.setSwap(swap);
        pathMetricsExtractor.calcValues(originalDestTrees, srcData, dstData, times, distances, weights);
    }

    /**
     * Invert the results matrix (represented by flattened array) in case src and dst were swapped
     * @param srcData the original unswapped source data
     * @param dstData the original unswapped destination data
     * @param times the swapped array of results
     * @param distances the swapped array of results
     * @param weights the swapped array of results
     * @return array of unswapped result arrays [times, distances, weights]
     */
    private float[][] swapResults(MatrixLocations srcData, MatrixLocations dstData, float[] times, float[] distances, float[] weights) {
        boolean hasTimes = times != null;
        boolean hasDistances = distances != null;
        boolean hasWeights = weights != null;
        float[] newTimes = new float[0];
        float[] newDistances = new float[0];
        float[] newWeights = new float[0];
        if(hasTimes)
            newTimes = new float[times.length];
        if(hasDistances)
            newDistances = new float[distances.length];
        if(hasWeights)
            newWeights = new float[weights.length];

        int i = 0;
        int srcSize = srcData.size();
        int dstSize = dstData.size();
        for (int dst = 0; dst < dstSize; dst++){
            for(int src = 0; src < srcSize; src++){
                if(hasTimes)
                    newTimes[dst + src * dstSize] = times[i];
                if(hasDistances)
                    newDistances[dst + src * dstSize] = distances[i];
                if(hasWeights)
                    newWeights[dst + src * dstSize] = weights[i];
                i++;
            }
        }
        return new float[][]{newTimes, newDistances, newWeights};
    }

    /**
     * /
     * /
     * __________OUT-CORE
     * /
     * /
     **/
    private void prepareSourceNodes(int[] from) {
        for (int i = 0; i < from.length; i++) {
            if (from[i] == -1)
                continue;
            //If two queried points are on the same node, this case can occur
            AveragedMultiTreeSPEntry existing = bestWeightMap.getOrDefault(from[i], null);
            if (existing != null) {
                existing.getItem(i).setWeight(0.0);
                upwardQueue.remove(existing);
                existing.updateWeights();
                upwardQueue.add(existing);
                continue;
            }

            AveragedMultiTreeSPEntry newFrom = new AveragedMultiTreeSPEntry(from[i], EdgeIterator.NO_EDGE, 0.0, true, null, from.length);
            newFrom.setOriginalEdge(EdgeIterator.NO_EDGE);
            newFrom.setSubItemOriginalEdgeIds(EdgeIterator.NO_EDGE);

            newFrom.getItem(i).setWeight(0.0);
            newFrom.updateWeights();
            upwardQueue.add(newFrom);

            bestWeightMap.put(from[i], newFrom);
            updateTarget(newFrom);
        }
    }

    /**
     * Phase I: build shortest path tree from all target nodes to the core, only upwards in level.
     * The EdgeFilter in use is a downward search edge filter with reverse access acceptance so that in the last phase of the algorithm, the targetGraph can be explored downwards
     *
     * @param targets the targets that form the seed for target graph building
     */
    public void prepareTargetGraph(int[] targets) {
        PriorityQueue<Integer> localPrioQueue = new PriorityQueue<>(100);
        DownwardSearchEdgeFilter downwardEdgeFilter = new DownwardSearchEdgeFilter(chGraph, encoder, true, this.hasTurnWeighting, swap);
        EdgeExplorer edgeExplorer = swap ? graph.createEdgeExplorer(DefaultEdgeFilter.outEdges(encoder)) : graph.createEdgeExplorer(DefaultEdgeFilter.inEdges(encoder));
        targetGraph = new SubGraph(graph);

        addNodes(targetGraph, localPrioQueue, targets);

        while (!localPrioQueue.isEmpty()) {
            int adjNode = localPrioQueue.poll();
            EdgeIterator iter = edgeExplorer.setBaseNode(adjNode);
            downwardEdgeFilter.setBaseNode(adjNode);
            exploreEntry(localPrioQueue, downwardEdgeFilter, adjNode, iter);
        }
        targetGraphExplorer = targetGraph.createExplorer();
    }

    /**
     * Explore the target graph and build coreExitPoints
     * @param localPrioQueue
     * @param downwardEdgeFilter
     * @param adjNode
     * @param iter
     */
    private void exploreEntry(PriorityQueue<Integer> localPrioQueue, DownwardSearchEdgeFilter downwardEdgeFilter, int adjNode, EdgeIterator iter) {
        while (iter.next()) {
            if (!downwardEdgeFilter.accept(iter))
                continue;
            boolean isNewNode = targetGraph.addEdge(adjNode, iter, true);
            if (isCoreNode(iter.getAdjNode()) && !isCoreNode(iter.getBaseNode())) {
                coreExitPoints.add(iter.getAdjNode());
            } else if(isNewNode) {
                localPrioQueue.add(iter.getAdjNode());
            }

        }
    }

    /**
     * Add nodes to target graph and prioQueue for target graph
     * @param graph
     * @param prioQueue
     * @param nodes
     */
    private void addNodes(SubGraph graph, PriorityQueue<Integer> prioQueue, int[] nodes) {
        for (int i = 0; i < nodes.length; i++) {
            int nodeId = nodes[i];
            if (nodeId >= 0) {
                if (graph != null)
                    graph.addEdge(nodeId, null, true);
                prioQueue.add(nodeId);
                if (isCoreNode(nodeId)) {
                    coreExitPoints.add(nodeId);
                }
            }
        }
    }

    private void runPhaseOutsideCore(MatrixLocations srcData) {
        prepareSourceNodes(srcData.getNodeIds());
        while (!finishedFrom && !isMaxVisitedNodesExceeded()) {
            finishedFrom = !fillEdgesOutsideCore();
        }
    }

    public boolean fillEdgesOutsideCore() {
        if (upwardQueue.isEmpty())
            return false;

        AveragedMultiTreeSPEntry currFrom = upwardQueue.poll();

        if (isCoreNode(currFrom.getAdjNode())) {
            // core entry point, do not relax its edges
            coreEntryPoints.add(currFrom.getAdjNode());
            // for regular CH Dijkstra we don't expect an entry to exist because the picked node is supposed to be already settled
            if (considerTurnRestrictions(currFrom.getAdjNode())) {
                List<AveragedMultiTreeSPEntry> existingEntryList = bestWeightMapCore.get(currFrom.getAdjNode());
                if (existingEntryList == null)
                    initBestWeightMapEntryList(bestWeightMapCore, currFrom.getAdjNode()).add(currFrom);
                else
                    existingEntryList.add(currFrom);
            }
        }
        else
            fillEdgesUpward(currFrom, upwardQueue, bestWeightMap, upAndCoreExplorer);


        visitedNodes++;

        return true;
    }

    List<AveragedMultiTreeSPEntry> initBestWeightMapEntryList(IntObjectMap<List<AveragedMultiTreeSPEntry>> bestWeightMap, int traversalId) {
        if (bestWeightMap.get(traversalId) != null)
            throw new IllegalStateException("Core entry point already exists in best weight map.");

        List<AveragedMultiTreeSPEntry> entryList = new ArrayList<>(5);
        bestWeightMap.put(traversalId, entryList);

        return entryList;
    }

    void fillEdgesUpward(AveragedMultiTreeSPEntry currEdge, PriorityQueue<AveragedMultiTreeSPEntry> prioQueue, IntObjectMap<AveragedMultiTreeSPEntry> bestWeightMap,
                         EdgeExplorer explorer) {
        EdgeIterator iter = explorer.setBaseNode(currEdge.getAdjNode());
        while (iter.next()) {
            if(hasTurnWeighting && !isInORS(iter, currEdge))
                turnWeighting.setInORS(false);

            AveragedMultiTreeSPEntry entry = bestWeightMap.get(iter.getAdjNode());

            if (entry == null) {
                entry = new AveragedMultiTreeSPEntry(iter.getAdjNode(), iter.getEdge(), Double.POSITIVE_INFINITY, true, null, currEdge.getSize());
                boolean addToQueue = iterateMultiTree(currEdge, iter, entry);
                if(addToQueue) {
                    entry.updateWeights();
                    bestWeightMap.put(iter.getAdjNode(), entry);
                    prioQueue.add(entry);
                    updateTarget(entry);
                }
            } else {
                boolean addToQueue = iterateMultiTree(currEdge, iter, entry);
                if (addToQueue) {
                    prioQueue.remove(entry);
                    entry.updateWeights();
                    prioQueue.add(entry);
                    updateTarget(entry);
                }
            }
            if(hasTurnWeighting)
                turnWeighting.setInORS(true);
        }
        //TODO check if necessary
        if(!targetGraph.containsNode(currEdge.getAdjNode())) currEdge.resetUpdate(false);

    }

    private boolean iterateMultiTree(AveragedMultiTreeSPEntry currEdge, EdgeIterator iter, AveragedMultiTreeSPEntry adjEntry) {
        boolean addToQueue = false;
        for (int i = 0; i < treeEntrySize; ++i) {
            MultiTreeSPEntryItem currEdgeItem = currEdge.getItem(i);
            double entryWeight = currEdgeItem.getWeight();

            if (entryWeight == Double.POSITIVE_INFINITY)
                continue;
            double edgeWeight;

            if (!additionalCoreEdgeFilter.accept(iter)) {
                continue;
            }
            if(hasTurnWeighting && !isInORS(iter, currEdgeItem))
                turnWeighting.setInORS(false);
            edgeWeight = weighting.calcWeight(iter, swap, currEdgeItem.getOriginalEdge());
            if(Double.isInfinite(edgeWeight))
                continue;
            double tmpWeight = edgeWeight + entryWeight;

            MultiTreeSPEntryItem eeItem = adjEntry.getItem(i);
            if (eeItem.getWeight() > tmpWeight) {
                eeItem.setWeight(tmpWeight);
                eeItem.setEdge(iter.getEdge());
                eeItem.setOriginalEdge(EdgeIteratorStateHelper.getOriginalEdge(iter));
                eeItem.setParent(currEdge);
                eeItem.setUpdate(true);
                addToQueue = true;
            }
            if(hasTurnWeighting)
                turnWeighting.setInORS(true);
        }

        return addToQueue;
    }

    /**
     * /
     * /
     * __________IN-CORE
     * /
     * /
     **/
    private void runPhaseInsideCore() {
        // Calculate all paths only inside core
        DijkstraManyToMany algorithm = new DijkstraManyToMany(graph, chGraph, bestWeightMap, bestWeightMapCore, weighting, TraversalMode.NODE_BASED);
        // This is for testing only. Get filter from request instead
        EdgeFilterSequence edgeFilterSequence = new EdgeFilterSequence();
        edgeFilterSequence.add(this.additionalCoreEdgeFilter);
        //TODO
//        try {
//            edgeFilterSequence.add(new AvoidFeaturesEdgeFilter(AvoidFeatureFlags.HIGHWAYS, graphHopper.getGraphHopperStorage()));
//        }
//        catch (Exception e){
//        }

        algorithm.setEdgeFilter(edgeFilterSequence);
        algorithm.setTreeEntrySize(this.treeEntrySize);
        algorithm.setHasTurnWeighting(this.hasTurnWeighting);
        algorithm.setMaxVisitedNodes(this.maxVisitedNodes);
        algorithm.setVisitedNodes(this.visitedNodes);
        algorithm.setTurnWeighting(this.turnWeighting);
        algorithm.setTargetGraphExplorer(this.targetGraphExplorer);
        algorithm.setTargetMap(this.targetMap);
        algorithm.setTargetSet(this.targetSet);
        algorithm.setSwap(this.swap);

        int[] entryPoints = coreEntryPoints.toArray();
        int[] exitPoints = coreExitPoints.toArray();
        algorithm.calcPaths(entryPoints, exitPoints);
    }

    public void setEdgeFilter(CoreDijkstraFilter additionalEdgeFilter) {
        this.additionalCoreEdgeFilter = additionalEdgeFilter;
    }

    boolean isCoreNode(int node) {
        return chGraph.getLevel(node) >= coreNodeLevel;
    }

    boolean considerTurnRestrictions(int node) {
        if (!hasTurnWeighting)
            return false;
        if (approximate)
            return isTurnRestrictedNode(node);
        return true;
    }

    boolean isTurnRestrictedNode(int node) {
        return chGraph.getLevel(node) == turnRestrictedNodeLevel;
    }

    /**
     * Check whether the turnWeighting should be in the inORS mode. If one of the edges is a virtual one, we need the original edge to get the turn restriction.
     * If the two edges are actually virtual edges on the same original edge, we want to disable inORS mode so that they are not regarded as u turn,
     * because the same edge id left and right of a virtual node results in a u turn
     * @param iter
     * @param currEdge
     * @return
     */
    private boolean isInORS(EdgeIteratorState iter, AveragedMultiTreeSPEntry currEdge) {
        return currEdge.getEdge() == iter.getEdge() || currEdge.getOriginalEdge() != EdgeIteratorStateHelper.getOriginalEdge(iter);
    }

    private boolean isInORS(EdgeIteratorState iter, MultiTreeSPEntryItem currEdgeItem) {
        return currEdgeItem.getEdge() == iter.getEdge() || currEdgeItem.getOriginalEdge() != EdgeIteratorStateHelper.getOriginalEdge(iter);
    }

    //TODO integrate into algorithm creation
    public void setMaxVisitedNodes(int numberOfNodes) {
        this.maxVisitedNodes = numberOfNodes;
    }

    protected boolean isMaxVisitedNodesExceeded() {
        return this.maxVisitedNodes < this.visitedNodes;
    }
}
