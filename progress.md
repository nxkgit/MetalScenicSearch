We have started turning METAL graph data into working code so the project can move from reading documentation to running experiments on real road networks. The first step was to read the standard graph file format and keep the network in memory in a way later work can build on.
-- Milestone 1
We wanted to start on milestone 1 by understanding how the data is laid out and loading it into something we can work with. The items below are what we put in place toward that.
- A Java component reads the text graph files: file header, how many points and connections to expect, one line per intersection-style point (name and coordinates), then one line per road segment (which two points it joins, road name, and optional extra coordinates along the segment).
- The program keeps that information organized as points, connections between points, and the full network, including which road segments meet at each point. Treating each segment as its own connection matches how we plan to forbid reusing the same stretch of road.
- A short demo program loads the New York region file and prints summary numbers so we can confirm the read looks reasonable (counts, a quick connectivity check, and a sample point and segment).

We can now go from a graph file on disk to a loaded network in memory, which sets us up for milestone 2 when we start looking for long routes between chosen locations.

-- Milestone 2
The goal of this milestone was to get a basic version of the algorithm running. The following is what we changed:

- We removed all the folder organization setup for maven because we decided not to overcomplicate things by using it (for now)
- We created the first version of the algorithm in ScenicSearch.java. It used depth first search along with the haversine formula which we found online for computing distances. The algorithm is currently a very simple DFS algorithm but this created problems which we will have to address in the future. Right now, the program fails on the larger .tmg graphs since it is so demanding to search for long routes. It works on the small siena2-area.tmg but we are working on how to eliminate paths earlier in order to make it less of a workload.
- We added a simple run.sh to see it work on the small siena2-area.tmg dataset.