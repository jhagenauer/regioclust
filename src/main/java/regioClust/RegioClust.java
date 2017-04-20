package regioClust;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.log4j.Logger;

import com.beust.jcommander.JCommander;

import regioClust.Clustering.HierarchicalClusteringType;
import regioClust.Clustering.TreeNode;

public class RegioClust {

	private static Logger log = Logger.getLogger(RegioClust.class);

	public static void main(String[] args) {

		Parameters params = new Parameters();
		JCommander jc = new JCommander(params, args);

		if (params.help) {
			jc.usage();
			return;
		}

		int threads = params.nrThreads;
		log.debug("Threads: " + threads);

		File data = new File(params.inshape);

		SpatialDataFrame sdf = DataUtils.readSpatialDataFrameFromShapefile(data, new int[] { 1, 2 }, true);

		int[] fa = new int[params.indep.size()];
		for (int i = 0; i < params.indep.size(); i++)
			fa[i] = params.indep.get(i);
		int ta = params.dep;

		for (int i = 0; i < fa.length; i++)
			log.debug("fa " + i + ": " + sdf.names.get(fa[i]));
		log.debug("ta: " + ta + "," + sdf.names.get(ta));

		Dist<double[]> gDist = null;
		if (params.coords != null) {
			int[] ga = new int[params.coords.size()];
			for (int i = 0; i < params.coords.size(); i++)
				ga[i] = params.coords.get(i);
			gDist = new EuclideanDist(ga);
		} else if (params.inweight != null) {
			try {
				gDist = new DistMapDist<>(GeoUtils.readDistMatrixKeyValue(sdf.samples, new File(params.inweight)));
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
			throw new RuntimeException("No distance matrix NOR indices of Euclidean coordinates are given.");

		Map<double[], Set<double[]>> cm = null;
		if (params.incont != null) {
			cm = GeoUtils.readContiguityKeyValue(sdf.samples, new File(params.incont));
		} else {
			log.info("No contiguity matrix given. Creating one...");
			cm = GeoUtils.getContiguityMap(sdf.samples, sdf.geoms, false, false);
			log.info("Saving the contiguity matrix to distmap.wgt");
			GeoUtils.writeContiguityKeyValue(cm, sdf.samples, new File("contmap.ctg"));
		}

		int minObs = params.minObs;

		Map<TreeNode, Set<TreeNode>> cma = Clustering.samplesCMtoTreeCM(cm);
		List<TreeNode> treeNodes = new ArrayList<TreeNode>(Clustering.getNodes(cma));

		log.info("Step 1...");
		List<TreeNode> curLayer = Clustering.getHierarchicalClusterTree(treeNodes, cma, gDist, HierarchicalClusteringType.ward, minObs, threads);
		curLayer = Clustering.cutTree(curLayer, 1);
		log.debug("Done. Remaining cluster :"+curLayer.size() );

		log.info("Step 2...");
		Map<TreeNode, Set<TreeNode>> ncm = getCMforCurLayer(curLayer, cm);
		List<TreeNode> tree = getFunctionalClusterinTree(curLayer, ncm, fa, ta, threads);
		log.info("Done.");

		List<Set<double[]>> ct = Clustering.treeToCluster(Clustering.cutTree(tree, params.nrCluster));
		LinearModel lm = new LinearModel(sdf.samples, ct, fa, ta, false);
		double mse = SupervisedUtils.getMSE(lm.getPredictions(sdf.samples, fa), sdf.samples, ta);
		double aic = SupervisedUtils.getAICc_GWMODEL(mse, ct.size() * (fa.length + 1), sdf.samples.size());
		double bic = SupervisedUtils.getBIC(mse, ct.size() * (fa.length + 1), sdf.samples.size());
		double r2 = SupervisedUtils.getR2(lm.getPredictions(sdf.samples, fa), sdf.samples, ta);

		log.info("#Cluster: " + lm.cluster.size());
		log.info("RSS: " + lm.getRSS());
		log.info("R2: " + r2);
		log.info("AICc: " + aic);
		log.info("BIC: " + bic);
		log.info("MSE: " + mse);

		if (params.outshape != null) {
			List<double[]> l = new ArrayList<double[]>();
			for (double[] d : sdf.samples) {
				double[] ns = new double[3 + fa.length + 1];

				int i = sdf.samples.indexOf(d);
				ns[0] = lm.getResiduals().get(i);

				for (int j = 0; j < lm.cluster.size(); j++) {
					if (!lm.cluster.get(j).contains(d))
						continue;

					ns[1] = j; // cluster

					double[] beta = lm.getBeta(j);
					for (int k = 0; k < beta.length; k++)
						ns[2 + k] = beta[k];
					break;
				}
				l.add(ns);
			}

			String[] names = new String[2 + fa.length + 1];
			names[0] = "residual";
			names[1] = "cluster";
			for (int i = 0; i < fa.length; i++)
				names[2 + i] = sdf.names.get(fa[i]);
			names[names.length - 1] = "Intrcpt";

			DataUtils.writeShape(l, sdf.geoms, names, sdf.crs, params.outshape);
		}
	}

	public static List<TreeNode> getFunctionalClusterinTree(List<TreeNode> leafLayer, Map<TreeNode, Set<TreeNode>> cm,
			int[] fa, int ta, int threads) {

		class FlatSet<T> extends HashSet<T> {
			private static final long serialVersionUID = -1960947872875758352L;
			public int hashCode = 0;

			@Override
			public boolean add(T t) {
				hashCode += t.hashCode();
				return super.add(t);
			}

			@Override
			public boolean addAll(Collection<? extends T> c) {
				hashCode += c.hashCode();
				return super.addAll(c);
			}

			@Override
			public int hashCode() {
				return hashCode;
			}
		}

		Map<TreeNode, Set<double[]>> curLayer = new HashMap<>();
		int age = 0;
		for (TreeNode tn : leafLayer) {
			age = Math.max(age, tn.age);

			Set<double[]> content = Clustering.getContents(tn);
			curLayer.put(tn, content);
		}

		// copy of connected map
		final Map<TreeNode, Set<TreeNode>> connected = new HashMap<TreeNode, Set<TreeNode>>();
		if (cm != null)
			for (Entry<TreeNode, Set<TreeNode>> e : cm.entrySet())
				connected.put(e.getKey(), new HashSet<TreeNode>(e.getValue()));

		Map<TreeNode, Double> ssCache = new HashMap<TreeNode, Double>();
		for (Entry<TreeNode, Set<double[]>> e : curLayer.entrySet()) {
			List<Set<double[]>> sc1 = new ArrayList<>();
			sc1.add(e.getValue());
			double rss1 = new LinearModel(new ArrayList<>(e.getValue()), sc1, fa, ta, false).getRSS();
			ssCache.put(e.getKey(), rss1);
		}
		Map<TreeNode, Map<TreeNode, Double>> unionCache = new ConcurrentHashMap<>();

		while (curLayer.size() > 1) {

			List<TreeNode> cl = new ArrayList<>(curLayer.keySet());

			ExecutorService es = Executors.newFixedThreadPool(threads);
			List<Future<double[]>> futures = new ArrayList<Future<double[]>>();
			for (int t = 0; t < threads; t++) {
				final int T = t;

				futures.add(es.submit(new Callable<double[]>() {
					@Override
					public double[] call() throws Exception {
						int c1 = -1, c2 = -1;
						double minCost = Double.POSITIVE_INFINITY;

						for (int i = T; i < cl.size() - 1; i += threads) {
							TreeNode l1 = cl.get(i);

							if (!connected.containsKey(l1))
								continue;
							Set<TreeNode> nbs = connected.get(l1);

							for (int j = i + 1; j < cl.size(); j++) {
								TreeNode l2 = cl.get(j);

								if (!nbs.contains(l2)) // disjoint
									continue;

								Set<double[]> s1 = curLayer.get(l1);
								Set<double[]> s2 = curLayer.get(l2);

								if (!unionCache.containsKey(l1) || !unionCache.get(l1).containsKey(l2)) {
									List<double[]> l = new ArrayList<>();
									l.addAll(s1);
									l.addAll(s2);

									List<Set<double[]>> sc3 = new ArrayList<>();
									Set<double[]> s = new HashSet<>();
									s.addAll(s1);
									s.addAll(s2);
									sc3.add(s);

									double rssFull = new LinearModel(l, sc3, fa, ta, false).getRSS();
									if (!unionCache.containsKey(l1))
										unionCache.put(l1, new HashMap<TreeNode, Double>());
									unionCache.get(l1).put(l2, rssFull);
								}
								double cost = unionCache.get(l1).get(l2) - (ssCache.get(l1) + ssCache.get(l2));

								if (cost < minCost) {
									c1 = i;
									c2 = j;
									minCost = cost;
								}
							}
						}
						return new double[] { c1, c2, minCost };
					}
				}));
			}
			es.shutdown();

			TreeNode c1 = null, c2 = null;
			double sMin = Double.POSITIVE_INFINITY;
			try {
				for (Future<double[]> f : futures) {
					double[] d = f.get();

					if (d[0] >= 0 && (c1 == null || d[2] < sMin)) {
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

			if (c1 == null && c2 == null) {
				log.debug("Cannot merge further: " + curLayer.size());
				return new ArrayList<>(curLayer.keySet());
			}

			// create merge node, remove c1,c2
			Set<double[]> union = new FlatSet<double[]>();
			union.addAll(curLayer.remove(c1));
			union.addAll(curLayer.remove(c2));

			TreeNode mergeNode = new TreeNode(++age, sMin);
			mergeNode.children = Arrays.asList(new TreeNode[] { c1, c2 });

			{
				ssCache.remove(c1);
				ssCache.remove(c2);
				ssCache.put(mergeNode, unionCache.get(c1).get(c2));
				unionCache.remove(c1);
			}

			// add nodes
			curLayer.put(mergeNode, union);

			// update connected map
			if (connected != null) {
				// 1. merge values of c1 and c2 and put union
				Set<TreeNode> ns = connected.remove(c1);
				ns.addAll(connected.remove(c2));
				connected.put(mergeNode, ns);

				// 2. replace all values c1,c2 by merged node
				for (Set<TreeNode> s : connected.values()) {
					if (s.contains(c1) || s.contains(c2)) {
						s.remove(c1);
						s.remove(c2);
						s.add(mergeNode);
					}
				}
			}
		}
		return new ArrayList<>(curLayer.keySet());
	}

	public static Map<TreeNode, Set<TreeNode>> getCMforCurLayer(Collection<TreeNode> curLayer,
			Map<double[], Set<double[]>> cma) {
		Map<TreeNode, Set<double[]>> cont = new HashMap<>();
		for (TreeNode tn : curLayer)
			cont.put(tn, Clustering.getContents(tn));

		Map<TreeNode, Set<TreeNode>> ncm = new HashMap<>();
		for (TreeNode tnA : curLayer) {
			Set<TreeNode> s = new HashSet<>();
			for (double[] a : cont.get(tnA))
				for (double[] nb : cma.get(a))
					for (TreeNode tnB : curLayer)
						if (cont.get(tnB).contains(nb))
							s.add(tnB);
			ncm.put(tnA, s);
		}
		return ncm;
	}

}
