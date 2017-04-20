package regioClust;

import java.util.List;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.apache.log4j.Logger;

public class SupervisedUtils {

	private static Logger log = Logger.getLogger(SupervisedUtils.class);
	
	public static double getRMSE(List<Double> response, List<double[]> samples, int ta ) {
		return Math.sqrt(getMSE(response, samples, ta));
	}

	public static double getMSE(List<Double> response, List<double[]> samples, int ta) {
		return getResidualSumOfSquares(response, samples, ta) / response.size();
	}
	
	public static double getR2(List<Double> response, List<double[]> samples, int ta) {
		if (response.size() != samples.size())
			throw new RuntimeException("response size != samples size ("+response.size()+"!="+samples.size()+")" );

		double ssRes = 0;
		for (int i = 0; i < response.size(); i++)
			ssRes += Math.pow(samples.get(i)[ta] - response.get(i), 2);
		
		SummaryStatistics ss = new SummaryStatistics();
		for ( double[] d : samples )
			ss.addValue(d[ta]);

		double mean = 0;
		for (double[] d : samples)
			mean += d[ta];
		mean /= samples.size();

		double ssTot = 0;
		for (double[] d : samples )
			ssTot += Math.pow(d[ta] - mean, 2);
		
		return 1.0 - ssRes / ssTot;
	}

	public static double getAIC(double mse, double nrParams, int nrSamples) {
		return nrSamples * Math.log(mse) + 2 * nrParams;
	}
	
	public static double getAICc(double mse, double nrParams, int nrSamples) {
		if( nrSamples - nrParams - 1 <= 0 ) {
			log.error(nrSamples+","+nrParams);
			System.exit(1);
		}
		return getAIC(mse, nrParams, nrSamples) + (2.0 * nrParams * (nrParams + 1)) / (nrSamples - nrParams - 1);
	}
	
	// dp.n*log(sigma.hat2) + dp.n*log(2*pi) +dp.n+tr.S
	public static double getAIC_GWMODEL(double mse, double nrParams, int nrSamples) {
		return nrSamples * Math.log(mse) + nrSamples * Math.log(2*Math.PI) + nrSamples + nrParams;
		
	}
	
	// ##AICc = 	dev + 2.0 * (double)N * ( (double)MGlobal + 1.0) / ((double)N - (double)MGlobal - 2.0);
	// lm_AICc= dp.n*log(lm_RSS/dp.n)+dp.n*log(2*pi)+dp.n+2*dp.n*(var.n+1)/(dp.n-var.n-2)
	public static double getAICc_GWMODEL(double mse, double nrParams, int nrSamples) {
		if( nrSamples - nrParams - 2 <= 0 ) 
			throw new RuntimeException("too few samples! "+nrSamples+" "+nrParams);
		return nrSamples * ( Math.log(mse) + Math.log(2*Math.PI) + 1 ) + (2.0 * nrSamples * ( nrParams + 1 ) ) / (nrSamples - nrParams - 2);
	}

	public static double getBIC(double mse, double nrParams, int nrSamples) {
		return nrSamples * Math.log(mse) + nrParams * Math.log(nrSamples);
	}
	
	public static double getResidualSumOfSquares(List<Double> response, List<double[]> samples, int ta) {
		if (response.size() != samples.size())
			throw new RuntimeException("response.size() != samples.size() "+response.size()+","+samples.size());

		double rss = 0;
		for (int i = 0; i < response.size(); i++)
			rss += Math.pow(response.get(i) - samples.get(i)[ta], 2);
		return rss;
	}
}
