package org.apache.sysds.hops.rewrite;

import org.jetbrains.annotations.Contract;

import java.util.Arrays;
import java.util.List;

public class Foo {

	public static void main(String[] args) {
		System.out.println("=== STARTING DUAL-STATE DP SANDBOX ===\n");

		// TEST CASE: test1.dml
		// Array length is size + 1
		String[] matrixNames = {"A", "B", "C"};
		double[] dimsArray = {4.0, 2.0, 1.0, 4.0};
		int size = 3;

		List<Boolean> isTransposeChain = Arrays.asList(false, false, false);

		System.out.println("Input Chain: A(10x20) %*% B(20x30) %*% C(30x40)");
		System.out.println("Dims Array: " + Arrays.toString(dimsArray));
		System.out.println("Transpose Flags: " + isTransposeChain + "\n");

		MemoTable memo;
		// Run the algorithm!
		memo = mmChainDP(dimsArray, size, isTransposeChain);

		System.out.println(memo.normalPlans[0][0]);

		// We want the final Normal answer for the whole chain (0 to size-1)
		String optimalPlan = getPlanAsString(0, size - 1, memo, false, matrixNames);

		System.out.println("\n=== FINAL OPTIMIZED PLAN ===");
		System.out.println(optimalPlan);
		System.out.println("\n=== EXECUTION FINISHED ===");
	}

	private static MemoTable mmChainDP(double[] dimArray, int size, List<Boolean> isTransposeChain) {
		MemoTable memo = new MemoTable(size);

		System.out.println("--- PHASE 1: BASE CASES (Length = 1) ---");
		for (int i = 0; i < size; i++) {
			double rows = dimArray[i];
			double cols = dimArray[i + 1];
			boolean isTransposed = isTransposeChain.get(i);

			Plan normalPlan = new Plan();
			Plan transPlan = new Plan();

			if (!isTransposed) {
				normalPlan.cost = 0;
				normalPlan.withTranspose = false;

				transPlan.cost = rows * cols;
				transPlan.withTranspose = true;
			} else {
				normalPlan.cost = rows * cols;
				normalPlan.withTranspose = true;

				transPlan.cost = 0;
				transPlan.withTranspose = false;
			}

			memo.setNormal(i, i, normalPlan);
			memo.setTransposed(i, i, transPlan);

			System.out.printf("Matrix %d [%.0fx%.0f] -> Normal Cost: %.0f | Transposed Cost: %.0f\n",
				i, rows, cols, normalPlan.cost, transPlan.cost);
		}

		System.out.println("\n--- PHASE 2: EVALUATING SUB-CHAINS ---");
		for (int l = 2; l <= size; l++) {
			System.out.println("\n>> Evaluating Sub-chains of Length: " + l);

			for (int i = 0; i < size - l + 1; i++) {
				int j = i + l - 1;
				double normalOutRows = dimArray[i];
				double normalOutCols = dimArray[j + 1];

				System.out.printf("   Sub-chain from index %d to %d (Result: [%.0fx%.0f])\n",
					i, j, normalOutRows, normalOutCols);

				Plan bestNormal = new Plan();
				Plan bestTrans = new Plan();

				for (int k = i; k < j; k++) {
					System.out.printf("      Checking split at k=%d:\n", k);

					// Normal Algebraic Path
					Plan normalLeft = memo.getNormal(i, k);
					Plan normalRight = memo.getNormal(k + 1, j);
					double scalarCost = normalOutRows * dimArray[k + 1] * normalOutCols;
					double costN = normalLeft.cost + normalRight.cost + scalarCost;

					System.out.printf("         [Normal Algebra] Left(%.0f) + Right(%.0f) + Math(%.0f) = %.0f\n",
						normalLeft.cost, normalRight.cost, scalarCost, costN);

					if (costN < bestNormal.cost) {
						bestNormal.cost = costN;
						bestNormal.splitIndex = k;
						bestNormal.withTranspose = false;
					}

					// Transposed Algebraic Path
					Plan transLeft = memo.getTransposed(i, k);
					Plan transRight = memo.getTransposed(k + 1, j);
					double costT = transLeft.cost + transRight.cost + scalarCost;

					System.out.printf("         [Transposed Algebra] Left(%.0f) + Right(%.0f) + Math(%.0f) = %.0f\n",
						transLeft.cost, transRight.cost, scalarCost, costT);

					if (costT < bestTrans.cost) {
						bestTrans.cost = costT;
						bestTrans.splitIndex = k;
						bestTrans.withTranspose = false;
					}
				}

				// Cross-State Pruning
				double physicalWrapCost = normalOutRows * normalOutCols;
				System.out.printf("      -- Pruning Phase: Cost to physically wrap is %.0f --\n", physicalWrapCost);

				if (bestNormal.cost + physicalWrapCost < bestTrans.cost) {
					System.out.printf("      *** PRUNED! Normal + Wrap (%.0f) is cheaper than pure Transposed (%.0f) ***\n",
						bestNormal.cost + physicalWrapCost, bestTrans.cost);
					bestTrans.cost = bestNormal.cost + physicalWrapCost;
					bestTrans.splitIndex = bestNormal.splitIndex;
					bestTrans.withTranspose = true;
				}

				if (bestTrans.cost + physicalWrapCost < bestNormal.cost) {
					System.out.printf("      *** PRUNED! Transposed + Wrap (%.0f) is cheaper than pure Normal (%.0f) ***\n",
						bestTrans.cost + physicalWrapCost, bestNormal.cost);
					bestNormal.cost = bestTrans.cost + physicalWrapCost;
					bestNormal.splitIndex = bestTrans.splitIndex;
					bestNormal.withTranspose = true;
				}

				memo.setNormal(i, j, bestNormal);
				memo.setTransposed(i, j, bestTrans);

				System.out.printf("   => WINNER for %d to %d: Normal=%.0f, Transposed=%.0f\n",
					i, j, bestNormal.cost, bestTrans.cost);
			}
		}
		return memo;
	}

