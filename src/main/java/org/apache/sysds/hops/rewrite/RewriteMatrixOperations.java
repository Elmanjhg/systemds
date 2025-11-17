/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.hops.rewrite;

import java.util.ArrayList;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.Hop;


public class RewriteMatrixOperations extends HopRewriteRule
{
    @Override
    public ArrayList<Hop> rewriteHopDAGs(ArrayList<Hop> roots, ProgramRewriteStatus state)
    {
        if( roots == null )
            return null;

        if( LOG.isDebugEnabled() )
            LOG.debug("=== RewriteMatrixOperations: Starting optimization ===");

        // Process each root in the DAG
        for( Hop h : roots )
            rule_OptimizeMMChains(h, state);

        return roots;
    }

    @Override
    public Hop rewriteHopDAG(Hop root, ProgramRewriteStatus state)
    {
        if( root == null )
            return null;

        if( LOG.isDebugEnabled() )
            LOG.debug("=== RewriteMatrixOperations: Starting single root optimization ===");

        rule_OptimizeMMChains(root, state);
        return root;
    }

    /**
     * Traverses the HOP DAG to find matrix multiplication chains and transpose operations
     * that can be optimized.
     *
     * @param hop The current hop being visited
     * @param state Program rewrite status for tracking modifications
     */
    private void rule_OptimizeMMChains(Hop hop, ProgramRewriteStatus state)
    {
        if( !hop.isVisited() ) {
            // check if this hop is a matrix multiplication or transpose operation
            boolean isMatMul = HopRewriteUtils.isMatrixMultiply(hop)
                    && !((AggBinaryOp) hop).hasLeftPMInput();
            boolean isTranspose = HopRewriteUtils.isReorg(hop, Types.ReOrgOp.TRANS);

            // if found an optimizable operation, try to optimize it
            if ( isMatMul || isTranspose ) {
                prepAndOptimizeMyNewDP(hop, state);
            }

            // Recursively visit all children
            for (Hop hi : hop.getInput())
                rule_OptimizeMMChains(hi, state);

            hop.setVisited();
        }
    }

    /**
     * Main entry point for the joint DP optimizer. This method:
     * 1. Extracts the matrix chain from the HOP DAG
     * 2. Runs the joint DP algorithm to find the optimal plan
     * 3. Reconstructs the optimized HOP DAG
     * 4. Replaces the original DAG if the optimization is beneficial
     *
     * @param hop The root hop of the potential chain to optimize
     * @param state Program rewrite status
     */
    private void prepAndOptimizeMyNewDP(Hop hop, ProgramRewriteStatus state)
    {
        if( LOG.isTraceEnabled() ) {
            LOG.trace("MChain - Opt - Processing HOP ID=" + hop.getHopID());
        }

        ChainExtractionResult chainResult = extractChain(hop);

        if (!chainResult.isValid || chainResult.leafMatrices.size() < 2) {
            // Not a valid chain or too short to optimize
            return;
        }

        ArrayList<Hop> chain = chainResult.leafMatrices;

        if( LOG.isTraceEnabled() ) {
            for (int i = 0; i < chain.size(); i++) {
                Hop h = chain.get(i);
                String transposeFlag = chainResult.leafIsTransposed.get(i) ? " [TRANSPOSED]" : "";
                LOG.trace("  Chain[" + i + "]: " + h.getName() + " ("
                        + h.getDim1() + "x" + h.getDim2() + ")" + transposeFlag);
            }
        }

        for (Hop h : chain) {
            if (!h.dimsKnown()) {
                return;
            }
        }

        DPResult dpResult = newDPmmChain(hop, chainResult);

        if (dpResult == null || dpResult.optimalPlan == null) {
            return; // no optimization found
        }

        Hop optimizedHop = reconstructPlan(dpResult.memo, dpResult.chainResult, 0,
                chain.size() - 1, dpResult.useTransposedPlan);

        if (optimizedHop == null) {
            return;
        }

        ArrayList<Hop> parents = new ArrayList<>(hop.getParent());
        for (Hop parent : parents) {
            HopRewriteUtils.replaceChildReference(parent, hop, optimizedHop);
        }
    }

