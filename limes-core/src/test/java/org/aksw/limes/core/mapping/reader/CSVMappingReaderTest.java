package org.aksw.limes.core.mapping.reader;

<<<<<<< HEAD
import static org.junit.Assert.assertTrue;

=======
import static org.junit.Assert.*;

import org.aksw.limes.core.evaluation.evaluationDataLoader.DataSetChooser;
import org.aksw.limes.core.evaluation.evaluationDataLoader.DataSetChooser.DataSets;
import org.aksw.limes.core.evaluation.evaluationDataLoader.EvaluationData;
import org.aksw.limes.core.io.cache.Cache;
>>>>>>> af599e57e3a02f290f814a16cb8681b380b304af
import org.aksw.limes.core.io.mapping.AMapping;
import org.aksw.limes.core.io.mapping.MappingFactory;
import org.aksw.limes.core.io.mapping.reader.CSVMappingReader;
import org.junit.Test;

public class CSVMappingReaderTest {

    @Test
    public void csvMappingThreeColTester() {
	AMapping testMap = MappingFactory.createDefaultMapping();

	testMap.add("http://linkedgeodata.org/triplify/node2806760713", "http://linkedgeodata.org/triplify/node2478449224", 1.0d);
	testMap.add("http://linkedgeodata.org/triplify/node2806760713", "http://linkedgeodata.org/triplify/node1387111642", 1.0d);
	testMap.add("http://linkedgeodata.org/triplify/node2806760713", "http://linkedgeodata.org/triplify/node2406512815", 1.0d);
	testMap.setPredicate("http://linkedgeodata.org/ontology/near");

	String file = System.getProperty("user.dir") + "/resources/mapping-3col-test.csv";
	CSVMappingReader r = new CSVMappingReader(file, ",");
	AMapping readMap = r.read();

	assertTrue(readMap.equals(testMap));
    }

    @Test
    public void csvMappingThreeColWithSimilarityTester() {
	AMapping testMap = MappingFactory.createDefaultMapping();
	testMap.add("http://dbpedia.org/resource/Berlin", "http://linkedgeodata.org/triplify/node240109189", 0.999d);

	String file = System.getProperty("user.dir") + "/resources/mapping-3col-sim-test.csv";
	CSVMappingReader r = new CSVMappingReader(file, ",");
	AMapping readMap = r.read();

	assertTrue(readMap.equals(testMap));
    }

    @Test
    public void csvMappingTwoColTester() {
	AMapping testMap = MappingFactory.createDefaultMapping();
	testMap.add("http://dbpedia.org/resource/Berlin", "http://linkedgeodata.org/triplify/node240109189", 1d);

	String file = System.getProperty("user.dir") + "/resources/mapping-2col-test.csv";
	CSVMappingReader r = new CSVMappingReader(file, ",");
	AMapping readMap = r.read();

	assertTrue(readMap.equals(testMap));
    }

    @Test
    public void csvMappingTestBugFix() {
	final DataSets[] datasets = DataSetChooser.DataSets.values();
	final String[] datasetsList = new String[datasets.length];
	for (int i = 0; i < datasets.length; i++) {
	    datasetsList[i] = datasets[i].name();
	}
	EvaluationData evalData = null;
	try {
	    for (String ds : datasetsList) {
		evalData = DataSetChooser.getData(ds);
		Cache source = evalData.getSourceCache();
		Cache target = evalData.getTargetCache();
		AMapping missing = MappingFactory.createDefaultMapping();
		evalData.getReferenceMapping().getMap().forEach((sourceURI, map2) -> {
		    map2.forEach((targetURI, value) -> {
			if (source.getInstance(sourceURI) == null || target.getInstance(targetURI) == null) {
			    missing.add(sourceURI, targetURI, 1.0);
			}

		    });
		});
		assertEquals(0, missing.size());
	    }
	} catch (Exception e) {
	    System.out.println(e.getMessage());
	}
    }

}
