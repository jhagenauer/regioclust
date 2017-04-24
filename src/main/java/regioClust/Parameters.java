package regioClust;

import java.util.ArrayList;
import java.util.List;

import com.beust.jcommander.Parameter;

public class Parameters {
	@Parameter(names = "-indep", description = "Indices of independent variables", required = true)
	List<Integer> indep;

	@Parameter(names = "-dep", description = "Index of dependent variables", required = true)
	Integer dep;
	
	@Parameter(names = "-coords", description = "Index of coordinates")
	List<Integer> coords = null;
	
	@Parameter(names = "-inshape", description = "Input ShapeFile", required = true)
	String inshape;
	
	@Parameter(names = "-outshape", description = "Output ShapeFile", required = true)
	String outshape;
	
	@Parameter(names = "-indist", description = "Input dist matrix")
	String inweight = null;
	
	@Parameter(names = "-incont", description = "Input contiguity matrix")
	String incont = null;
	
	@Parameter(names = "-threads", description = "Number of threads.")
	Integer nrThreads = 1;
	
	@Parameter(names = "-minobs", description = "Min. observations per cluster", required = true )
	Integer minObs;
	
	@Parameter(names = "-cluster", description = "Number of clusters", required = true )
	Integer nrCluster;
	
	@Parameter(names = "-help", help = true)
    boolean help = false;
}