    /**
     * Core dynamic programming algorithm that computes optimal plans.
     *
     * This is the "brain" of the optimizer. It uses a 2D memoization table where each
     * cell memo[i][j] stores TWO plans:
     * - normalPlan: Optimal way to compute chain[i...j]
     * - transposedPlan: Optimal way to compute t(chain[i...j])
     *
     * By tracking both plans simultaneously, we can make globally optimal decisions
     * about when to apply transpose rewrites.
     *
     * Complexity: O(n³) time, O(n²) space where n is the chain length
     *
     * @param rootHop The root hop of the expression
     * @param chainResult The extracted chain with transpose metadata
     * @return DPResult containing the optimal plan and memo table
     */
    private DPResult newDPmmChain(Hop rootHop, ChainExtractionResult chainResult)
    {
        ArrayList<Hop> chain = chainResult.leafMatrices;
        int size = chain.size();

        // The memoization table: stores both normal and transposed plans for each subproblem
        PlanPair[][] memo = new PlanPair[size][size];

        // base cases
        // For each matrix, we compute the cost and dimensions of using it
        // both normally and transposed
        for (int i = 0; i < size; i++) {
            memo[i][i] = new PlanPair();
            Hop currentHop = chain.get(i);
            boolean isTransposed = chainResult.leafIsTransposed.get(i);

            long rows = currentHop.getDim1();
            long cols = currentHop.getDim2();

            if (isTransposed) {
                // this matrix already appears transposed in the original expression
                // e.g., it's part of t(A*B), so A appears transposed
                memo[i][i].normalPlan = new Plan(0, i, cols, rows, true);
                memo[i][i].transposedPlan = new Plan(0, i, rows, cols, false);
            } else {
                // Normal matrix
                memo[i][i].normalPlan = new Plan(0, i, rows, cols, false);
                memo[i][i].transposedPlan = new Plan(0, i, cols, rows, true);
            }
        }

        // build up solutions for increasing chain lengths
        // This is the core DP loop that tries all possible split points
        for (int len = 2; len <= size; len++) {
            for (int i = 0; i < size - len + 1; i++) {
                int j = i + len - 1;
                memo[i][j] = new PlanPair();

                // Try all possible split points k where i <= k < j
                for (int k = i; k < j; k++) {

                    // === Compute optimal NORMAL plan for (i...j) ===
                    // This computes: Left(i...k) %*% Right(k+1...j)
                    Plan leftNorm = memo[i][k].normalPlan;
                    Plan rightNorm = memo[k+1][j].normalPlan;

                    if (leftNorm.cost != Double.MAX_VALUE && rightNorm.cost != Double.MAX_VALUE) {
                        // Verify dimension compatibility: left.cols must equal right.rows
                        if (leftNorm.dim2 == rightNorm.dim1) {
                            // Cost = cost(left) + cost(right) + cost(multiply)
                            // where cost(multiply) = rows × inner × cols
                            double cost = leftNorm.cost + rightNorm.cost
                                    + (double) leftNorm.dim1 * leftNorm.dim2 * rightNorm.dim2;

                            if (cost < memo[i][j].normalPlan.cost) {
                                memo[i][j].normalPlan = new Plan(cost, k,
                                        leftNorm.dim1, rightNorm.dim2, false);
                            }
                        }
                    }

                    // compute optimal TRANSPOSED plan for t(i...j)
                    // we need the transposed plans for both children, in reversed order
                    Plan leftTrans = memo[i][k].transposedPlan;
                    Plan rightTrans = memo[k+1][j].transposedPlan;

                    if (leftTrans.cost != Double.MAX_VALUE && rightTrans.cost != Double.MAX_VALUE) {
                        if (rightTrans.dim2 == leftTrans.dim1) {
                            double cost = leftTrans.cost + rightTrans.cost
                                    + (double) rightTrans.dim1 * rightTrans.dim2 * leftTrans.dim2;

                            if (cost < memo[i][j].transposedPlan.cost) {
                                memo[i][j].transposedPlan = new Plan(cost, k,
                                        rightTrans.dim1, leftTrans.dim2, true);
                            }
                        }
                    }
                }
            }
        }

        // select the final plan
        DPResult result = new DPResult();
        result.memo = memo;
        result.chainResult = chainResult;

        Plan finalNormal = memo[0][size-1].normalPlan;
        Plan finalTransposed = memo[0][size-1].transposedPlan;

        // does the root expression want a transposed result
        boolean rootWantsTranspose = chainResult.rootIsTranspose;

        if (rootWantsTranspose) {
            // use the transposed plan
            result.optimalPlan = finalTransposed;
            result.useTransposedPlan = true;
            result.optimalCost = finalTransposed.cost;
            result.originalCost = finalNormal.cost;
        } else {
            // use the normal plan
            result.optimalPlan = finalNormal;
            result.useTransposedPlan = false;
            result.optimalCost = finalNormal.cost;
            result.originalCost = finalNormal.cost;
        }

        return result;
    }

