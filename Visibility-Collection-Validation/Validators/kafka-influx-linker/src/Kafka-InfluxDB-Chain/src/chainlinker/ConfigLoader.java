package chainlinker;
import java.io.FileReader;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.StringJoiner;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.influxdb.InfluxDB.ConsistencyLevel;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class ConfigLoader {
	private static final Logger logger = LogManager.getLogger(ConfigLoader.class);
	
	// Singleton part for this class as this class does not need to exist in multitude.
	private static ConfigLoader instance = makeInstance();
	private static ConfigLoader makeInstance() {
		logger.debug("Loading config file...");
		
		// Loading configurations from config file. Any error during loading will be filtered here.
		ConfigLoader conf;
		try {
			// TODO: Make this to get config file's path, not by hard-coded string inside of it.
			conf = new ConfigLoader();
		} catch (IOException e1) {
			logger.fatal("Failed to open config file. Is it in proper place? The file's full path must be \"~/kafka/.Kafka-InfluxDB-Chain\".", e1);
			conf = null;
		} catch (ParseException e1) {
			logger.fatal("The config file is found, but JSONParser failed to parse it. Is it in proper form?", e1);
			conf = null;
		} catch (NullPointerException e1) {
			// Error message is embedded in the throwable.
			logger.fatal(e1.getMessage(), e1);
			conf = null;   
		}
		return conf;
	}
	public static ConfigLoader getInstance () {
		return instance;
	}		

	private KafkaConfig kafka;
	// Nested class to store and provice read-only access to Kafka-related setting values.
	public class KafkaConfig {
		private String bootstrap_servers;
		private String topic_name;
		private String group_id;
		private String auto_commit;
		private String auto_commit_interval_ms;
		private String session_timeout_ms;
		private String key_deserializer;
		private String value_deserializer;

		public String getBootstrapServers() {
			return bootstrap_servers;
		}
		public String getTopicName() {
			return topic_name;
		}
		public String getGroupID() {
			return group_id;
		}
		public String getAutoCommit() {
			return auto_commit;
		}
		public String getAutoCommitIntervalMS() {
			return auto_commit_interval_ms;
		}
		public String getSessionTimeoutMS() {
			return session_timeout_ms;
		}
		public String getKeyDeserializer() {
			return key_deserializer;
		}
		public String getValueDeserializer() {
			return value_deserializer;
		}
	}

	private InfluxDBConfig influxdb;
	// Nested class to store and provice read-only access to InfluxDB-related setting values.
	public class InfluxDBConfig {
		private String address;
		private String id;
		private String password;
		private String db_name;
		private String retention_policy;
		private ConsistencyLevel consistency_level;

		public String getAddress() {
			return address;
		}
		public String getID() {
			return id;
		}
		public String getPassword() {
			return password;
		}
		public String getDBName() {
			return db_name;
		}
		public String getRetentionPolicy() {
			return retention_policy;
		}
		public ConsistencyLevel getConsistencyLevel() {
			return consistency_level;
		}
	}

	private ConfigLoader() throws IOException, ParseException, NullPointerException {
		load();
	}

	// This is to make text of the object in JSON that error occurred for error message.
	LinkedList<String> hierachy_header = new LinkedList<>();

	/*
	 * Reading the config file. The file must be in JSON style.
	 * 
	 * Config file full path : ~/kafka/.Kafka-InfluxDB-Chain
	 */
	protected void load() throws IOException, ParseException, NullPointerException {

		// Loading entire JSON file
		JSONObject config_all_json;
		JSONParser parser = new JSONParser();
		Object obj = parser.parse(new FileReader(
				System.getProperty( "user.home" ) + "/kafka-influx-linker/.kafka-influx-linker"));
		config_all_json = (JSONObject) obj;

		// Loading part for Kafka configuration
		JSONObject config_kafka_json;
		config_kafka_json = (JSONObject)getValue(config_all_json, "kafka");

		kafka = new KafkaConfig();
		hierachy_header.add("kafka");
		kafka.topic_name = (String)getValue(config_kafka_json, "topic");

		@SuppressWarnings("unchecked")
		Iterator<String> iterator = ((JSONArray)getValue(config_kafka_json, "bootstrap.servers")).iterator();
		StringJoiner brokerSJ = new StringJoiner(";", "", "");
		while (iterator.hasNext()) {
			brokerSJ.add(iterator.next());
		}
		kafka.bootstrap_servers = brokerSJ.toString();

		kafka.group_id = (String)getValue(config_kafka_json, "group.id");
		kafka.auto_commit = (String)getValue(config_kafka_json, "enable.auto.commit");
		kafka.auto_commit_interval_ms = ((Long)getValue(config_kafka_json, "auto.commit.interval.ms")).toString();
		kafka.session_timeout_ms = ((Long)getValue(config_kafka_json, "session.timeout.ms")).toString();
		kafka.key_deserializer = (String)getValue(config_kafka_json, "key.deserializer");
		kafka.value_deserializer = (String)getValue(config_kafka_json, "value.deserializer");

		hierachy_header.removeLast();

		// Loading part for InfluxDB
		JSONObject config_influx_json;
		hierachy_header.add("influxdb");
		config_influx_json = (JSONObject)getValue(config_all_json, "influxdb");

		influxdb = new InfluxDBConfig();
		influxdb.address = (String)getValue(config_influx_json, "address");
		influxdb.id = (String)getValue(config_influx_json, "id");
		influxdb.password = (String)getValue(config_influx_json, "password");
		influxdb.db_name = (String)getValue(config_influx_json, "db_name");
		influxdb.retention_policy = (String)getValue(config_influx_json, "retention_policy");
		influxdb.consistency_level = getConsistencyLevel(config_influx_json);

		hierachy_header.removeLast();
	}

	KafkaConfig getKafkaConfig() {
		return kafka;
	}
	InfluxDBConfig getInfluxDBConfig() {
		return influxdb;
	}

	/*
	 * This method is intended for checking validity of the given setting.
	 * 
	 * Currently, this checks only whether required value exists.
	 * TODO: Make this also check each value's syntax.
	 */
	protected Object getValue(JSONObject json, String key) throws NullPointerException {
		Object value = json.get(key);
		if (value == null) throw new NullPointerException ("Config file's '" + String.join(":", hierachy_header) + ":" + key + "' is missing.");
		return value;
	}

	// InfluxDB's ConsistencyLevel requires a different approach as it is not a String.
	protected ConsistencyLevel getConsistencyLevel(JSONObject json) throws ParseException {
		String lvl_str = ((String)getValue(json, "consistency_level")).toLowerCase();
		switch (lvl_str) {
		case "all" :
			return ConsistencyLevel.ALL;
		default:
			throw new ParseException(0, "Failed to parse '" + String.join(":", hierachy_header) + ":consistency_level.");
		}
	}
}
