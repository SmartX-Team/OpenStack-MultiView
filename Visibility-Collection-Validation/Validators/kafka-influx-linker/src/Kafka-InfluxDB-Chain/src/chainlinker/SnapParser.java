package chainlinker;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/*
 * Not confirmed about what happens when InfluxDB connection failed.
 * No reference or documents are found. Need to be reinforced.
 */

public class SnapParser {
	private static final Logger logger = LogManager.getLogger(SnapParser.class);
	ConfigLoader.InfluxDBConfig influxDBConf;

	protected HashMap<String, SnapPluginParser> parserMap = new HashMap<>();
	protected Set<String> parserKeySet;

	public SnapParser() {
		influxDBConf = ConfigLoader.getInstance().getInfluxDBConfig();
		// TODO: Must make this reflective.
		parserMap.put("snap-plugin-collector-psutil", new SnapPSUtilParser());
		parserMap.put("snap-plugin-collector-cpu", new SnapCPUParser());
		
		parserKeySet = parserMap.keySet();
		for (String parserKey : parserKeySet) {
//			parserMap.get(parserKey).loadMap(typeMap);
			parserMap.get(parserKey).loadParserMap(parserMap);
		}
	}

	public void processMessage(JSONArray msgValue) {
		// Creating InfluxDB instance to handle InfluxDB connection
		logger.trace("Opening a connection to InfluxDB server " + influxDBConf.getAddress() + " with ID " + influxDBConf.getID() + ".");
		InfluxDB influxDB = InfluxDBFactory.connect(influxDBConf.getAddress(), influxDBConf.getID(), influxDBConf.getPassword());
		
		String dbName = influxDBConf.getDBName();
		influxDB.createDatabase(dbName); // This will be ignored by InfluxDB if already the DB exists

		// Only 1 query are sent to the DB per 1 Kafka message by batching points.
		logger.trace("Setting up a BatchPoints...");
		BatchPoints batchPoints = BatchPoints
				.database(dbName)
				.retentionPolicy(influxDBConf.getRetentionPolicy())
				.consistency(influxDBConf.getConsistencyLevel())
				.build();				

		@SuppressWarnings("unchecked")
		Iterator<JSONObject> msg_iterator = msgValue.iterator();
		logger.trace("Processing a message with " + msgValue.size() + " elements.");
		int count = 1;
		while (msg_iterator.hasNext()) { // For each data element
			logger.trace("Processing the element #" + count + ".");
			JSONObject dataObj = msg_iterator.next();
			try {
				batchPoints.point(parse(dataObj));
			} catch (NullPointerException e) {
				e.printStackTrace();						
			} catch (ClassNotFoundException e) {
				logger.error("Failed to find data type info for given data with field name '" + e.getMessage() + "'", e);
			}
			count++;
		}

		influxDB.write(batchPoints);
	}
	
	protected Object getSafe(JSONObject map, String key) throws NullPointerException {
		Object value = map.get(key);
		if (value == null) throw new NullPointerException();		
		return value;
	}	
	
	// Gets each data JSONObject and return it as an Point to be fed into InfluxDB.
	public Point parse(JSONObject dataObj) throws NullPointerException, ClassNotFoundException {
		logger.trace("Parsing...");
		
		// name, source, unit, time, value

		// Extraction of name. String name will be the measurement in influxDB.
		JSONArray namespace = (JSONArray)getSafe(dataObj, "namespace");
		StringJoiner nameSJ = new StringJoiner("/", "", "");
		@SuppressWarnings("unchecked")
		Iterator<JSONObject> namespace_iterator = namespace.iterator();
		while (namespace_iterator.hasNext()) {
			nameSJ.add(getSafe(namespace_iterator.next(),"Value").toString());
		}
		String name = nameSJ.toString();

		// Extraction of source.
		String source = (String)((JSONObject)getSafe(dataObj, "tags")).get("plugin_running_on");

		// Extraction of unit.
		String unit = (String)getSafe(dataObj, "Unit_");

		// Extraction of time.
		String timestamp = (String)getSafe(dataObj, "timestamp");
		long time = RFC3339toNSConvertor.ToNS(timestamp);

		logger.trace("Processed a data with time " + timestamp + " = " + time + " ns.");

		org.influxdb.dto.Point.Builder builder = Point.measurement(name)
				.time(time, TimeUnit.NANOSECONDS)
				.tag("source", source);
		
		if (unit.length() > 0) {
			// This prevents 0 length String causing InfluxDB query parsing error.
			// If any other values make this error again, then more improvements may be required.
			builder.tag("unit", unit);
		}

		// TODO: Maybe this can be improved again...
		// 1st pass : Fast searching for static names via HashMap
		SnapPluginParser parser = parserMap.get(name);
		if (parser == null) {
			// 2nd pass : Querying for parameterized names (ex. snap-plugin-collector-cpu)
			// Asking all registered parsers whether they can handle given data type
			for (String parseName : parserKeySet) {
				SnapPluginParser parserIter = parserMap.get(parseName); 
				if (parserIter.isParsible(name)) {
					parser = parserIter;
					break;					
				}
			}
		}
		
		if (parser == null) {
			logger.error("No matching parser found with data type '" + name + "'.");
			throw new ClassNotFoundException (name);
		}
		
		try {
			parser.addField(builder, name, getSafe(dataObj, "data"));
		} catch (ClassNotFoundException e) {
			logger.error("A parser '" + parser.getClass().getName() + "' corresponding to data type '" + name + "', but it does not know how to handle it.");
			throw new ClassNotFoundException (name);
		}

		logger.trace("Parsing complete...");
		return builder.build();
	}	
}