    /**
     * Reconstructs the optimal HOP DAG from the DP memo table.
     *
     * This method recursively builds the new expression tree by following the
     * split points stored in the memo table.
     *
     * @param memo The DP memoization table
     * @param chainResult The extracted chain metadata
     * @param i Start index of the subproblem
     * @param j End index of the subproblem
     * @param useTransposed Whether to build the transposed plan
     * @return The reconstructed Hop representing the optimal plan
     */
    private Hop reconstructPlan(PlanPair[][] memo, ChainExtractionResult chainResult,
                                int i, int j, boolean useTransposed) {

        ArrayList<Hop> chain = chainResult.leafMatrices;

        // Base case: single matrix
        if (i == j) {
            Hop leaf = chain.get(i);
            boolean leafIsTransposed = chainResult.leafIsTransposed.get(i);
            Plan plan = useTransposed ? memo[i][j].transposedPlan : memo[i][j].normalPlan;

            // Determine what form of the leaf we need
            if (useTransposed) {
                if (plan.isTransposed) {
                    // We need the transposed version
                    return leafIsTransposed ? leaf : HopRewriteUtils.createTranspose(leaf);
                } else {
                    // need the normal version
                    return leaf;
                }
            } else {
                // need the normal version
                return leaf;
            }
        }

        // Recursive case: combine subproblems according to the optimal split
        Plan plan = useTransposed ? memo[i][j].transposedPlan : memo[i][j].normalPlan;
        int k = plan.splitPoint;

        if (useTransposed && plan.isTransposed) {
            // Building t(A*B) = t(B)*t(A)
            Hop rightTransposed = reconstructPlan(memo, chainResult, k+1, j, true);  // t(B)
            Hop leftTransposed = reconstructPlan(memo, chainResult, i, k, true);     // t(A)
            return HopRewriteUtils.createMatrixMultiply(rightTransposed, leftTransposed);
        } else {
            // normal case: A*B
            Hop left = reconstructPlan(memo, chainResult, i, k, false);
            Hop right = reconstructPlan(memo, chainResult, k+1, j, false);
            return HopRewriteUtils.createMatrixMultiply(left, right);
        }
    }

    /**
     * Extracts a chain of matrices from the HOP DAG.
     *
     * This method identifies sequences of matrix multiplications and transposes,
     * tracking which matrices appear transposed in the original expression.
     *
     * @param root The root hop of the potential chain
     * @return ChainExtractionResult containing the extracted matrices and metadata
     */
    private ChainExtractionResult extractChain(Hop root) {
        ChainExtractionResult result = new ChainExtractionResult();

        // Special handling if root is a transpose
        if (HopRewriteUtils.isReorg(root, Types.ReOrgOp.TRANS)) {
            result.rootIsTranspose = true;
            Hop child = root.getInput().get(0);

            if (HopRewriteUtils.isMatrixMultiply(child)) {
                // This is t(A*B*...), extract the chain
                extractChainRecursive(child, result, false);
            } else {
                // Just t(A), not a chain
                result.isValid = false;
            }
        } else if (HopRewriteUtils.isMatrixMultiply(root)) {
            // Root is a matmul, extract normally
            extractChainRecursive(root, result, false);
        } else {
            // Not an optimizable operation
            result.isValid = false;
        }

        return result;
    }

