package org.openmrs.module.ugandaemrsync.server;

import com.google.common.base.Joiner;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.SQLQuery;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.type.StringType;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openmrs.api.context.Context;

import java.io.IOException;
import java.util.*;

import static org.openmrs.module.ugandaemrsync.server.SyncConstant.HEALTH_CENTER_SYNC_ID;
import static org.openmrs.module.ugandaemrsync.server.SyncConstant.LAST_SYNC_DATE;

/**
 * Created by lubwamasamuel on 07/11/2016.
 */
public class SyncDataRecord {
	
	UgandaEMRHttpURLConnection ugandaEMRHttpURLConnection = new UgandaEMRHttpURLConnection();
	
	Log log = LogFactory.getLog(getClass());
	
	public SyncDataRecord() {
	}
	
	public int syncRecords(List newSyncRecords) {
		int connectionStatus = 200;
		try {
			connectionStatus = ugandaEMRHttpURLConnection.getCheckConnection("google.com");
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		if (connectionStatus == SyncConstant.CONNECTION_SUCCESS) {
			int size = 0;
			Iterator it = newSyncRecords.iterator();
			UgandaEMRRecord ugandaEMRRecord = new UgandaEMRRecord();
			while (it.hasNext()) {
				Object row[] = (Object[]) it.next();
				try {
					Map response = syncData(String.valueOf(row[1]));
					Integer uuid = Integer.valueOf(String.valueOf(row[0]));
					String success = String.valueOf(response.get("response"));
					if (success.equalsIgnoreCase("successful")) {
						ugandaEMRRecord.updateSyncedRecord(uuid);
						size += 1;
					}
				}
				catch (Exception e) {
					System.out.println("Why no ");
					e.printStackTrace();
				}
			}
			return size;
		} else {
			log.info("Connection to internet was not Successful. Code: " + connectionStatus);
			return 0;
		}
	}
	
	/**
	 * @param syncRecord
	 * @return
	 * @throws Exception
	 */
	private Map syncData(String syncRecord) throws Exception {
		String contentTypeXML = SyncConstant.XML_CONTENT_TYPE;
		
		SyncGlobalProperties syncGlobalProperties = new SyncGlobalProperties();
		
		String serverIP = syncGlobalProperties.getGlobalProperty(SyncConstant.SERVER_IP);
		String serverProtocol = syncGlobalProperties.getGlobalProperty(SyncConstant.SERVER_PROTOCOL);
		String facilitySyncId = syncGlobalProperties.getGlobalProperty(HEALTH_CENTER_SYNC_ID);
		
		String url = serverProtocol + serverIP + "/api";
		
		UgandaEMRHttpURLConnection ugandaEMRHttpURLConnection = new UgandaEMRHttpURLConnection();
		
		try {
			UUID uuid = UUID.fromString(facilitySyncId);
			
			facilitySyncId = uuid.toString();
			
			return ugandaEMRHttpURLConnection.sendPostBy(contentTypeXML, syncRecord, facilitySyncId, url);
			
		}
		catch (IllegalArgumentException exception) {
			Map<String, String> map = new HashMap<String, String>();
			map.put("message", "No valid facility Id Found");
			return map;
		}
	}
	
	private List getDatabaseRecord(String query, String from, String to, int datesToBeReplaced, List<String> columns) {
		SyncGlobalProperties syncGlobalProperties = new SyncGlobalProperties();
		
		String facilityId = syncGlobalProperties.getGlobalProperty(HEALTH_CENTER_SYNC_ID);
		String lastSyncDate = syncGlobalProperties.getGlobalProperty(LAST_SYNC_DATE);
		
		String finalQuery;
		if (datesToBeReplaced == 1) {
			finalQuery = String.format(query, facilityId, lastSyncDate, from, to);
		} else if (datesToBeReplaced == 2) {
			finalQuery = String.format(query, facilityId, lastSyncDate, lastSyncDate, from, to);
		} else if (datesToBeReplaced == 3) {
			finalQuery = String.format(query, facilityId, lastSyncDate, lastSyncDate, lastSyncDate, from, to);
		} else {
			finalQuery = String.format(query, facilityId, from, to);
		}
		Session session = Context.getRegisteredComponent("sessionFactory", SessionFactory.class).getCurrentSession();
		SQLQuery sqlQuery = session.createSQLQuery(finalQuery);
		for (String column : columns) {
			sqlQuery.addScalar(column, StringType.INSTANCE);
		}
		return sqlQuery.list();
	}
	
	private List getDatabaseRecord(String query) {
		Session session = Context.getRegisteredComponent("sessionFactory", SessionFactory.class).getCurrentSession();
		SQLQuery sqlQuery = session.createSQLQuery(query);
		return sqlQuery.list();
	}
	
	public static Map<String, String> convertListOfMapsToJsonString(List list, List<String> columns) throws IOException {
		JSONArray result = new JSONArray();
		Iterator it = list.iterator();
		List<String> valuesToBeUpdated = new ArrayList<String>();
		Map<String, String> vals = new HashMap<String, String>();
		while (it.hasNext()) {
			Object rows[] = (Object[]) it.next();
			JSONObject row = new JSONObject();
			
			for (int i = 0; i < columns.size(); i++) {
				row.put(columns.get(i), rows[i]);
			}
			
			result.put(row);
		}
		vals.put("json", result.toString());
		vals.put("updateValues", Joiner.on(",").join(valuesToBeUpdated));
		return vals;
	}
	
	private void processData(Integer mySize, String url, String query, int datesToBeReplaced, List<String> columns,
	        Integer max) throws Exception {
		int startIndex = 0;
		boolean entireListNotProcessed = true;
		int offset = 0;
		while (entireListNotProcessed) {
			List records = getDatabaseRecord(query, String.valueOf(offset), String.valueOf(max), datesToBeReplaced, columns);
			Map<String, String> data = SyncDataRecord.convertListOfMapsToJsonString(records, columns);
			String json = data.get("json");
			ugandaEMRHttpURLConnection.sendPostBy(url, json);
			if (offset >= mySize || mySize <= max) {
				entireListNotProcessed = false;
			} else {
				startIndex = startIndex + 1;
			}
			offset = (startIndex * max);
		}
	}
	
	private Map<String, Integer> convertListToMap(List list) {
		Map<String, Integer> result = new HashMap<String, Integer>();
		Iterator it = list.iterator();
		while (it.hasNext()) {
			Object rows[] = (Object[]) it.next();
			result.put(String.valueOf(rows[1]), Integer.valueOf(String.valueOf(rows[0])));
		}
		return result;
	}
	
	public List syncData() {
		
		SyncGlobalProperties syncGlobalProperties = new SyncGlobalProperties();
		
		String lastSyncDate = syncGlobalProperties.getGlobalProperty(SyncConstant.LAST_SYNC_DATE);
		String totalsQuery = SyncConstant.TABLES_TOTAL_QUERY;
		
		List totals = getDatabaseRecord(totalsQuery.replaceAll("lastSync", lastSyncDate));
		
		Integer max = Integer.valueOf(syncGlobalProperties.getGlobalProperty(SyncConstant.MAX_NUMBER_OF_ROWS));
		
		Map<String, Integer> numbers = convertListToMap(totals);
		
		Integer encounters = numbers.get("encounter");
		Integer obs = numbers.get("obs");
		Integer persons = numbers.get("person");
		Integer person_names = numbers.get("person_name");
		Integer person_addresses = numbers.get("person_address");
		Integer person_attributes = numbers.get("person_attribute");
		Integer patients = numbers.get("patient");
		Integer patient_identifiers = numbers.get("patient_identifier");
		Integer visits = numbers.get("visit");
		Integer encounter_providers = numbers.get("encounter_provider");
		Integer providers = numbers.get("provider");
		Integer encounter_roles = numbers.get("encounter_role");
		
		Integer fingerprints = numbers.get("fingerprint");
		
		try {
			processData(encounters, "api/encounters", SyncConstant.ENCOUNTER_QUERY, 3, SyncConstant.ENCOUNTER_COLUMNS, max);
			processData(obs, "api/obs", SyncConstant.OBS_QUERY, 2, SyncConstant.OBS_COLUMNS, max);
			processData(persons, "api/persons", SyncConstant.PATIENT_QUERY, 3, SyncConstant.PATIENT_COLUMNS, max);
			processData(person_names, "api/person_names", SyncConstant.PERSON_NAME_QUERY, 3,
			    SyncConstant.PERSON_NAME_COLUMNS, max);
			processData(person_addresses, "api/person_addresses", SyncConstant.PERSON_ADDRESS_QUERY, 3,
			    SyncConstant.PERSON_ADDRESS_COLUMNS, max);
			processData(person_attributes, "api/person_attributes", SyncConstant.PERSON_ATTRIBUTE_QUERY, 3,
			    SyncConstant.PERSON_ATTRIBUTE_COLUMNS, max);
			processData(patients, "api/patients", SyncConstant.PATIENT_QUERY, 3, SyncConstant.PATIENT_COLUMNS, max);
			processData(patient_identifiers, "api/patient_identifiers", SyncConstant.PATIENT_IDENTIFIER_QUERY, 3,
			    SyncConstant.PATIENT_IDENTIFIER_COLUMNS, max);
			processData(visits, "api/visits", SyncConstant.VISIT_QUERY, 3, SyncConstant.VISIT_COLUMNS, max);
			processData(encounter_providers, "api/encounter_providers", SyncConstant.ENCOUNTER_PROVIDER_QUERY, 3,
			    SyncConstant.ENCOUNTER_PROVIDER_COLUMNS, max);
			processData(providers, "api/providers", SyncConstant.PROVIDER_QUERY, 3, SyncConstant.PROVIDER_COLUMNS, max);
			processData(fingerprints, "api/fingerprints", SyncConstant.FINGERPRINT_QUERY, 1,
			    SyncConstant.FINGERPRINT_COLUMNS, max);
			
			Date now = new Date();
			
			String newSyncDate = SyncConstant.DEFAULT_DATE_FORMAT.format(now);
			
			syncGlobalProperties.setGlobalProperty(SyncConstant.LAST_SYNC_DATE, newSyncDate);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		
		return totals;
	}
}
