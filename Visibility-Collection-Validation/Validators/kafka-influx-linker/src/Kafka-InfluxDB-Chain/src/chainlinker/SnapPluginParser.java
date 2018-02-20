package chainlinker;
import java.util.Iterator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/*
 * Not confirmed about what happends when InfluxDB connection failed.
 * No reference or documents are found. Need to be reinforced.
 */

public abstract class SnapPluginParser {
	private static final Logger logger = LogManager.getLogger(SnapPluginParser.class);
	ConfigLoader.InfluxDBConfig influxDBConf;

	public SnapPluginParser() {
		influxDBConf = ConfigLoader.getInstance().getInfluxDBConfig();
	}

	// Gets each data JSONObject and return it as an Point to be fed into InfluxDB.
	abstract public Point parse(JSONObject dataObj) throws NullPointerException, ClassNotFoundException;

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
}