	public static String getPlanAsString(int i, int j, MemoTable memo, boolean isTransposed, String[] names){
		// 1. Read the "signpost" / splitIndex for the current span
		Plan plan = isTransposed ? memo.getTransposed(i, j) : memo.getNormal(i, j);

		// 2. Base Case (A single matrix)
		if (i == j) {
			String leaf = names[i];
			if (plan.withTranspose) {
				return "t(" + leaf + ")";
			}
			return leaf;
		}

		if (plan.withTranspose) {
			// Build the math in the OPPOSITE state, then physically wrap it in text
			String child = getPlanAsString(i, j, memo, !isTransposed, names);
			return "t(" + child + ")";
		}

		// 4. The Algebraic Split
		String leftChild, rightChild;
		if (isTransposed) {
			// (A * B)^T = B^T * A^T  => Right side goes left, Left side goes right!
			leftChild = getPlanAsString(plan.splitIndex + 1, j, memo, true, names);
			rightChild = getPlanAsString(i, plan.splitIndex, memo, true, names);
		} else {
			// Normal order
			leftChild = getPlanAsString(i, plan.splitIndex, memo, false, names);
			rightChild = getPlanAsString(plan.splitIndex + 1, j, memo, false, names);
		}

		// 5. Glue the strings together with parenthesis to show the tree grouping
		return "(" + leftChild + " %*% " + rightChild + ")";
	}

	private static class Plan {
		double cost = Double.MAX_VALUE;
		int splitIndex = -1;
		boolean withTranspose;
	}

	private static class MemoTable {
		private final Plan[][] normalPlans;
		private final Plan[][] transposedPlans;

		@Contract(pure = true)
		public MemoTable(int size) {
			normalPlans = new Plan[size][size];
			transposedPlans = new Plan[size][size];
		}

		@Contract(pure = true)
		public Plan getNormal(int i, int j) { return normalPlans[i][j]; }
		@Contract(pure = true)
		public Plan getTransposed(int i, int j) { return transposedPlans[i][j]; }
		public void setNormal(int i, int j, Plan plan) { normalPlans[i][j] = plan; }
		public void setTransposed(int i, int j, Plan plan) { transposedPlans[i][j] = plan; }
	}
}
