package org.aksw.limes.core.ml.algorithm;

import org.aksw.limes.core.datastrutures.Tree;
import org.aksw.limes.core.evaluation.qualititativeMeasures.PseudoFMeasure;
import org.aksw.limes.core.exceptions.UnsupportedMLImplementationException;
import org.aksw.limes.core.io.cache.Cache;
import org.aksw.limes.core.io.ls.LinkSpecification;
import org.aksw.limes.core.io.mapping.AMapping;
import org.aksw.limes.core.io.mapping.MappingFactory;
import org.aksw.limes.core.io.parser.Parser;
import org.aksw.limes.core.measures.mapper.MappingOperations;
import org.aksw.limes.core.ml.algorithm.wombat.AWombat;
import org.aksw.limes.core.ml.algorithm.wombat.ExtendedClassifier;
import org.aksw.limes.core.ml.algorithm.wombat.RefinementNode;
import org.aksw.limes.core.ml.oldalgorithm.MLModel;
import org.aksw.limes.core.ml.setting.LearningParameters;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete Wombat algorithm (slow implementation)
 * 
 * @author Mohamed Sherif <sherif@informatik.uni-leipzig.de>
 * @version Jun 7, 2016
 */
public class WombatComplete extends AWombat {
    protected static final String ALGORITHM_NAME = "Wombat Complete";

    protected static Logger logger = Logger.getLogger(WombatComplete.class.getName());
    
    protected static boolean usePruning = false;

    protected RefinementNode bestSolutionNode = null;
    protected List<ExtendedClassifier> classifiers = null;
    protected int iterationNr = 0;
    protected Map<String, AMapping> diffs;
    
    // for evaluation
    protected int pruneNodeCount = 0;
    protected long pruningTime = 0;


    protected WombatComplete() {
        super();
    }

    @Override
    protected String getName() {
        return ALGORITHM_NAME;
    }

    @Override
    protected void init(LearningParameters lp, Cache sourceCache, Cache targetCache) {
        super.init(lp, sourceCache, targetCache);
        sourceUris = sourceCache.getAllUris();
        targetUris = targetCache.getAllUris();
    }

    @Override
    protected MLModel learn(AMapping trainingData) {
        this.trainingData = trainingData;
        return learn();
    }

    private MLModel learn() {
        if (bestSolutionNode == null) { // not to do learning twice
            bestSolutionNode = findBestSolution();
        }
        String bestMetricExpr = bestSolutionNode.getMetricExpression();
        double threshold = Double.parseDouble(bestMetricExpr.substring(bestMetricExpr.lastIndexOf("|") + 1, bestMetricExpr.length()));
        AMapping bestMapping = bestSolutionNode.getMapping();
        LinkSpecification bestLS = new LinkSpecification(bestMetricExpr, threshold);
        double bestFMeasure = bestSolutionNode.getFMeasure();
        MLModel result = new MLModel(bestLS, bestMapping, bestFMeasure, null);
        return result;
    }

    @Override
    protected MLModel learn(PseudoFMeasure pfm) {
        if(pfm != null){
            this.pseudoFMeasure = pfm;
        }else{ // use default PFM
            this.pseudoFMeasure = new PseudoFMeasure();
        }
        this.isUnsupervised = true;
        return learn();
    }

    @Override
    protected AMapping predict(Cache source, Cache target, MLModel mlModel) {
        LinkSpecification ls = mlModel.getLinkSpecification();
        return getPredictions(ls, source, target);
    }

    @Override
    protected boolean supports(MLImplementationType mlType) {
        return mlType == MLImplementationType.SUPERVISED_BATCH || mlType == MLImplementationType.UNSUPERVISED;
    }

    @Override
    protected AMapping getNextExamples(int size) throws UnsupportedMLImplementationException {
        throw new UnsupportedMLImplementationException(this.getName());
    }

