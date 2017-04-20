package regioClust;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;

import com.vividsolutions.jts.geom.Geometry;

public class GeoUtils {
			
	// useful to strip large distance-matrices from not relevant(??) entries
	public static Map<double[],Map<double[],Double>> getKNearestFromMatrix( final Map<double[],Map<double[],Double>> invDistMatrix, int k ) {
		Map<double[],Map<double[],Double>> knnM = new HashMap<double[],Map<double[],Double>>();
		for( final double[] a : invDistMatrix.keySet() ) {
			PriorityQueue<double[]> pq = new PriorityQueue<>( invDistMatrix.get(a).size(), new Comparator<double[]>() {
				@Override
				public int compare(double[] o1, double[] o2) {
					return invDistMatrix.get(a).get(o1).compareTo(invDistMatrix.get(a).get(o2));
				}
			});
			pq.addAll(invDistMatrix.get(a).keySet());
			
			Map<double[],Double> sub = new HashMap<double[],Double>();
			while( sub.size() < k ) {
				double[] d = pq.poll();
				sub.put(d,invDistMatrix.get(a).get(d));
			}			
			knnM.put(a, sub);
		}
		return knnM;
	}
		
	public static <T> Map<T, Map<T, Double>> getDistanceMatrix(Collection<T> samples, Dist<T> gDist, boolean withIdentity ) {
		Map<T, Map<T, Double>> r = new HashMap<T, Map<T, Double>>();
		for (T a : samples) {
			Map<T, Double> m = new HashMap<T, Double>();
			for (T b : samples) {
				if (a == b && !withIdentity)
					continue;
				m.put(b, gDist.dist(a, b));
			}
			r.put(a, m);
		}
		return r;
	}
	
	public static Map<double[], List<double[]>> getKNNs(final List<double[]> samples, final Dist<double[]> gDist, int k, boolean includeIdentity) {
		Map<double[], List<double[]>> r = new HashMap<double[], List<double[]>>();
				
		for (final double[] x : samples) {
			PriorityQueue<double[]> pq = new PriorityQueue<>(samples.size(), new Comparator<double[]>() {
				@Override
				public int compare(double[] o1, double[] o2) {
					return Double.compare(gDist.dist(x, o1),gDist.dist(x, o2));
				}
			});
			pq.addAll(samples);
			List<double[]> sub = new ArrayList<double[]>();
			if( !includeIdentity )
				pq.poll(); // drop first/identity
			while( sub.size() < k )
				sub.add(pq.poll());
			r.put(x, sub);
		}
		return r;
	}
	
	public static <T> void writeDistMatrixKeyValue(Map<T, Map<T, Double>> dMap, List<T> samples, File fn) {
		Map<T,Integer> idxMap = new HashMap<T,Integer>();
		for( int i = 0; i < samples.size(); i++ )
			idxMap.put(samples.get(i), i);
		try {
			FileWriter fw = new FileWriter(fn);
			fw.write("id1,id2,dist\n");
			for (Entry<T, Map<T, Double>> e1 : dMap.entrySet()) {
				int a = idxMap.get(e1.getKey());
				for (Entry<T, Double> e2 : e1.getValue().entrySet())
					fw.write(a + "," + idxMap.get(e2.getKey()) + "," + e2.getValue() + "\n");
			}
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static <T> Map<T, Map<T, Double>> readDistMatrixKeyValue(List<T> samples, File fn) throws NumberFormatException, IOException, FileNotFoundException {
		Map<T, Map<T, Double>> distMatrix = new HashMap<T, Map<T, Double>>();
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader(fn));
			String line = br.readLine(); // ignore first line by reading but not using
			while ((line = br.readLine()) != null) {
	
				String[] s = line.split(",");
	
				T a = samples.get(Integer.parseInt(s[0]));
				T b = samples.get(Integer.parseInt(s[1]));
	
				if (!distMatrix.containsKey(a))
					distMatrix.put(a, new HashMap<T, Double>());
	
				distMatrix.get(a).put(b, Double.parseDouble(s[2]));
			}
		} finally {
			try {
				if( br != null )
					br.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return distMatrix;
	}
		
	public static Map<double[], Set<double[]>> getContiguityMap(List<double[]> samples, List<Geometry> geoms, boolean rookAdjacency, boolean includeIdentity ) {
		Map<double[], Set<double[]>> r = new HashMap<>();
		for( int i = 0; i < samples.size(); i++ ) {
			Geometry a = geoms.get(i);
			Set<double[]> l = new HashSet<>();
			for( int j = 0; j < samples.size(); j++ ) {
				Geometry b = geoms.get(j);
				if( !includeIdentity && a == b )
					continue;				
				if( !rookAdjacency ) { // queen
					if( a.touches(b) || a.intersects(b) )
							l.add( samples.get(j));
				} else { // rook
					if( a.intersection(b).getCoordinates().length > 1 ) // SLOW
						l.add( samples.get(j));
				}
			}
			r.put(samples.get(i), l);
		}
		return r;
	}

	public static <T> Map<T, Set<T>> readContiguityKeyValue(List<T> samples, File fn) {
		Map<T, Set<T>> cm = new HashMap<T, Set<T>>();
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader(fn));
			String line = br.readLine(); // ignore first line by reading but not using
			while ((line = br.readLine()) != null) {
	
				String[] s = line.split(",");
	
				T a = samples.get(Integer.parseInt(s[0]));
				T b = samples.get(Integer.parseInt(s[1]));
	
				if (!cm.containsKey(a))
					cm.put(a, new HashSet<T>());
				
				cm.get(a).add(b);
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				if( br != null )
					br.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return cm;
	}
	
	public static <T> void writeContiguityKeyValue(Map<T, Set<T>> dMap, List<T> samples, File fn) {
		Map<T,Integer> idxMap = new HashMap<T,Integer>();
		for( int i = 0; i < samples.size(); i++ )
			idxMap.put(samples.get(i), i);
		try {
			FileWriter fw = new FileWriter(fn);
			fw.write("id1,id2\n");
			for (Entry<T, Set<T>> e1 : dMap.entrySet()) {
				int a = idxMap.get(e1.getKey());
				for (T e2 : e1.getValue())
					fw.write(a + "," + idxMap.get(e2) + "\n");
			}
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
