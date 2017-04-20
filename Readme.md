## Synopsis

RegioClust is a method that combines hierarchical clustering and linear regression to identify regions with similar relationships. 

## Usage

Usage: <main class> [options]
Options:
* -cluster
    Number of clusters
  -coords
    Index of coordinates
* -dep
    Index of dependent variables
  -help

  -incont
    Input contiguity matrix
* -indep
    Indices of independent variables
    Default: []
  -indist
    Input dist matrix
* -inshape
    Input ShapeFile
* -minobs
    Min. observations per cluster
  -outshape
    Output ShapeFile
  -threads
    Number of threads.
    Default: 1
      
## Notes

- Either the parameter "coords" or "indist" has to be set.
- Setting "coord" instead of "indist" is advisable if the distance matrix is very large.
- If you to not the parameter "incont", RegioClust tries to derive a contiguity matrix on its own (only works for polygonal data)
- You almost always want to define parameters "outshape" and "coords".  
- The paramter "minobs" should be reasonable large but in any case larger than the number of dependent variables plus one

## License

GNU GENERAL PUBLIC LICENSE Version 3