    @Override
    protected MLModel activeLearn(AMapping oracleMapping) throws UnsupportedMLImplementationException {
        throw new UnsupportedMLImplementationException(this.getName());
    }


    
    /**
     * @return RefinementNode containing the best over all solution
     * @author sherif
     */
    public RefinementNode findBestSolution() {
        List<ExtendedClassifier> classifiers = findInitialClassifiers();
        diffs = computeClassifiersDiffPermutations(classifiers);
        createRefinementTreeRoot();
        RefinementNode.setrMax(computeMaxRecall(classifiers));
        Tree<RefinementNode> mostPromisingNode = findMostPromisingNode(refinementTreeRoot, false);
        long time = System.currentTimeMillis();
        pruneTree(refinementTreeRoot, mostPromisingNode.getValue().getFMeasure());
        pruningTime += System.currentTimeMillis() - time;
        logger.info("Most promising node: " + mostPromisingNode.getValue());
        iterationNr++;
        while ((mostPromisingNode.getValue().getFMeasure()) < maxFitnessThreshold
                && (refinementTreeRoot.size() - pruneNodeCount) <= maxRefineTreeSize
                && iterationNr <= maxIterationNumber) {
            System.out.println("Running iteration number " + iterationNr);
            iterationNr++;
            mostPromisingNode = expandNode(mostPromisingNode);
            mostPromisingNode = findMostPromisingNode(refinementTreeRoot, false);
            time = System.currentTimeMillis();
            pruneTree(refinementTreeRoot, mostPromisingNode.getValue().getFMeasure());
            pruningTime += System.currentTimeMillis() - time;
            if (mostPromisingNode.getValue().getFMeasure() == -Double.MAX_VALUE) {
                break; // no better solution can be found
            }
            logger.info("Most promising node: " + mostPromisingNode.getValue());
        }
        RefinementNode bestSolution = findMostPromisingNode(refinementTreeRoot, true).getValue();
        logger.info("Overall Best Solution: " + bestSolution);
        if (!RefinementNode.isSaveMapping()) {
            bestSolution.setMap(getMapingOfMetricExpression(bestSolution.getMetricExpression()));
        }
        return bestSolution;
    }
    
    /**
     * @param r
     * @param fMeasure
     * @author sherif
     */
    private void pruneTree(Tree<RefinementNode> r, double f) {
        if (!usePruning)
            return;
        if (r.getchildren() != null && r.getchildren().size() > 0) {
            for (Tree<RefinementNode> child : r.getchildren()) {
                if (child.getValue().getMaxFMeasure() < f) {
                    prune(child);
                } else {
                    pruneTree(child, f);
                }
            }
        }
    }
    
    
    /**
     * @param c
     *         initial classifiers
     * @return all permutations of x\y for each x,y in classifiers and x!=y
     * @author sherif
     */
    private Map<String, AMapping> computeClassifiersDiffPermutations(List<ExtendedClassifier> c) {
        Map<String, AMapping> diffs = new HashMap<>();
        for (int i = 0; i < c.size(); i++) {
            for (int j = 0; j < c.size(); j++) {
                if (i != j) {
                    AMapping m = MappingOperations.difference(c.get(i).getMapping(), c.get(j).getMapping());
                    String e = "MINUS(" + c.get(i).getMetricExpression() + "," + c.get(j).getMetricExpression() + ")|0.0";
                    diffs.put(e, m);
                }
            }
        }
        return diffs;
    }
    
    /**
     * Get the most promising node as the node with the best F-score
     *
     * @param r
     *         the refinement search tree
     * @param overall
     *         set true to get the best over all node (normally at the end of the algorithm)
     *         if set to false you got only the best leaf
     * @return most promising node from the input tree r
     * @author sherif
     */
    private Tree<RefinementNode> findMostPromisingNode(Tree<RefinementNode> r, boolean overall) {
        // trivial case
        if (r.getchildren() == null || r.getchildren().size() == 0) {
            return r;
        }
        // get the most promising child
        Tree<RefinementNode> mostPromisingChild = new Tree<RefinementNode>(new RefinementNode());
        for (Tree<RefinementNode> child : r.getchildren()) {
            if (usePruning && child.getValue().getMaxFMeasure() < mostPromisingChild.getValue().getFMeasure()) {
                long time = System.currentTimeMillis();
                prune(child);
                pruningTime += System.currentTimeMillis() - time;
            }
            if (child.getValue().getFMeasure() >= 0) {
                Tree<RefinementNode> promisingChild = findMostPromisingNode(child, overall);
                if (promisingChild.getValue().getFMeasure() > mostPromisingChild.getValue().getFMeasure()) {
                    mostPromisingChild = promisingChild;
                } else if ((promisingChild.getValue().getFMeasure() == mostPromisingChild.getValue().getFMeasure())
                        && (computeExpressionComplexity(promisingChild) < computeExpressionComplexity(mostPromisingChild))) {
                    mostPromisingChild = promisingChild;
                }
            }
        }
        if (overall) { // return the best leaf
            return mostPromisingChild;
        } else // return the best over all node
            if ((r.getValue().getFMeasure() > mostPromisingChild.getValue().getFMeasure())
                    || (r.getValue().getFMeasure() == mostPromisingChild.getValue().getFMeasure()
                    && computeExpressionComplexity(r) < computeExpressionComplexity(mostPromisingChild))) {
                return r;
            } else {
                return mostPromisingChild;
            }
    }
    
    
    /**
     * @param node
     * @return Complexity of the input node as the number of operators included in its metric expression
     * @author sherif
     */
    private int computeExpressionComplexity(Tree<RefinementNode> node) {
        String e = node.getValue().getMetricExpression();
        return StringUtils.countMatches(e, "OR(") + StringUtils.countMatches(e, "AND(") + StringUtils.countMatches(e, "MINUS(");
    }


