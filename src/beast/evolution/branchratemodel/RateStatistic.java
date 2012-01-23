/*
 * RateStatistic.java
 *
 * Copyright (C) 2002-2006 Alexei Drummond and Andrew Rambaut
 *
 * This file is part of BEAST.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership and licensing.
 *
 * BEAST is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 *  BEAST is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BEAST; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
 * Boston, MA  02110-1301  USA
 */

package beast.evolution.branchratemodel;

import java.io.PrintStream;

import beast.core.Description;
import beast.core.Input;
import beast.core.Loggable;
import beast.core.Plugin;
import beast.core.Valuable;
import beast.evolution.tree.Node;
import beast.evolution.tree.Tree;
import beast.math.statistic.DiscreteStatistics;


@Description("A statistic that tracks the mean, variance and coefficent of variation of rates. " +
        "It has three dimensions, one for each statistic.")
public class RateStatistic extends Plugin implements Loggable, Valuable {
    public Input<BranchRateModel> branchRateModelInput = new Input<BranchRateModel>("branchratemodel", "model that provides rates for a tree");
    public Input<Tree> treeInput = new Input<Tree>("tree", "tree for which the rates apply");
    public Input<Boolean> internalInput = new Input<Boolean>("internal", "consider internal nodes, default true", true);
    public Input<Boolean> externalInput = new Input<Boolean>("external", "consider external nodes, default true", true);

    private Tree tree = null;
    private BranchRateModel branchRateModel = null;
    private boolean internal = true;
    private boolean external = true;

    final static int MEAN = 0;
    final static int VARIANCE = 1;
    final static int COEFFICIENT_OF_VARIATION = 2;

    @Override
    public void initAndValidate() throws Exception {
        tree = treeInput.get();
        branchRateModel = branchRateModelInput.get();
        this.internal = internalInput.get();
        this.external = externalInput.get();
    }

    /**
     * calculate the three statistics from scratch *
     */
    public double[] calcValues() {
        int length = 0;
        int offset = 0;
        if (external) {
            length += tree.getLeafNodeCount();
        }
        if (internal) {
            length += tree.getInternalNodeCount() - 1;
        }

        final double[] rates = new double[length];
        // need those only for mean
        final double[] branchLengths = new double[length];

        Node[] nodes = tree.getNodesAsArray();
        int nrOfLeafs = tree.getLeafNodeCount();
        /** handle leaf nodes **/
        if (external) {
            for (int i = 0; i < nrOfLeafs; i++) {
                Node child = nodes[i];
                Node parent = child.getParent();
                branchLengths[i] = parent.getHeight() - child.getHeight();
                rates[i] = branchRateModel.getRateForBranch(child);
            }
            offset = nrOfLeafs;
        }

        /** handle internal nodes **/
        if (internal) {
            final int n = tree.getNodeCount();
            int k = offset;
            for (int i = nrOfLeafs; i < n; i++) {
                Node child = nodes[i];
                if (!child.isRoot()) {
                    Node parent = child.getParent();
                    branchLengths[k] = parent.getHeight() - child.getHeight();
                    rates[k] = branchRateModel.getRateForBranch(child);
                    k++;
                }
            }
        }

        double[] values = new double[3];
        double totalWeightedRate = 0.0;
        double totalTreeLength = 0.0;
        for (int i = 0; i < rates.length; i++) {
            totalWeightedRate += rates[i] * branchLengths[i];
            totalTreeLength += branchLengths[i];
        }
        values[MEAN] = totalWeightedRate / totalTreeLength;
        values[VARIANCE] = DiscreteStatistics.variance(rates);
        final double mean = DiscreteStatistics.mean(rates);
        values[COEFFICIENT_OF_VARIATION] = Math.sqrt(DiscreteStatistics.variance(rates, mean)) / mean;
        return values;
    }


    /**
     * Valuable implementation *
     */

    @Override
    public int getDimension() {
        return 3;
    }

    @Override
    public double getArrayValue() {
        return calcValues()[0];
    }

    @Override
    public double getArrayValue(int iDim) {
        if (iDim > 3) {
            throw new IllegalArgumentException();
        }
        return calcValues()[iDim];
    }


    /**
     * Loggable implementation *
     */

    @Override
    public void init(PrintStream out) throws Exception {
        String sID = getID();
        if (sID == null) {
            sID = "";
        }
        out.print(sID + ".mean\t" + sID + ".variance\t" + sID + ".coefficientOfVariation\t");
    }


    @Override
    public void log(int nSample, PrintStream out) {
        double[] values = calcValues();
        out.print(values[0] + "\t" + values[1] + "\t" + values[2] + "\t");
    }


    @Override
    public void close(PrintStream out) {
        // nothing to do
    }

}
