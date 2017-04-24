package regioClust;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.log4j.Logger;

public class Clustering {

	private static Logger log = Logger.getLogger(Clustering.class);

	public enum HierarchicalClusteringType {
		single_linkage, complete_linkage, average_linkage, ward
	};
		
	public static List<TreeNode> cutTree( Collection <TreeNode> roots, int numCluster ) {
		Comparator<TreeNode> comp = new Comparator<TreeNode>() {
			@Override
			public int compare(TreeNode o1, TreeNode o2) {
				return -Integer.compare(o1.age, o2.age);
			}
		};
		
		PriorityQueue<TreeNode> pq = new PriorityQueue<TreeNode>(1, comp);
		pq.addAll( roots );
		while( pq.size() < numCluster ) { 
			TreeNode tn = pq.poll();
			if( tn == null )
				throw new RuntimeException("Too few observations for the desired number of clusters!");
			
			for( TreeNode child : tn.children )
				if( child != null )
					pq.add(child);
		}
		return new ArrayList<>(pq);
	}
		
	public static Set<TreeNode> getSubtree( TreeNode node ) {
		Set<TreeNode> s = new HashSet<>();
		s.add(node);
		for( TreeNode child : node.children ) {
			if( child == null )
				continue;
			s.addAll( getSubtree(child) );
		}
		return s;
	}
	
	public static Set<TreeNode> getLeafLayer( TreeNode node ) {
		Set<TreeNode> leafLayer = new HashSet<>();
		for( TreeNode n : getSubtree(node) )
			if( n.age == 0 )
				leafLayer.add(n);
		return leafLayer;
	}
	
	public static List<Set<double[]>> treeToCluster(Collection<TreeNode> roots) {
		List<Set<double[]>> clusters = new ArrayList<Set<double[]>>();
		for( TreeNode r : roots ) 
			clusters.add( getContents(r) );
		return clusters;
	}
	
	// Gets contents of a node (contents of leaf nodes with node of tree with node as root node)
	public static Set<double[]> getContents( TreeNode node ) {
		Set<double[]> contents = new HashSet<>();
		for( TreeNode n : getLeafLayer(node) )
			contents.addAll(n.contents);
		return contents;
	}
	
	public static class TreeNode {		
		public Set<double[]> contents = null; // only nodes of age 0 should ever have contents! 
		public int age = 0;
		public double cost = 0; 
		public List<TreeNode> children = new ArrayList<TreeNode>();
		
		public TreeNode( int age, double cost, Set<double[]> contents ) {
			this(age,cost);
			this.contents = contents;
		}
		
		public TreeNode( int age, double cost ) {
			this.age = age;
			this.cost = cost;
		}
		
		public void setChildren( List<TreeNode> children ) { this.children = children; }
		public void setContents( Set<double[]> contents ) { this.contents = contents; }
		public String toString() { return "["+age+", "+cost+"]"; }
	}
	
	public static List<TreeNode> samplesToTree(List<double[]> samples ) {
		List<TreeNode> l = new ArrayList<TreeNode>();
		for( double[] d : samples ) {
			TreeNode tn = new TreeNode(0,0);
			tn.contents = new HashSet<>();
			tn.contents.add(d);
			l.add(tn);
		}	
		return l;
	}
	

	public static <T> Set<T> getNodes(Map<T, Set<T>> cm) {
		Set<T> nodes = new HashSet<T>(cm.keySet());
		for (T a : cm.keySet())
			nodes.addAll(cm.get(a));
		return nodes;
	}
	
	public static Map<TreeNode,Set<TreeNode>> samplesCMtoTreeCM(Map<double[], Set<double[]>> cm) {
		Set<double[]> s = getNodes(cm);
		List<TreeNode> l = samplesToTree( new ArrayList<>(s) );
		
		Map<TreeNode,Set<TreeNode>> ncm = new HashMap<>();
		for( TreeNode tnA : l ) {
			double[] a = tnA.contents.iterator().next();
			
			Set<TreeNode> cs = new HashSet<>();
			for( double[] b : cm.get(a) )
				for( TreeNode tnB : l )
					if( tnB.contents.contains(b) )
						cs.add(tnB);
			ncm.put(tnA, cs);
		}
		return ncm;
	}
	