    /**
     * @param child
     * @author sherif
     */
    private void prune(Tree<RefinementNode> t) {
        pruneNodeCount++;
        //      t.remove();
        t.getValue().setMetricExpression("Pruned");
        t.getValue().setPrecision(-Double.MAX_VALUE);
        t.getValue().setRecall(-Double.MAX_VALUE);
        t.getValue().setfMeasure(-Double.MAX_VALUE);
        t.getValue().setMaxFMeasure(-Double.MAX_VALUE);
        t.getValue().setMap(null);
        if (t.getchildren() != null && t.getchildren().size() > 0) {
            for (Tree<RefinementNode> child : t.getchildren()) {
                t.removeChild(child);
            }
        }
    }


    /**
     * @return initial classifiers
     */
    public List<ExtendedClassifier> findInitialClassifiers() {
        logger.info("Geting all initial classifiers ...");
        List<ExtendedClassifier> initialClassifiers = new ArrayList<>();
        for (String p : sourcePropertiesCoverageMap.keySet()) {
            for (String q : targetPropertiesCoverageMap.keySet()) {
                for (String m : measures) {
                    ExtendedClassifier cp = findInitialClassifier(p, q, m);
                    //only add if classifier covers all entries
                    initialClassifiers.add(cp);
                }
            }
        }
        logger.info("Done computing all initial classifiers.");
        return initialClassifiers;
    }


    /**
     * Computes the atomic classifiers by finding the highest possible F-measure
     * achievable on a given property pair
     *
     * @param sourceCache
     *         Source cache
     * @param targetCache
     *         Target cache
     * @param sourceProperty
     *         Property of source to use
     * @param targetProperty
     *         Property of target to use
     * @param measure
     *         Measure to be used
     * @param trainingMap
     * @param reference
     *         Reference mapping
     * @return Best simple classifier
     */
    private ExtendedClassifier findInitialClassifier(String sourceProperty, String targetProperty, String measure) {
        double maxOverlap = 0;
        double theta = 1.0;
        AMapping bestMapping = MappingFactory.createDefaultMapping();
        for (double threshold = 1d; threshold > minPropertyCoverage; threshold = threshold * propertyLearningRate) {
            AMapping mapping = executeAtomicMeasure(sourceProperty, targetProperty, measure, threshold);
            double overlap = recall(mapping);
            if (maxOverlap < overlap) { //only interested in largest threshold with recall 1
                bestMapping = mapping;
                theta = threshold;
                maxOverlap = overlap;
                bestMapping = mapping;
            }
        }
        ExtendedClassifier cp = new ExtendedClassifier(measure, theta);
        cp.setfMeasure(maxOverlap);
        cp.sourceProperty = sourceProperty;
        cp.targetProperty = targetProperty;
        cp.setMapping(bestMapping);
        return cp;
    }


