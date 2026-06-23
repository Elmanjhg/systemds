package org.apache.sysds.test.functions.rewrite;

import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.recompile.Recompiler;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RewriteMatrixChainPerformanceTest extends AutomatedTestBase {

	private static final String TEST_DIR = "MatrixChainOperations/";
	private static final String TEST_CLASS_DIR = TEST_DIR + RewriteMatrixChainPerformanceTest.class.getSimpleName() + "/";

	@Override
	public void setUp() {
		addTestConfiguration("performanceMmChain1", new TestConfiguration(TEST_CLASS_DIR, "performanceMmChain1", new String[]{"R"}));
		addTestConfiguration("performanceMmChain2", new TestConfiguration(TEST_CLASS_DIR, "performanceMmChain2", new String[]{"R"}));
		addTestConfiguration("performanceMmChain3", new TestConfiguration(TEST_CLASS_DIR, "performanceMmChain3", new String[]{"R"}));
	}

	@Test
	public void runComparison1() {
		runPerfTest("performanceMmChain1");
	}

	@Test
	public void runComparison2() {
		runPerfTest("performanceMmChain2");
	}

	@Test
	public void runComparison3() {
		runPerfTest("performanceMmChain3");
	}

	private void runPerfTest(String testName) {
		System.out.println("==================================================");
		System.out.println(" RUNNING BENCHMARK: " + testName);
		System.out.println("==================================================");

		double timeBaseline = executeAndExtractTime(testName, false, "NONE");

		double timeOldHeuristic = executeAndExtractTime(testName, true, "OLD");

		double timeNewDP = executeAndExtractTime(testName, true, "NEW_DP");

		// Print final comparison table
		System.out.println("\n--------------------------------------------------");
		System.out.printf("%-20s | %-15s\n", "Configuration", "Exec Time (ms)");
		System.out.println("--------------------------------------------------");
		System.out.printf("%-20s | %-15.2f\n", "Baseline (No Opt)", timeBaseline);
		System.out.printf("%-20s | %-15.2f\n", "Old Heuristic", timeOldHeuristic);
		System.out.printf("%-20s | %-15.2f\n", "New Dual-State DP", timeNewDP);
		System.out.println("--------------------------------------------------\n");
	}

	/**
	 * Executes the script, captures System.out, and extracts the printed time.
	 */
	private double executeAndExtractTime(String testName, boolean enableRewrites, String mode) {
		ExecMode platformOld = rtplatform;
		boolean rewritesOld = OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION;

		PrintStream originalOut = System.out;
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		System.setOut(new PrintStream(bos));

		try {
			rtplatform = ExecMode.SINGLE_NODE;
			OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION = enableRewrites;

			TestConfiguration config = getTestConfiguration(testName);
			loadTestConfiguration(config);

			fullDMLScriptName = SCRIPT_DIR + TEST_DIR + testName + ".dml";
			programArgs = new String[]{"-stats", "-args", output("R")};

			// Execute
			runTest(true, false, null, -1);

		} finally {
			System.setOut(originalOut);
			OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION = rewritesOld;
			rtplatform = platformOld;
			Recompiler.reinitRecompiler();
		}

		String consoleOutput = bos.toString();

		Pattern pattern = Pattern.compile("Average Execution Time: ([0-9]+(?:\\.[0-9]+)?) ms");
		Matcher matcher = pattern.matcher(consoleOutput);

		if (matcher.find()) {
			return Double.parseDouble(matcher.group(1));
		} else {
			System.err.println("Failed to parse time for mode: " + mode + ".\nOutput was:\n" + consoleOutput);
			return -1.0;
		}
	}
}
