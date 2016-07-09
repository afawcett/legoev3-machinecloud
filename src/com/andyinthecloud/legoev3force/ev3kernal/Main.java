package com.andyinthecloud.legoev3force.ev3kernal;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.ajax.JSON;

import com.sforce.soap.partner.Connector;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectorConfig;

import lejos.hardware.lcd.LCD;
import lejos.utility.Delay;

/**
 * Main kernal entry point for connection to Salesforce and routing commands to the robot
 */
public class Main {
	
	// Connected App Details 
	private static final String CLIENT_ID = "3MVG9A_f29uWoVQsrQF1iUPf_aj8C7yTUK1k8IzmaRX5kvgEeNl46.eOenf.x8ITrOjBJt3HwON8jrSOyOHY9";
	private static final String CLIENT_SECRET = "2337075232379333111";

	// Local config file for storing refresh token, robot id and instance url
	private static String CONFIG_FILE = "ev3force.properites";
	
	/**
	 * Connects to Saleforce by reading pre determined oAuth token from properties file
	 *    or starts a pairing process with the Heroku Canvas App, then listens for commands
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args)
		throws Exception
	{
		// Reconnect or begin pairing process?
		RobotConnection robotConnection = null;
		File configFile = new File(CONFIG_FILE);
		if(configFile.exists())
			robotConnection = loadConnection(configFile);
		else
			robotConnection = pairWithSalesforce();
		
        // Display Robot name
        LCD.clear();
        LCD.drawString("Querying Robot", 0, 3);
        PartnerConnection partnerConnection = Connector.newConnection(robotConnection.connectorConfig);
        QueryResult result = partnerConnection.query("select Name, mcloud__Paired__c from mcloud__Robot__c where Id = '"+robotConnection.robotId+"'");
        SObject[] robots = result.getRecords();
        
        // If the robot record no longer exists or has become unpaired repair with it
        if(robots.length==0 || ((String)robots[0].getField("mcloud__Paired__c")).equals("false"))
        {
        	// Repair with the robot record
        	robotConnection = pairWithSalesforce();
        	if(robots.length==0) // Query the new robot record?
        		robots = partnerConnection.query("select Name from mcloud__Robot__c where Id = '"+robotConnection.robotId+"'").getRecords();
        }
        LCD.clear();
        LCD.drawString("Welcome " + robots[0].getField("Name") + "!", 0, 3);
        Delay.msDelay(5000);
        LCD.clear();
        
        // Start listening
        EV3ForceCommand.start(
        		robotConnection.connectorConfig.getSessionId(), 
        		robotConnection.connectorConfig.getServiceEndpoint(), 
        		partnerConnection, 
        		robotConnection.robotId);
	}
	
	/**
	 * Starts a pairing process with Heroku Canvas app
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	private static RobotConnection pairWithSalesforce()
		throws Exception
	{
		LCD.clear();
		LCD.drawString("Getting Pin", 0, 3);
		
		// Http commons with pairing service
	    HttpClient httpClient = new HttpClient();
	    httpClient.setConnectTimeout(20 * 1000); // Connection timeout
	    httpClient.setTimeout(120 * 1000); // Read timeout
	    httpClient.start();
	    
	    // Get a pin number
	    ContentExchange getPin = new ContentExchange();
	    getPin.setMethod("GET");
	    getPin.setURL("https://ev3forcepairing.herokuapp.com/service/pin");
	    httpClient.send(getPin);
	    getPin.waitForDone();	
	    Map<String, Object> parsed = (Map<String, Object>) JSON.parse(getPin.getResponseContent());
	    
	    // Display pin number to enter into Salesforce
	    String pin = (String) parsed.get("pin");
	    LCD.clear();
		LCD.drawString("Pin " + pin, 0, 3);

		// Wait for refresh token for the given pin number
		Integer waitCount = 0;
		String refreshToken = null;
		String robotId = null;
		while(true)
		{
		    getPin = new ContentExchange();
		    getPin.setMethod("GET");
		    getPin.setURL("https://ev3forcepairing.herokuapp.com/service/pin?pin=" + pin);
		    httpClient.send(getPin);
		    getPin.waitForDone();
		    parsed = (Map<String, Object>) JSON.parse(getPin.getResponseContent());
		    refreshToken = (String) parsed.get("refreshToken");
			robotId = (String) parsed.get("robotId");
			if(refreshToken!=null)
				break;
			LCD.drawString("Waiting " + waitCount++, 0, 4);
			Delay.msDelay(1000);
		}
				
		// Save refresh token and robot id for next startup
		saveConfiguration(refreshToken, robotId);
				
		// Setup connector config
		ConnectorConfig config = new ConnectorConfig();
		config.setSessionId(refreshToken);
		config.setManualLogin(true);
		RobotConnection sfConnection = new RobotConnection();
		sfConnection.connectorConfig = config;
		sfConnection.robotId = robotId;
		resolveAccessToken(refreshToken, sfConnection);
		
		// Update Robot to show its been paired
		LCD.clear();
		LCD.drawString("Updating Robot", 0, 3);		
		SObject robotRecord = new SObject();
		robotRecord.setId(robotId);
		robotRecord.setType("mcloud__Robot__c");
		robotRecord.setField("mcloud__Paired__c", Boolean.TRUE);
		PartnerConnection partnerConnection = Connector.newConnection(sfConnection.connectorConfig);
		partnerConnection.update(new SObject[] { robotRecord } );
		
		return sfConnection;
	}
	
	/**
	 * Loads connection details obtained from a previous pairing process
	 * @param configFile
	 * @return
	 * @throws Exception
	 */
	private static RobotConnection loadConnection(File configFile)
		throws Exception
	{
		// Load refresh token and robot Id
		Properties configProps = new Properties();
		configProps.load(new FileReader(configFile));
		ConnectorConfig config = new ConnectorConfig();
		config.setManualLogin(true);
		RobotConnection robotConnection = new RobotConnection();
		robotConnection.connectorConfig = config;
		robotConnection.robotId = configProps.getProperty("RobotId");
		
		// Get access token
		String refreshToken = configProps.getProperty("RefreshToken");
		resolveAccessToken(refreshToken, robotConnection);
		return robotConnection;
	}
	