    /**
     * Get the most promising node as the node with the best F-score
     *
     * @param r  The whole refinement tree
     * @param penaltyWeight
     * @return most promising node from the input tree r
     * @author sherif
     */
    protected Tree<RefinementNode> getMostPromisingNode(Tree<RefinementNode> r, double penaltyWeight) {
        // trivial case
        if (r.getchildren() == null || r.getchildren().size() == 0) {
            return r;
        }
        // get mostPromesyChild of children
        Tree<RefinementNode> mostPromesyChild = new Tree<RefinementNode>(new RefinementNode());
        for (Tree<RefinementNode> child : r.getchildren()) {
            if (child.getValue().getFMeasure() >= 0) {
                Tree<RefinementNode> promesyChild = getMostPromisingNode(child, penaltyWeight);
                double newFitness;
                newFitness = promesyChild.getValue().getFMeasure() - penaltyWeight * computePenalty(promesyChild);
                if (newFitness > mostPromesyChild.getValue().getFMeasure()) {
                    mostPromesyChild = promesyChild;
                }
            }
        }
        // return the argmax{root, mostPromesyChild}
        if (penaltyWeight > 0) {
            return mostPromesyChild;
        } else if (r.getValue().getFMeasure() >= mostPromesyChild.getValue().getFMeasure()) {
            return r;
        } else {
            return mostPromesyChild;
        }
    }

    /**
     * @return children penalty + complexity penalty
     * @author sherif
     */
    private double computePenalty(Tree<RefinementNode> promesyChild) {
        long childrenCount = promesyChild.size() - 1;
        double childrenPenalty = (childrenPenaltyWeit * childrenCount) / refinementTreeRoot.size();
        long level = promesyChild.level();
        double complexityPenalty = (complexityPenaltyWeit * level) / refinementTreeRoot.depth();
        return childrenPenalty + complexityPenalty;
    }

