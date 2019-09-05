package org.openmrs.module.ugandaemrsync.tasks;

import org.apache.http.HttpStatus;
import org.openmrs.GlobalProperty;
import org.openmrs.api.context.Context;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.report.ReportData;
import org.openmrs.module.reporting.report.ReportRequest;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.module.reporting.report.renderer.RenderingMode;
import org.openmrs.module.reporting.report.util.ReportUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.ugandaemrsync.server.SyncGlobalProperties;
import org.openmrs.module.ugandaemrsync.server.UgandaEMRHttpURLConnection;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.openmrs.util.OpenmrsUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.DataInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static org.openmrs.module.ugandaemrsync.UgandaEMRSyncConfig.*;
import static org.openmrs.module.ugandaemrsync.server.SyncConstant.SERVER_PROTOCOL;

/**
 * Posts recency data to the central server
 */

@Component
public class SendRecencyDataToCentralServerTask extends AbstractTask {
	
	protected Log log = LogFactory.getLog(getClass());
	
	UgandaEMRHttpURLConnection ugandaEMRHttpURLConnection = new UgandaEMRHttpURLConnection();

	@Autowired
	@Qualifier("reportingReportDefinitionService")
	protected ReportDefinitionService reportingReportDefinitionService;
	
	@Override
	public void execute() {
		Date todayDate = new Date();
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		
		SyncGlobalProperties syncGlobalProperties = new SyncGlobalProperties();
		String recencyServerUrlEndPoint = syncGlobalProperties.getGlobalProperty(RECENCY_SERVER_URL);
		String recencyDomainUrl = recencyServerUrlEndPoint.substring(
		    recencyServerUrlEndPoint.indexOf(syncGlobalProperties.getGlobalProperty(SERVER_PROTOCOL)),
		    recencyServerUrlEndPoint.indexOf(syncGlobalProperties.getGlobalProperty(RECENCY_SUBDIRECTORY)));
		
		GlobalProperty gp = Context.getAdministrationService().getGlobalPropertyObject(
		    RECENCY_TASK_LAST_SUCCESSFUL_SUBMISSION_DATE);
		if (gp.getPropertyValue().equals(dateFormat.format(todayDate))) {
			log.info("Last successful submission was on {global property value} so this task will not run again today. If you need to send data, run the task manually.t"
			        + System.lineSeparator());
			return;
		}
		
		log.info("Sending recency data to central server ");
		
		//Check internet connectivity
		if (!ugandaEMRHttpURLConnection.isConnectionAvailable()) {
			return;
		}
		
		//Check destination server availability
		if (!ugandaEMRHttpURLConnection.isServerAvailable(recencyDomainUrl)) {
			return;
		}
		
		String bodyText = getRecencyDataExport();
		int httpResponseStatus = ugandaEMRHttpURLConnection.httpPost(recencyServerUrlEndPoint, bodyText);
		if (httpResponseStatus == HttpStatus.SC_OK) {
			ReportUtil.updateGlobalProperty(RECENCY_TASK_LAST_SUCCESSFUL_SUBMISSION_DATE, dateFormat.format(todayDate));
			log.info("Recency data has been sent to central server");
		} else {
			log.info(httpResponseStatus);
			ugandaEMRHttpURLConnection.setAlertForAllUsers("Http request has returned a response status: "
			        + httpResponseStatus);
		}
	}
	
	private String getRecencyDataExport() {
		ReportDefinitionService reportDefinitionService = Context.getService(ReportDefinitionService.class);
		String strOutput = new String();
		
		try {
			ReportDefinition rd = reportDefinitionService.getDefinitionByUuid(RECENCY_DEFININATION_UUID);
			if (rd == null) {
				throw new IllegalArgumentException("unable to find Recency Data Export report with uuid "
				        + RECENCY_DEFININATION_UUID);
			}
			
			RenderingMode renderingMode = new RenderingMode(REPORT_RENDERING_MODE);
			if (!renderingMode.getRenderer().canRender(rd)) {
				throw new IllegalArgumentException("Unable to render Recency Data Export with " + REPORT_RENDERING_MODE);
			}
			
			EvaluationContext context = new EvaluationContext();
			ReportData reportData = reportDefinitionService.evaluate(rd, context);
			ReportRequest reportRequest = new ReportRequest();
			reportRequest.setReportDefinition(new Mapped<ReportDefinition>(rd, context.getParameterValues()));
			reportRequest.setRenderingMode(renderingMode);
			File file = new File(OpenmrsUtil.getApplicationDataDirectory() + RECENCY_CSV_FILE_NAME);
			FileOutputStream fileOutputStream = new FileOutputStream(file);
			renderingMode.getRenderer().render(reportData, renderingMode.getArgument(), fileOutputStream);
			
			strOutput = this.readOutputFile(strOutput);
		}
		catch (Exception e) {
			log.info(e.toString());
		}
		
		return strOutput;
	}
	
	/*
	Method: readOutputFile
	Pre condition: empty strOutput initialized
	Description:
		Read the recency exported report file in csv
		Create a string and prefix the dhis2_orgunit_uuid
		and encounter_uuid columns to the final output
	Post condition: strOutput assigned with csv file data prefixed with two additional columns
	* */
	
	public String readOutputFile(String strOutput) throws Exception {
		SyncGlobalProperties syncGlobalProperties = new SyncGlobalProperties();
		FileInputStream fstreamItem = new FileInputStream(OpenmrsUtil.getApplicationDataDirectory() + RECENCY_CSV_FILE_NAME);
		DataInputStream inItem = new DataInputStream(fstreamItem);
		BufferedReader brItem = new BufferedReader(new InputStreamReader(inItem));
		String phraseItem;
		
		if (!(phraseItem = brItem.readLine()).isEmpty()) {
			strOutput = strOutput + "\"dhis2_orgunit_uuid\"," + "\"encounter_uuid\"," + phraseItem + System.lineSeparator();
			while ((phraseItem = brItem.readLine()) != null) {
				strOutput = strOutput + "\"" + syncGlobalProperties.getGlobalProperty(DHIS2_ORGANIZATION_UUID) + "\",\"\","
				        + phraseItem + System.lineSeparator();
			}
		}
		
		fstreamItem.close();
		
		return strOutput;
	}
}