    /**
     * Recursive helper for chain extraction.
     *
     * @param hop Current hop being processed
     * @param result Accumulator for the extraction result
     * @param isTransposed Whether this hop appears in a transposed context
     */
    private void extractChainRecursive(Hop hop, ChainExtractionResult result, boolean isTransposed) {
        boolean isMatMul = HopRewriteUtils.isMatrixMultiply(hop);
        boolean isTranspose = HopRewriteUtils.isReorg(hop, Types.ReOrgOp.TRANS);

        // Any matrix that's not matmul or transpose is a leaf
        boolean isLeaf = !isMatMul && !isTranspose;

        // Exclude aggregation operations
        boolean isAggUnary = (hop instanceof org.apache.sysds.hops.AggUnaryOp);

        if (isAggUnary) {
            result.isValid = false;
            return;
        }

        if (isLeaf) {
            // found a base matrix
            result.leafMatrices.add(0, hop);
            result.leafIsTransposed.add(0, isTransposed);
            return;
        }

        if (isTranspose) {
            extractChainRecursive(hop.getInput().get(0), result, !isTransposed);
            return;
        }

        if (isMatMul) {
            if (hop.getParent().size() > 1) {
                result.isValid = false;
                return;
            }

            // recurse on both children
            Hop left = hop.getInput().get(0);
            Hop right = hop.getInput().get(1);

            extractChainRecursive(left, result, isTransposed);
            if (!result.isValid) return;

            extractChainRecursive(right, result, isTransposed);
            return;
        }

        result.isValid = false;
    }

    /**
     * Represents a single execution plan
     *
     * Stores:
     * - cost: Total FLOPs required
     * - splitPoint: Where to split the chain for this plan
     * - dim1, dim2: Output dimensions of this plan
     * - isTransposed: Whether this plan produces a transposed result
     */
    private class Plan {
        double cost = Double.MAX_VALUE;  // Total FLOPs
        int splitPoint = -1;              // Optimal split index
        long dim1 = -1;                   // Output rows
        long dim2 = -1;                   // Output columns
        boolean isTransposed = false;     // Does this plan produce t(result)?

        Plan() { }

        Plan(double cost, int split, long dim1, long dim2, boolean transposed) {
            this.cost = cost;
            this.splitPoint = split;
            this.dim1 = dim1;
            this.dim2 = dim2;
            this.isTransposed = transposed;
        }
    }

    /**
     * stores both normal and transposed plans for a subproblem.
     *
     * This is the core of the memoization table. Each cell memo[i][j]
     * contains a PlanPair that tracks the optimal way to compute
     * chain[i...j] both normally and transposed.
     */
    private class PlanPair {
        Plan normalPlan;      // Optimal plan for chain[i...j]
        Plan transposedPlan;  // Optimal plan for t(chain[i...j])

        PlanPair() {
            this.normalPlan = new Plan();
            this.transposedPlan = new Plan();
        }
    }

    /**
     * Result of chain extraction from the HOP DAG.
     */
    private class ChainExtractionResult {
        ArrayList<Hop> leafMatrices = new ArrayList<>();          // The base matrices
        ArrayList<Boolean> leafIsTransposed = new ArrayList<>();  // Transpose flags
        boolean rootIsTranspose = false;                          // Is root a transpose?
        boolean isValid = true;                                   // Is extraction valid?
    }

    /**
     * Final result of the DP algorithm.
     */
    private class DPResult {
        PlanPair[][] memo;                    // DP memoization table
        ChainExtractionResult chainResult;    // original chain metadata
        Plan optimalPlan;                     // The chosen optimal plan
        boolean useTransposedPlan;            // Whether to use transposed plan
        double optimalCost;                   // Cost of optimal plan
        double originalCost;                  // Cost of original plan
    }
}