    /**
     * @param node
     *         Refinement node to be expanded
     * @return The input tree node after expansion
     * @author sherif
     */
    private Tree<RefinementNode> expandNode(Tree<RefinementNode> node) {
        // Add children
        List<RefinementNode> childrenNodes = refine(node);
        for (RefinementNode n : childrenNodes) {
            if (!inRefinementTree(n.getMetricExpression())) {
                node.addChild(new Tree<RefinementNode>(n));
            }
        }
        // Add sibling (if any)
        if (node.level() == 1) {
            List<RefinementNode> siblingNodes = createConjunctionsWithDiffNodes(node);
            for (RefinementNode n : siblingNodes) {
                if (!inRefinementTree(n.getMetricExpression())) {
                    node.getParent().addChild(new Tree<RefinementNode>(n));
                }
            }
        }
        if (verbose) {
            System.out.println("Tree size:" + refinementTreeRoot.size());
            refinementTreeRoot.print();
        }
        return node;
    }
    
    
    /**
     * Apply refinement operator
     *
     * @param node
     * @return list of all children
     * @author sherif
     */
    private List<RefinementNode> refine(final Tree<RefinementNode> node) {
        List<RefinementNode> result = new ArrayList<>();
        String childMetricExpr = new String();
        AMapping childMap = MappingFactory.createDefaultMapping();
        String nodeMetricExpr = node.getValue().getMetricExpression();

        if (isRoot(nodeMetricExpr)) {
            for (String diffExpr : diffs.keySet()) {
                AMapping diffMapping = diffs.get(diffExpr);
                result.add(createNode(diffMapping, diffExpr));
            }
            return result;
        } else if (isAtomic(nodeMetricExpr)) {
            return createDisjunctionsWithDiffNodes(node);
        } else if (isDifference(nodeMetricExpr)) {
            String firstMetricExpr = getSubMetricExpressions(nodeMetricExpr).get(0);
            AMapping firstMetricExprMapping = getMapingOfMetricExpression(firstMetricExpr);
            result.add(createNode(firstMetricExprMapping, firstMetricExpr));
            result.addAll(createDisjunctionsWithDiffNodes(node));
            return result;
        } else if (isConjunction(nodeMetricExpr)) {
            childMetricExpr = new String();
            List<String> subMetricExpr = getSubMetricExpressions(nodeMetricExpr);
            result.add(createNode(subMetricExpr.get(0)));
            List<String> childSubMetricExpr = new ArrayList<>();
            for (int i = 0; i < subMetricExpr.size(); i++) {
                for (int j = 0; j < subMetricExpr.size(); j++) {
                    if (i == j) {
                        for (RefinementNode n : refine(new Tree<RefinementNode>(createNode(subMetricExpr.get(i))))) {
                            childSubMetricExpr.add(n.getMetricExpression());
                        }
                    } else {
                        childSubMetricExpr.add(subMetricExpr.get(i));
                    }
                }
                childMetricExpr += "AND(" + childSubMetricExpr.get(0) + "," + childSubMetricExpr.get(1) + ")|0.0";
                childMap = MappingOperations.intersection(getMapingOfMetricExpression(childSubMetricExpr.get(0)), getMapingOfMetricExpression(childSubMetricExpr.get(1)));
                for (int k = 2; k < childSubMetricExpr.size(); k++) {
                    childMetricExpr = "AND(" + childMetricExpr + "," + childSubMetricExpr.get(k) + ")|0.0";
                    childMap = MappingOperations.intersection(childMap, getMapingOfMetricExpression(childSubMetricExpr.get(k)));
                }
                result.add(createNode(childMap, childMetricExpr));
                childMetricExpr = new String();
            }
            result.addAll(createDisjunctionsWithDiffNodes(node));
            return result;
        } else if (isDisjunction(nodeMetricExpr)) {
            childMetricExpr = new String();
            List<String> subMetricExpr = getSubMetricExpressions(nodeMetricExpr);
            //          System.out.println("-------------subMetricExpr: "+ subMetricExpr);
            result.add(createNode(subMetricExpr.get(0)));
            List<String> childSubMetricExpr = new ArrayList<>();
            for (int i = 0; i < subMetricExpr.size(); i++) {
                for (int j = 0; j < subMetricExpr.size(); j++) {
                    if (i == j) {
                        for (RefinementNode n : refine(new Tree<RefinementNode>(createNode(subMetricExpr.get(i))))) {
                            childSubMetricExpr.add(n.getMetricExpression());
                        }
                    } else {
                        childSubMetricExpr.add(subMetricExpr.get(i));
                    }
                }
                childMetricExpr += "OR(" + childSubMetricExpr.get(0) + "," + childSubMetricExpr.get(1) + ")|0.0";
                childMap = MappingOperations.union(getMapingOfMetricExpression(childSubMetricExpr.get(0)), getMapingOfMetricExpression(childSubMetricExpr.get(1)));
                for (int k = 2; k < childSubMetricExpr.size(); k++) {
                    childMetricExpr = "OR(" + childMetricExpr + "," + childSubMetricExpr.get(k) + ")|0.0";
                    childMap = MappingOperations.union(childMap, getMapingOfMetricExpression(childSubMetricExpr.get(k)));
                }
                result.add(createNode(childMap, childMetricExpr));
                childMetricExpr = new String();
            }
            result.addAll(createDisjunctionsWithDiffNodes(node));
            return result;
        } else {
            logger.error("Wrong metric expression: " + nodeMetricExpr);
            System.exit(1);
        }
        return result;
    }
    
    
    /**
     * @param nodeMetricExpr
     * @param nodeMapping
     * @return list of nodes L ∪ A_i \ A_j | A_i ∈ P, A_j ∈ P, where P is the set if initial classifiers
     * @author sherif
     */
    private List<RefinementNode> createDisjunctionsWithDiffNodes(Tree<RefinementNode> node) {
        List<RefinementNode> result = new ArrayList<>();
        for (String diffExpr : diffs.keySet()) {
            AMapping diffMapping = diffs.get(diffExpr);
            String childMetricExpr = "OR(" + node.getValue().getMetricExpression() + "," + diffExpr + ")|0.0";
            AMapping nodeMaping = MappingFactory.createDefaultMapping();
            if (RefinementNode.isSaveMapping()) {
                nodeMaping = node.getValue().getMapping();
            } else {
                nodeMaping = getMapingOfMetricExpression(node.getValue().getMetricExpression());
            }
            AMapping childMap = MappingOperations.union(nodeMaping, diffMapping);
            result.add(createNode(childMap, childMetricExpr));
        }
        return result;
    }

   
    
    /**
     * @param nodeMetricExpr
     * @return
     * @author sherif
     */
    private boolean isRoot(String nodeMetricExpr) {
        // TODO Auto-generated method stub
        return false;
    }
    