	public static List<TreeNode> getHierarchicalClusterTree( List<TreeNode> leafLayer, Map<TreeNode,Set<TreeNode>> cm, Dist<double[]> dist, HierarchicalClusteringType type ) {
		return getHierarchicalClusterTree(leafLayer, cm, dist, type, Integer.MAX_VALUE, Math.max(1 , Runtime.getRuntime().availableProcessors() -1 ) );
	}
	
	//@return roots of one or more trees
	public static List<TreeNode> getHierarchicalClusterTree( List<TreeNode> leafLayer, final Map<TreeNode,Set<TreeNode>> cm, Dist<double[]> dist, HierarchicalClusteringType type, int minSize, int threads ) {
						
		class FlatSet<T> extends HashSet<T> {
			private static final long serialVersionUID = -1960947872875758352L;
			public int hashCode = 0;
			
			@Override 
			public boolean add( T t ) {
				hashCode += t.hashCode();
				return super.add(t);
			}
			
			@Override
			public boolean addAll( Collection<? extends T> c ) {
				hashCode += c.hashCode();
				return super.addAll(c);
			}
			
			@Override
			public int hashCode() {
				return hashCode;
			}
		}
				
		List<TreeNode> tree = new ArrayList<>();
		Map<TreeNode,Set<double[]>> curLayer = new HashMap<>();
		
		Map<TreeNode, Double> ssCache = new HashMap<TreeNode, Double>();
		Map<TreeNode, Map<TreeNode,Double>> unionCache = new ConcurrentHashMap<>();
					
		int length = getContents(leafLayer.get(0)).iterator().next().length;
		int age = 0;
		for( TreeNode tn : leafLayer ) {
			
			age = Math.max( age, tn.age );
			tree.add(tn);
			
			Set<double[]> content = getContents(tn);
			curLayer.put(tn, content);
			ssCache.put(tn, DataUtils.getSumOfSquares(content, dist));
		}
						
		// copy of connected map
		final Map<TreeNode, Set<TreeNode>> connected = new HashMap<TreeNode, Set<TreeNode>>();
		if (cm != null) 
			for( Entry<TreeNode,Set<TreeNode>> e : cm.entrySet() )
				connected.put(e.getKey(),new HashSet<TreeNode>(e.getValue()));
				
		while (curLayer.size() > 1 ) {
			
			if( curLayer.size() % 1000 == 0 )
				log.debug(curLayer.size());
						
			List<TreeNode> cl = new ArrayList<>(curLayer.keySet());
						
			ExecutorService es = Executors.newFixedThreadPool(threads);
			List<Future<double[]>> futures = new ArrayList<Future<double[]>>();
			for (int t = 0; t < threads; t++) {
				final int T = t;

				futures.add(es.submit(new Callable<double[]>() {
					@Override
					public double[] call() throws Exception {
						int c1 = -1, c2 = -1;
						double sMin = Double.MAX_VALUE;
						
						for (int i = T; i < cl.size() - 1; i += threads) {
							TreeNode l1 = cl.get(i);
							
							Set<TreeNode> nbs = null;
							if( cm != null )
								if( connected.containsKey(l1) )
									nbs = connected.get(l1);
								else
									continue;
			
							for (int j = i + 1; j < cl.size(); j++) {
								TreeNode l2 = cl.get(j);
								
								if( nbs != null && !nbs.contains(l2) ) // disjoint
									continue;
								
								if( curLayer.get(l1).size() >= minSize && curLayer.get(l2).size() >= minSize )
									continue;
																							
								double s = Double.NaN;
								if (HierarchicalClusteringType.ward == type) {																														
									// get error sum of squares
									if (!unionCache.containsKey(l1) || !unionCache.get(l1).containsKey(l2)) {
																			
										// calculate mean and sum of squares, slightly faster than actually forming a union
										double[] mean = new double[length];
										for (int l = 0; l < length; l++) {
											for (double[] d : curLayer.get(l1) )
												mean[l] += d[l];
											for (double[] d : curLayer.get(l2) )
												mean[l] += d[l];
										}
																	
										for (int l = 0; l < length; l++)
											mean[l] /= curLayer.get(l1).size()+curLayer.get(l2).size();
										
										double ssUnion = 0;
										for( double[] d : curLayer.get(l1) ) {
											double di = dist.dist(mean, d);
											ssUnion += di * di;
										}
										for( double[] d : curLayer.get(l2) ) {
											double di = dist.dist(mean, d);
											ssUnion += di * di;
										}
										
										if (!unionCache.containsKey(l1))
											unionCache.put( l1, new HashMap<TreeNode, Double>() );
										unionCache.get(l1).put(l2, ssUnion);
									}													
									s = unionCache.get(l1).get(l2) - ( ssCache.get(l1) + ssCache.get(l2) );	
								} else if (HierarchicalClusteringType.single_linkage == type) {
									s = Double.MAX_VALUE;
									for (double[] d1 : curLayer.get(l1)) 
										for (double[] d2 : curLayer.get(l2)) 
											s = Math.min(s, dist.dist(d1, d2) );				
								} else if (HierarchicalClusteringType.complete_linkage == type) {
									s = Double.MIN_VALUE;
									for (double[] d1 : curLayer.get(l1))
										for (double[] d2 : curLayer.get(l2))
											s = Math.max(s, dist.dist(d1, d2) );
								} else if (HierarchicalClusteringType.average_linkage == type) {
									s = 0;
									for (double[] d1 : curLayer.get(l1)) 
										for (double[] d2 : curLayer.get(l2)) 
											s += dist.dist(d1, d2);
									s /= (curLayer.get(l1).size() * curLayer.get(l2).size());
								}
								if ( s < sMin) {
									c1 = i;
									c2 = j;
									sMin = s;
								}
							}
						}
					return new double[] { c1, c2, sMin };
					}
				}));
			}
			es.shutdown();
			
			TreeNode c1 = null, c2 = null;
			double sMin = Double.MAX_VALUE;			
			try {
				for (Future<double[]> f : futures) {
					double[] d = f.get();
					if (d[2] < sMin) {
						c1 = cl.get((int) d[0]);
						c2 = cl.get((int) d[1]);
						sMin = d[2];
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			} catch (ExecutionException e) {
				e.printStackTrace();
			}

			if (c1 == null && c2 == null) 
				return new ArrayList<TreeNode>( curLayer.keySet() );
												
			// create merge node, remove c1,c2		
			Set<double[]> union = new FlatSet<double[]>(); 
			union.addAll(curLayer.remove(c1));
			union.addAll(curLayer.remove(c2));	
			
			TreeNode mergeNode = new TreeNode(++age, sMin);
			mergeNode.children = Arrays.asList(new TreeNode[]{ c1, c2 });
			
			// update cache
			if( type == HierarchicalClusteringType.ward ) {
				ssCache.remove(c1);
				ssCache.remove(c2);
				ssCache.put( mergeNode, unionCache.get(c1).get(c2) );
				unionCache.remove(c1);
			} 
									
			// add nodes
			curLayer.put(mergeNode,union);
			tree.add(mergeNode);
			
			// update connected map
			if( cm != null ) {
				// 1. merge values of c1 and c2 and put union
				Set<TreeNode> ns = connected.remove(c1);
				ns.addAll( connected.remove(c2) );
				connected.put(mergeNode, ns);
				
				// 2. replace all values c1,c2 by merged node
				for( Set<TreeNode> s : connected.values() ) {
					if( s.contains(c1) || s.contains(c2)) {
						s.remove(c1);
						s.remove(c2);
						s.add(mergeNode);
					}
				}
			}
		}
		return new ArrayList<>(curLayer.keySet());
	}
}