	/**
	 * Stores the connection details in ev3force.properites in the current folder
	 * @param robotConnection
	 * @throws Exception
	 */
	private static void saveConfiguration(String refreshToken, String robotId)
		throws Exception
	{
		Properties configProps = new Properties();
		configProps.put("RefreshToken", refreshToken);
		configProps.put("RobotId", robotId);
		configProps.store(new FileWriter(CONFIG_FILE), null);
	}

	/**
	 * Simple POJO for passing around connection details
	 */
	public static class RobotConnection
	{
		public String robotId;
		public ConnectorConfig connectorConfig;
	}
		
	/** 
	 * Helper method to obtain an access token via oAuth
	 * @param refreshToken
	 * @return
	 */
	private static void resolveAccessToken(String refreshToken, RobotConnection robotConnection)
		throws Exception
	{
		LCD.clear();
		LCD.drawString("Salesforce Login", 0, 3);				
	    HttpClient httpClient = new HttpClient();
	    httpClient.setConnectTimeout(20 * 1000); // Connection timeout
	    httpClient.setTimeout(120 * 1000); // Read timeout
	    httpClient.start();		
	    ContentExchange refershToken = new ContentExchange();
	    refershToken.setMethod("POST");
	    refershToken.setURL("https://login.salesforce.com/services/oauth2/token");
	    String formData =
	    		"grant_type=refresh_token" + "&" + 
	    		"refresh_token=" + refreshToken + "&" +
	    		"client_id=" + CLIENT_ID + "&" +
	    		"client_secret=" + CLIENT_SECRET;
	    refershToken.setRequestContent( new ByteArrayBuffer(formData));
	    refershToken.setRequestContentType( "application/x-www-form-urlencoded; charset=UTF-8" );
	    httpClient.send(refershToken);
	    refershToken.waitForDone();
	    String jsonResponse = refershToken.getResponseContent();
	    @SuppressWarnings("unchecked")
		Map<String, Object> parsed = (Map<String, Object>) JSON.parse(jsonResponse); 
	    String accessToken = (String) parsed.get("access_token");
	    String instanceUrl = (String) parsed.get("instance_url");
	    robotConnection.connectorConfig.setSessionId(accessToken);
	    robotConnection.connectorConfig.setServiceEndpoint(instanceUrl+"/services/Soap/u/29.0");
	}
}