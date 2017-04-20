package regioClust;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.geotools.data.DataStore;
import org.geotools.data.FeatureSource;
import org.geotools.data.FeatureStore;
import org.geotools.data.FileDataStoreFactorySpi;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.Name;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.MultiPoint;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;

public class DataUtils {

	private static Logger log = Logger.getLogger(DataUtils.class);
	

	public static void writeShape(List<double[]> samples, List<Geometry> geoms, String fn) {
		writeShape(samples, geoms, null, null, fn);
	}

	public static void writeShape(List<double[]> samples, List<Geometry> geoms, String[] names, String fn) {
		writeShape(samples, geoms, names, null, fn);
	}

	public static void writeShape(List<double[]> samples, List<Geometry> geoms, String[] names, CoordinateReferenceSystem crs, String fn) {
		if (names != null && samples.get(0).length != names.length)
			throw new RuntimeException("column-length does not match names-length: " + samples.get(0).length + "!=" + names.length);

		try {
			SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
			typeBuilder.setName("samples");
			for (int i = 0; i < samples.get(0).length; i++) {
				if (names != null) {
					String n = names[i];
					if (n.length() > 8) {
						n = n.substring(0, 8);
						//log.debug("Trunkating " + names[i] + " to " + n);
					}
					typeBuilder.add(n, Double.class);
				} else {
					typeBuilder.add("data" + i, Double.class);
				}
			}

			if (crs != null)
				typeBuilder.setCRS(crs);
			else
				log.warn("CRS not set!");

			Geometry g = geoms.get(0);
			if (g instanceof Polygon)
				typeBuilder.add("the_geom", Polygon.class);
			else if (g instanceof MultiPolygon)
				typeBuilder.add("the_geom", MultiPolygon.class);
			else if (g instanceof Point)
				typeBuilder.add("the_geom", Point.class);
			else if (g instanceof MultiPoint)
				typeBuilder.add("the_geom", MultiPoint.class);
			else
				throw new RuntimeException("Unkown geometry type!");

			SimpleFeatureType type = typeBuilder.buildFeatureType();
			SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(type);

			ListFeatureCollection fc = new ListFeatureCollection(type);
			for (double[] d : samples) {
				for (int i = 0; i < d.length; i++)
					featureBuilder.add(d[i]);
				featureBuilder.add(geoms.get(samples.indexOf(d)));
				SimpleFeature sf = featureBuilder.buildFeature(null);
				fc.add(sf);
			}

			// store shape file, no coordinate reference system
			Map map = Collections.singletonMap("url", new File(fn).toURI().toURL());
			FileDataStoreFactorySpi factory = new ShapefileDataStoreFactory();
			DataStore myData = factory.createNewDataStore(map);
			myData.createSchema(fc.getSchema());
			Name name = myData.getNames().get(0);
			FeatureStore<SimpleFeatureType, SimpleFeature> store = (FeatureStore<SimpleFeatureType, SimpleFeature>) myData.getFeatureSource(name);

			store.addFeatures(fc);

			/*Transaction transaction = new DefaultTransaction("create");
			try {
				store.addFeatures(fc);
				transaction.commit();
			} catch (Exception e) {
				e.printStackTrace();
				transaction.rollback();
			} finally {
				transaction.close();
			}*/

			myData.dispose();
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static SpatialDataFrame readSpatialDataFrameFromShapefile(File file, boolean debug) {
		return readSpatialDataFrameFromShapefile(file, new int[] {}, debug);
	}

	public static SpatialDataFrame readSpatialDataFrameFromShapefile(File file, int[] toDouble, boolean debug) {

		SpatialDataFrame sd = new SpatialDataFrame();
		sd.samples = new ArrayList<double[]>();
		sd.geoms = new ArrayList<Geometry>();
		sd.names = new ArrayList<String>();
		sd.bindings = new ArrayList<SpatialDataFrame.binding>();

		DataStore dataStore = null;
		try {
			dataStore = new ShapefileDataStore((file).toURI().toURL());
			FeatureSource<SimpleFeatureType, SimpleFeature> featureSource = dataStore.getFeatureSource(dataStore.getTypeNames()[0]);
			sd.crs = featureSource.getSchema().getCoordinateReferenceSystem();

			Set<Integer> td = new HashSet<>();
			for (int i : toDouble)
				td.add(i);
			Set<Integer> ignore = new HashSet<Integer>();

			List<AttributeDescriptor> adl = featureSource.getFeatures().getSchema().getAttributeDescriptors(); // all
			for (int i = 0; i < adl.size(); i++) {
				AttributeDescriptor ad = adl.get(i);
				String bin = ad.getType().getBinding().getName();
								
				if (ignore.contains(i) )
					continue;

				if (bin.equals("java.lang.Integer")) {
					sd.names.add(ad.getLocalName());
					sd.bindings.add(SpatialDataFrame.binding.Integer);
				} else if (bin.equals("java.lang.Double") || td.contains(i) ) {
					sd.names.add(ad.getLocalName());
					sd.bindings.add(SpatialDataFrame.binding.Double);
				} else if (bin.equals("java.lang.Long")) {
					sd.names.add(ad.getLocalName());
					sd.bindings.add(SpatialDataFrame.binding.Long);
				} else {
					ignore.add(i);
					if (debug)
						log.debug("Ignoring " + ad.getLocalName() + ", because " + bin);
				}
			}

			if (debug) {
				int idx = 0;
				for (int i = 0; i < adl.size(); i++) {
					if (ignore.contains(i))
						log.debug(i + ":" + adl.get(i).getLocalName() + ", IGN");
					else
						log.debug(i + ":" + adl.get(i).getLocalName() + "," + (idx++));
				}
			}

			FeatureIterator<SimpleFeature> it = featureSource.getFeatures().features();
			try {
				while (it.hasNext()) {
					SimpleFeature feature = it.next();
					double[] d = new double[adl.size() - ignore.size()];

					int idx = 0;
					for (int i = 0; i < adl.size(); i++) {

						if (ignore.contains(i))
							continue;

						String name = adl.get(i).getLocalName();
						Object o = feature.getAttribute(name);
						if (td.contains(i) && o instanceof String)
							d[idx++] = Double.parseDouble((String) o);
						else if (o instanceof Double)
							d[idx++] = ((Double) o).doubleValue();
						else if (o instanceof Integer)
							d[idx++] = ((Integer) o).intValue();
						else if (o instanceof Long)
							d[idx++] = ((Long) o).longValue();
						else {
							log.error("Unknown attribute type: " + name + ", " + o + ", " + i);
							System.exit(1);
						}
					}

					sd.samples.add(d);
					sd.geoms.add((Geometry) feature.getDefaultGeometry());
				}
			} finally {
				if (it != null) {
					it.close();
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			dataStore.dispose();
		}
		return sd;
	}

	
	public static double[] getMean(Collection<double[]> cluster) {
		int l = cluster.iterator().next().length;
		double[] r = new double[l];
		for (double[] d : cluster)
			for (int i = 0; i < l; i++)
				r[i] += d[i];
		for (int i = 0; i < l; i++)
			r[i] /= cluster.size();
		return r;
	}
	
	public static double getSumOfSquares(Collection<double[]> s, Dist<double[]> dist) {
		double ssq = 0;
		if (s.isEmpty())
			return ssq;

		double[] mean = getMean(s);
		for (double[] d : s) {
			double di = dist.dist(mean, d);
			ssq += di * di;
		}

		return ssq;
	}

	public static double getWithinSumOfSquares(Collection<Set<double[]>> c, Dist<double[]> dist) {
		double ssq = 0;
		for (Set<double[]> s : c)
			ssq += getSumOfSquares(s, dist);
		return ssq;
	}

	public static double[] strip( double[] d, int[] fa ) {
		double[] nd = new double[fa.length];
		for( int j = 0; j < fa.length; j++ )
			nd[j] = d[fa[j]];
		return nd;
	}
}