    /**
     * @param nodeMetricExpr
     * @return
     * @author sherif
     */
    private List<String> getSubMetricExpressions(String metricExpr) {
        List<String> result = new ArrayList<>();
        double threshold = Double.parseDouble(metricExpr.substring(metricExpr.lastIndexOf("|") + 1, metricExpr.length()));
        ;
        String metric = metricExpr.substring(0, metricExpr.lastIndexOf("|"));
        Parser p = new Parser(metric, threshold);
        result.add(p.getLeftTerm() + "|" + p.getLeftCoefficient());
        result.add(p.getRightTerm() + "|" + p.getRightCoefficient());
        return result;
    }
    
    
    /**
     * @param nodeMetricExpr
     * @param nodeMapping
     * @return list of nodes L ∪ A_i \ A_j | A_i ∈ P, A_j ∈ P, where P is the set if initial classifiers
     * @author sherif
     */
    private List<RefinementNode> createConjunctionsWithDiffNodes(Tree<RefinementNode> node) {
        List<RefinementNode> result = new ArrayList<>();
        for (String diffExpr : diffs.keySet()) {
            AMapping diffMapping = diffs.get(diffExpr);
            AMapping nodeMaping = MappingFactory.createDefaultMapping();
            if (RefinementNode.isSaveMapping()) {
                nodeMaping = node.getValue().getMapping();
            } else {
                nodeMaping = getMapingOfMetricExpression(node.getValue().getMetricExpression());
            }
            String childMetricExpr = "AND(" + node.getValue().getMetricExpression() + "," + diffExpr + ")|0.0";
            AMapping childMap = MappingOperations.intersection(nodeMaping, diffMapping);
            result.add(createNode(childMap, childMetricExpr));
        }
        return result;
    }
    
    /**
     * @param metricExpression
     * @return true if the input metricExpression already contained
     * in one of the search tree nodes, false otherwise
     * @author sherif
     */
    private boolean inRefinementTree(String metricExpression) {
        return inRefinementTree(metricExpression, refinementTreeRoot);
    }

    /**
     * @param metricExpression
     * @param treeRoot
     * @return true if the input metricExpression already contained
     * in one of the search tree nodes, false otherwise
     * @author sherif
     */
    private boolean inRefinementTree(String metricExpression, Tree<RefinementNode> treeRoot) {
        if (treeRoot == null) {
            return false;
        }
        if (treeRoot.getValue().getMetricExpression().equals(metricExpression)) {
            return true;
        }
        if (treeRoot.getchildren() != null) {
            for (Tree<RefinementNode> n : treeRoot.getchildren()) {
                if (inRefinementTree(metricExpression, n)) {
                    return true;
                }
            }
        }
        return false;
    }




    
    /**
     * initiate the refinement tree as a root node  with set of
     * children nodes containing all permutations of x\y
     * for each x,y in classifiers and x!=y
     *
     * @return
     * @author sherif
     */
    private void createRefinementTreeRoot() {
        RefinementNode initialNode = new RefinementNode(-Double.MAX_VALUE, MappingFactory.createDefaultMapping(), "");
        refinementTreeRoot = new Tree<RefinementNode>(null, initialNode, null);
        for (String diffExpr : diffs.keySet()) {
            AMapping diffMapping = diffs.get(diffExpr);
            RefinementNode n = createNode(diffMapping, diffExpr);
            refinementTreeRoot.addChild(new Tree<RefinementNode>(refinementTreeRoot, n, null));
        }
        if (verbose) {
            System.out.println("Tree size:" + refinementTreeRoot.size());
            refinementTreeRoot.print();
        }
    }
    
    /**
     * @param classifiers
     * @return maximum achievable recall as the recall of the mapping generated
     * from disjunctions of all initial mappings
     * @author sherif
     */
    public double computeMaxRecall(List<ExtendedClassifier> classifiers) {
        AMapping unionMaping;
        unionMaping = classifiers.get(0).getMapping();
        for (int i = 1; i < classifiers.size(); i++) {
            unionMaping = MappingOperations.union(unionMaping, classifiers.get(i).getMapping());
        }
        return recall(unionMaping);
    }
    
    /**
     * @param l
     * @return
     * @author sherif
     */
    private boolean isDisjunction(String l) {
        return l.startsWith("OR");
    }

    /**
     * @param l
     * @return
     * @author sherif
     */
    private boolean isConjunction(String l) {
        return l.startsWith("AND");
    }

    /**
     * @param l
     * @return
     * @author sherif
     */
    private boolean isDifference(String l) {
        return l.startsWith("MINUS");
    }

    /**
     * @param l
     * @return
     * @author sherif
     */
    private boolean isAtomic(String l) {
        if (!isDifference(l) && !isConjunction(l) && !isDisjunction(l))
            return true;
        return false;
    }

}
