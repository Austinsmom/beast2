/*
 * CoalescentSimulator.java
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

package beast.evolution.tree;

import beast.core.Description;
import beast.core.Input;
import beast.core.Input.Validate;
import beast.core.StateNode;
import beast.core.StateNodeInitialiser;
import beast.evolution.alignment.Alignment;
import beast.evolution.alignment.TaxonSet;
import beast.evolution.tree.coalescent.PopulationFunction;
import beast.math.distributions.MRCAPrior;
import beast.math.distributions.ParametricDistribution;
import beast.util.HeapSort;
import beast.util.Randomizer;

import java.util.*;


@Description("This class provides the basic engine for coalescent simulation of a given demographic model over a given time period. ")
public class RandomTree extends Tree implements StateNodeInitialiser {
    public Input<Alignment> m_taxa = new Input<Alignment>("taxa","set of taxa to initialise tree with specified by alignment");
    //public Input<TaxonSet> m_taxonset = new Input<TaxonSet>("taxonset","set of taxa to initialise tree with specified by a taxonset", Validate.XOR, m_taxa);
    public Input<PopulationFunction> m_populationFunction = new Input<PopulationFunction>("populationModel","population function for generating coalescent???", Validate.REQUIRED);
    public Input<List<MRCAPrior>> m_calibrations = new Input<List<MRCAPrior>>("constraint", "specifies (monophyletic or height distribution) constraints on internal nodes", new ArrayList<MRCAPrior>());

    // total nr of taxa
    int m_nTaxa;
    // list of bitset representation of the taxon sets
    List<BitSet> m_bTaxonSets;
    // the first m_nIsMonophyletic of the m_bTaxonSets are monophyletic, while the remainder are not
    int m_nIsMonophyletic;
    // list of parametric distribution constraining the MRCA of taxon sets, null if not present
    List<ParametricDistribution> m_distributions;
    class Bound {
    	Double m_fUpper = Double.POSITIVE_INFINITY;
    	Double m_fLower = Double.NEGATIVE_INFINITY;
    	public String toString() {return "[" + m_fLower + "," + m_fUpper + "]";}
    }
    List<Bound> m_bounds;
    List<String> m_sTaxonSetIDs;
    
    List<Integer> [] m_children;

    // number of the next internal node, used when creating new internal nodes
    int m_nNextNodeNr;
    
	// used to indicate one of the MRCA constraints could not be met
    protected class ConstraintViolatedException extends Exception {
		private static final long serialVersionUID = 1L;
	}

	@Override
    public void initAndValidate() throws Exception {
        List<String> sTaxa;
        if (m_taxa.get() != null) {
                sTaxa = m_taxa.get().getTaxaNames();
        } else {
                sTaxa = m_taxonset.get().asStringList();
        }
        m_nTaxa = sTaxa.size();

        // find taxon sets we are dealing with
        m_bTaxonSets = new ArrayList<BitSet>();
        m_bounds = new ArrayList<Bound>();
        m_distributions = new ArrayList<ParametricDistribution>();
        m_sTaxonSetIDs = new ArrayList<String>();
        m_nIsMonophyletic = 0;
        for (MRCAPrior prior : m_calibrations.get()) {
    		TaxonSet taxonSet = prior.m_taxonset.get();
    		BitSet bTaxa = new BitSet(m_nTaxa);
    		for (String sTaxonID : taxonSet.asStringList()) {
    			int iID = sTaxa.indexOf(sTaxonID);
    			if (iID < 0) {
    				throw new Exception("Taxon <" + sTaxonID + "> could not be found in list of taxa. Choose one of " + sTaxa.toArray(new String[0]));
    			}
    			bTaxa.set(iID);
    		}
			ParametricDistribution distr = prior.m_distInput.get();
			Bound bounds = new Bound();
			if (distr != null) {
				bounds.m_fLower = distr.inverseCumulativeProbability(0.0); 
				bounds.m_fUpper = distr.inverseCumulativeProbability(1.0); 
			}

			if (prior.m_bIsMonophyleticInput.get()) {
				// add any monophyletic constraint
    			m_bTaxonSets.add(m_nIsMonophyletic, bTaxa);
    			m_distributions.add(m_nIsMonophyletic, distr);
    			m_bounds.add(m_nIsMonophyletic, bounds);
    			m_nIsMonophyletic++;
    		} else {
    			// only calibrations with finite bounds are added
    			if (!Double.isInfinite(bounds.m_fLower) || !Double.isInfinite(bounds.m_fUpper)) {
	        		m_bTaxonSets.add(bTaxa);
	        		m_distributions.add(distr);
	    			m_bounds.add(bounds);
    			}
    		}
    	}
    	
        // assume all calibration constraints are MonoPhyletic
        // TODO: verify that this is a reasonable assumption
        m_nIsMonophyletic = m_bTaxonSets.size();
        
        
    	// sort constraints such that if taxon set i is subset of taxon set j, then i < j
        for (int i = 0; i < m_nIsMonophyletic; i++) {
        	for (int j = i + 1; j < m_nIsMonophyletic; j++) {
        		boolean bIntersects = intersects(m_bTaxonSets.get(i), m_bTaxonSets.get(j));
        		if (bIntersects) {
	        		boolean bIsSubset = isSubset(m_bTaxonSets.get(j), m_bTaxonSets.get(i));
	        		boolean bIsSubset2 = isSubset(m_bTaxonSets.get(i), m_bTaxonSets.get(j));
	                // sanity check: make sure either 
	                // o taxonset1 is subset of taxonset2 OR 
	                // o taxonset1 is superset of taxonset2 OR 
	                // o taxonset1 does not intersect taxonset2
	        		if (!(bIsSubset || bIsSubset2)) {
	        			throw new Exception("333: Don't know how to generate a Random Tree for taxon sets that intersect, " +
	        					"but are not inclusive. Taxonset " + m_sTaxonSetIDs.get(i) + " and " + m_sTaxonSetIDs.get(j));
	        		}
	        		// swap i & j if b1 subset of b2
	        		if (bIsSubset) {
	        			swap(m_bTaxonSets, i, j);
	        			swap(m_distributions, i, j);
	        			swap(m_bounds, i, j);
	        			swap(m_sTaxonSetIDs, i, j);
	        		}
        		}
			}
        }
        
    	// build tree of mono constraints such that i is parent of j => j is subset of i
        int [] nParent = new int[m_nIsMonophyletic];
        m_children = new List[m_nIsMonophyletic+1];
        for (int i = 0; i < m_nIsMonophyletic+1; i++) {
        	m_children[i] = new ArrayList<Integer>();
        }        
        for (int i = 0; i < m_nIsMonophyletic; i++) {
        	int j = i+1;
        	while (j < m_nIsMonophyletic && !isSubset(m_bTaxonSets.get(i), m_bTaxonSets.get(j))) {
        		j++;
        	}
        	nParent[i] = j;
        	m_children[j].add(i);
        }
        
        // make sure upper bounds of a child does not exceed the upper bound of its parent
        for (int i = 0; i < m_nIsMonophyletic; i++) {
        	if (nParent[i] < m_nIsMonophyletic) {
        		if (m_bounds.get(i).m_fUpper > m_bounds.get(nParent[i]).m_fUpper) {
        			m_bounds.get(i).m_fUpper = m_bounds.get(nParent[i]).m_fUpper - 1e-100;
        		}
        	}
        }
        
        initStateNodes();
        super.initAndValidate();
    }

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void swap(List list, int i, int j) {
		Object tmp = list.get(i);
		list.set(i, list.get(j));
		list.set(j, tmp);
	}

	// taxonset intersection test
	private boolean intersects(BitSet bitSet, BitSet bitSet2) {
		for (int k = bitSet.nextSetBit(0); k >= 0; k = bitSet.nextSetBit(k+1)) {
			if (bitSet2.get(k)) {
				return true;
			}
		}
		return false;
	}
	
	// taxonset subset test
    private boolean isSubset(BitSet bitSet, BitSet bitSet2) {
		boolean bIsSubset = true;
		for (int k = bitSet.nextSetBit(0); bIsSubset && k >= 0; k = bitSet.nextSetBit(k+1)) {
			bIsSubset = bitSet2.get(k);
		}
		return bIsSubset;
	}

	@Override
    public void initStateNodes() {
            List<String> sTaxa;
            if (m_taxa.get() != null) {
                    sTaxa = m_taxa.get().getTaxaNames();
            } else {
                    sTaxa = m_taxonset.get().asStringList();
            }
            PopulationFunction popFunction = m_populationFunction.get();

            simulateTree(sTaxa, popFunction);

            nodeCount = 2*sTaxa.size()-1;
            internalNodeCount = sTaxa.size()-1;
            leafNodeCount = sTaxa.size();
            initArrays();
            
            if (m_initial.get() != null) {
                    m_initial.get().assignFromWithoutID(this);
            }
    }

    @Override
    public List<StateNode> getInitialisedStateNodes() {
            List<StateNode> stateNodes = new ArrayList<StateNode>();
            stateNodes.add(m_initial.get());
            return stateNodes;
	}

	/**
	 * Simulates a coalescent tree, given a taxon list.
	 * 
	 * @param taxa
	 *            the set of taxa to simulate a coalescent tree between
	 * @param demoFunction
	 *            the demographic function to use
	 */
	public void simulateTree(List<String> taxa, PopulationFunction demoFunction) {
		if (taxa.size() == 0)
			return;

		for (int attempts = 0; attempts < 1000; ++attempts) {
			try {
	            m_nNextNodeNr = m_nTaxa;
				List<Node> candidates = new ArrayList<Node>();
				for (int i = 0; i < taxa.size(); i++) {
					Node node = new Node();
					node.setNr(i);
					node.setID(taxa.get(i));
					node.setHeight(0.0);
					candidates.add(node);
				}
		
				if (m_trait.get() != null) {
					// set tip dates
					for (Node node : candidates) {
						node.setMetaData(m_trait.get().getTraitName(), m_trait.get().getValue(node.getNr()));
					}
				}
		
				// TODO: deal with dated taxa
				double fMostRecent = 0;
				for (Node node : candidates) {
					fMostRecent = Math.max(fMostRecent, node.getHeight());
				}
				// dr.evolution.util.Date mostRecent = null;
				// boolean usingDates = false;
				//
				// for (int i = 0; i < taxa.size(); i++) {
				// if (TaxonList.Utils.hasAttribute(taxa, i,
				// dr.evolution.util.Date.DATE)) {
				// usingDates = true;
				// dr.evolution.util.Date date =
				// (dr.evolution.util.Date)taxa.getTaxonAttribute(i,
				// dr.evolution.util.Date.DATE);
				// if ((date != null) && (mostRecent == null || date.after(mostRecent)))
				// {
				// mostRecent = date;
				// }
				// } else {
				// // assume contemporaneous tips
				// candidates.get(i).setHeight(0.0);
				// }
				// }
				//
				// if (usingDates && mostRecent != null ) {
				// TimeScale timeScale = new TimeScale(mostRecent.getUnits(), true,
				// mostRecent.getAbsoluteTimeValue());
				//
				// for (int i =0; i < taxa.size(); i++) {
				// dr.evolution.util.Date date =
				// (dr.evolution.util.Date)taxa.getTaxonAttribute(i,
				// dr.evolution.util.Date.DATE);
				// if (date == null) {
				// throw new IllegalArgumentException("Taxon, " + taxa.get(i) +
				// ", is missing its date");
				// }
				//
				// candidates.get(i).setHeight(timeScale.convertTime(date.getTimeValue(),
				// date));
				// }
				// if (demoFunction.getUnits() != mostRecent.getUnits()) {
				// //throw new
				// IllegalArgumentException("The units of the demographic model and the most recent date must match!");
				// }
				// }
		
				List<Node> allCandidates = new ArrayList<Node>();
				allCandidates.addAll(candidates);
				root = simulateCoalescent(m_nIsMonophyletic, allCandidates, candidates, demoFunction);
				return;
			} catch (ConstraintViolatedException e) {
				// need to generate another tree
			}
		}
	}
	

	private Node simulateCoalescent(int iIsMonophyleticNode, List<Node> allCandidates, List<Node> candidates, PopulationFunction demoFunction) 
		throws ConstraintViolatedException {
		List<Node> remainingCandidates = new ArrayList<Node>();
		BitSet taxaDone = new BitSet(m_nTaxa);
		for (int iMonoNode : m_children[iIsMonophyleticNode]) {
			// create list of leaf nodes for this monophyletic MRCA
			List<Node> candidates2 = new ArrayList<Node>();
			BitSet bTaxonSet = m_bTaxonSets.get(iMonoNode);
			for (int k = bTaxonSet.nextSetBit(0); k >=0; k = bTaxonSet.nextSetBit(k+1)) {
				candidates2.add(allCandidates.get(k));
			}
			
			Node MRCA = simulateCoalescent(iMonoNode, allCandidates, candidates2, demoFunction);
			remainingCandidates.add(MRCA);
			taxaDone.or(bTaxonSet);
		}
		
		for (Node node : candidates) {
			if (!taxaDone.get(node.getNr())) {
				remainingCandidates.add(node);
			}
		}
		
		Node MRCA = simulateCoalescent(remainingCandidates, demoFunction);
		return MRCA;
	}

	/**
	 * @return the root node of the given array of nodes after simulation of the
	 *         coalescent under the given demographic model.
	 */
	public Node simulateCoalescent(List<Node> nodes, PopulationFunction demographic) throws ConstraintViolatedException {
		// sanity check - disjoint trees

		// if( ! Tree.Utils.allDisjoint(nodes) ) {
		// throw new RuntimeException("non disjoint trees");
		// }

		if (nodes.size() == 0) {
			throw new IllegalArgumentException("empty nodes set");
		}

		for (int attempts = 0; attempts < 1000; ++attempts) {
			List<Node> rootNode = simulateCoalescent(nodes, demographic, 0.0, Double.POSITIVE_INFINITY);
			if (rootNode.size() == 1) {
				return rootNode.get(0);
			}
		}

		throw new RuntimeException("failed to merge trees after 1000 tries!");
	}

	public List<Node> simulateCoalescent(List<Node> nodes, PopulationFunction demographic, double currentHeight,
			double maxHeight) throws ConstraintViolatedException {
		// If only one node, return it
		// continuing results in an infinite loop
		if (nodes.size() == 1)
			return nodes;

		double[] heights = new double[nodes.size()];
		for (int i = 0; i < nodes.size(); i++) {
			heights[i] = nodes.get(i).getHeight();
		}
		int[] indices = new int[nodes.size()];
		HeapSort.sort(heights, indices);

		// node list
		nodeList.clear();
		activeNodeCount = 0;
		for (int i = 0; i < nodes.size(); i++) {
			nodeList.add(nodes.get(indices[i]));
		}
		setCurrentHeight(currentHeight);

		// get at least two tips
		while (getActiveNodeCount() < 2) {
			currentHeight = getMinimumInactiveHeight();
			setCurrentHeight(currentHeight);
		}

		// simulate coalescent events
		double nextCoalescentHeight = currentHeight
				+ PopulationFunction.Utils.getSimulatedInterval(demographic, getActiveNodeCount(), currentHeight);

		// while (nextCoalescentHeight < maxHeight && (getNodeCount() > 1)) {
		while (nextCoalescentHeight < maxHeight && (nodeList.size() > 1)) {

			if (nextCoalescentHeight >= getMinimumInactiveHeight()) {
				currentHeight = getMinimumInactiveHeight();
				setCurrentHeight(currentHeight);
			} else {
				currentHeight = coalesceTwoActiveNodes(currentHeight, nextCoalescentHeight);
			}

			// if (getNodeCount() > 1) {
			if (nodeList.size() > 1) {
				// get at least two tips
				while (getActiveNodeCount() < 2) {
					currentHeight = getMinimumInactiveHeight();
					setCurrentHeight(currentHeight);
				}

				// nextCoalescentHeight = currentHeight +
				// DemographicFunction.Utils.getMedianInterval(demographic,
				// getActiveNodeCount(), currentHeight);
				nextCoalescentHeight = currentHeight
						+ PopulationFunction.Utils.getSimulatedInterval(demographic, getActiveNodeCount(),
								currentHeight);
			}
		}

		return nodeList;

		// Node[] nodesLeft = new Node[nodeList.size()];
		// for (int i = 0; i < nodesLeft.length; i++) {
		// nodesLeft[i] = nodeList.get(i);
		// }
		//
		// return nodesLeft;
	}

	/**
	 * @return the height of youngest inactive node.
	 */
	private double getMinimumInactiveHeight() {
		if (activeNodeCount < nodeList.size()) {
			return (nodeList.get(activeNodeCount)).getHeight();
		} else
			return Double.POSITIVE_INFINITY;
	}

	/**
	 * Set the current height.
	 */
	private void setCurrentHeight(double height) {
		while (getMinimumInactiveHeight() <= height) {
			activeNodeCount += 1;
		}
	}

	/**
	 * @return the numver of active nodes (equate to lineages)
	 */
	private int getActiveNodeCount() {
		return activeNodeCount;
	}

	//
	// /**
	// * @return the total number of nodes both active and inactive
	// */
	// private int getNodeCount() {
	// return nodeList.size();
	// }
	//
	/**
	 * Coalesce two nodes in the active list. This method removes the two
	 * (randomly selected) active nodes and replaces them with the new node at
	 * the top of the active list.
	 */
	private double coalesceTwoActiveNodes(double fMinHeight, double height) throws ConstraintViolatedException {
		int node1 = Randomizer.nextInt(activeNodeCount);
		int node2 = node1;
		while (node2 == node1) {
			node2 = Randomizer.nextInt(activeNodeCount);
		}

		Node left = nodeList.get(node1);
		Node right = nodeList.get(node2);

		Node newNode = new Node();
//		System.err.println(2 * m_taxa.get().getNrTaxa() - nodeList.size());
		newNode.setNr(m_nNextNodeNr++);
		newNode.setHeight(height);
		newNode.m_left = left;
		left.setParent(newNode);
		newNode.m_right = right;
		right.setParent(newNode);

		nodeList.remove(left);
		nodeList.remove(right);

		activeNodeCount -= 2;

		nodeList.add(activeNodeCount, newNode);

		activeNodeCount += 1;

		// check if there is a calibration on this node
		Integer iConstraint = getDistrConstraint(newNode);
		if (iConstraint != null) {
//			for (int i = 0; i < 1000; i++) {
//				try {
//					height = distr.sample(1)[0][0];
//				} catch (Exception e) {
//					e.printStackTrace();
//				}
//				if (height > fMinHeight) {
//					break;
//				}
//			} 
			double fMin = Math.max(m_bounds.get(iConstraint).m_fLower, fMinHeight);
			double fMax = m_bounds.get(iConstraint).m_fUpper;
			if (fMax < fMin) {
				// failed to draw a matching height from the MRCA distribution
				// TODO: try to scale rest of tree down
				throw new ConstraintViolatedException();
			}
			if (height < fMin || height > fMax) {
				height = fMin + Randomizer.nextDouble() * (fMax - fMin);
				newNode.setHeight(height);
			}
		}


		if (getMinimumInactiveHeight() < height) {
			throw new RuntimeException(
					"This should never happen! Somehow the current active node is older than the next inactive node!");
		}
		return height;
	}
	
	private Integer getDistrConstraint(Node node) {
		for (int i = 0; i < m_distributions.size(); i++) {
			if (m_distributions.get(i) != null) {
				BitSet taxonSet = m_bTaxonSets.get(i);
				if (traverse(node, taxonSet, taxonSet.cardinality(), new int[1]) == m_nTaxa + 127) {
					return i;
				}
			}
		}
		return null;
	}

	int traverse(Node node, BitSet MRCATaxonSet, int nNrOfMRCATaxa, int[] nTaxonCount) {
		if (node.isLeaf()) {
			nTaxonCount[0]++;
			if (MRCATaxonSet.get(node.getNr())) {
				return 1;
			} else {
				return 0;
			}
		} else {
			int iTaxons = traverse(node.m_left, MRCATaxonSet, nNrOfMRCATaxa, nTaxonCount);
			int nLeftTaxa = nTaxonCount[0]; 
			nTaxonCount[0] = 0;
			if (node.m_right != null) {
				iTaxons += traverse(node.m_right, MRCATaxonSet, nNrOfMRCATaxa, nTaxonCount);
				int nRightTaxa = nTaxonCount[0]; 
				nTaxonCount[0] = nLeftTaxa + nRightTaxa;
			}
			if (iTaxons == m_nTaxa + 127) {
				iTaxons++;
			}
			if (iTaxons == nNrOfMRCATaxa) {
				// we are at the MRCA, return magic nr
				return m_nTaxa + 127;
			}
			return iTaxons ;
		}
	}
	
	
	@Override
    public String [] getTaxaNames() {
    	if (m_sTaxaNames == null) {
            List<String> sTaxa;
            if (m_taxa.get() != null) {
                    sTaxa = m_taxa.get().getTaxaNames();
            } else {
                    sTaxa = m_taxonset.get().asStringList();
            }
            m_sTaxaNames = sTaxa.toArray(new String[0]);
    	}
    	return m_sTaxaNames;
    }

	final private ArrayList<Node> nodeList = new ArrayList<Node>();
	private int activeNodeCount = 0;

	
}