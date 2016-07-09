package com.andyinthecloud.legoev3force.ev3kernal;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import lejos.hardware.Button;
import lejos.hardware.lcd.LCD;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ajax.JSON;

import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.sobject.SObject;

/** 
 * Demonstration Java program to run on the Lego Mindstorms EV3 receiving commands
 *   via Salesforce using the Streaming API and records being inserted into a Custom Object
 */
public class EV3ForceCommand {
        
    /**
     * Starts listening for new commands and waits for a key press on the Lego brick to exit
     * @param sessionId
     * @param serverUrl
     * @throws Exception
     */
    public static void start(String sessionId, String serverUrl, final PartnerConnection partnerConnection, String robotId)
    	throws Exception
    {        
        // Subscribe to the Command push topic
        LCD.clear();
        LCD.drawString("Stream connect....", 0, 3);        
        final BayeuxClient client = makeStreamingAPIConnection(sessionId, serverUrl);
        LCD.clear();
        LCD.drawString("Waiting....", 0, 3);

        // Configure robot
        EV3DirectCommand.init();
        
        // Subscribe to the 'commands' topic to listen for new Command__c records
        client.getChannel("/topic/robot"+robotId).subscribe(new ClientSessionChannel.MessageListener() 
            {
                @SuppressWarnings("unchecked")
                public void onMessage(ClientSessionChannel channel, Message message) 
                {
                    try
                    {
                        HashMap<String, Object> data = (HashMap<String, Object>) JSON.parse(message.toString());
                        HashMap<String, Object> record = (HashMap<String, Object>) data.get("data");
                        HashMap<String, Object> sobject = (HashMap<String, Object>) record.get("sobject");
                        String commandName = (String) sobject.get("Name");
                        String command = (String) sobject.get("mcloud__Command__c");
                        String commandParameter = (String) sobject.get("mcloud__CommandParameter__c");
                        String programToRunId = (String) sobject.get("mcloud__ProgramToRun__c");
                        String forwardToRobotId = (String) sobject.get("mcloud__ForwardToRobot__c");
                        executeCommand(commandName, command, commandParameter, programToRunId, forwardToRobotId, partnerConnection);
                    }
                    catch (Exception e)
                    {
                    	e.printStackTrace();
                    	System.exit(1);
                    }
                }
            });          
        
        // Press button to stop
        Button.waitForAnyPress();
        System.exit(1);;
    }
    
    /**
     * Executes commands received from Salesforce via the Streaming API
     * @param commandName
     * @param command
     * @param commandParameter
     * @param programToRun
     */
    private static void executeCommand(String commandName, String command, String commandParameter, String programToRunId, String forwardToRobotId, PartnerConnection partnerConnection)
    	throws Exception
    {
        LCD.clear();    	
        LCD.drawString(forwardToRobotId!=null ? "Forwarding:" : "Running:  ", 0, 1);
        LCD.drawString(commandName, 0, 2);
        LCD.drawString("Command:  ", 0, 3);
        LCD.drawString(command, 0, 4);
        LCD.drawString("Parameter:", 0, 5);        
        LCD.drawString(commandParameter==null ? "" : commandParameter, 0, 6);
        
        // Forward this command to the specified Robot by insert it
        if(forwardToRobotId!=null)
        {        
        	SObject commandRecord = new SObject();
        	commandRecord.setType("mcloud__Command__c");
        	commandRecord.setField("mcloud__Command__c", command);
        	commandRecord.setField("mcloud__CommandParameter__c", commandParameter);
        	commandRecord.setField("mcloud__Robot__c", forwardToRobotId);
        	if(programToRunId!=null)
        		commandRecord.setField("mcloud__ProgramToRun__c", programToRunId);
        	partnerConnection.create(new SObject[] { commandRecord });
        	return;
        }
        
        // Execute this command on this robot
        int parameter = 1;
        if(commandParameter!=null && commandParameter.length()>0)
        	try { parameter = Integer.parseInt(commandParameter); } catch (Exception e) { }
        if(command.equals("Forward"))
        	EV3DirectCommand.moveForward(parameter);
        else if(command.equals("Backward"))
        	EV3DirectCommand.moveBackwards(parameter);
        else if(command.equals("Rotate Left"))
        	EV3DirectCommand.turnLeft();
        else if(command.equals("Rotate Right"))
        	EV3DirectCommand.turnRight();
        else if(command.equals("Grab"))
        	EV3DirectCommand.grab();
        else if(command.equals("Release"))
        	EV3DirectCommand.release();
        else if(command.equals("LED"))
        	EV3DirectCommand.led(parameter);
        else if(command.equals("Shutdown"))
        	System.exit(1);
        else if(command.equals("Run Program"))
        {
        	// Program to run?
        	if(programToRunId==null)
        		return;
        	// Query for the given program commands and execute them as above
			QueryResult result = 
				partnerConnection.query(
					"select Name, mcloud__Command__c, mcloud__CommandParameter__c, mcloud__ProgramToRun__c, mcloud__ForwardToRobot__c " +
					  "from mcloud__Command__c " +
					  "where mcloud__Program__c = '" + programToRunId + "' order by mcloud__ProgramSequence__c");
			SObject[] commands = result.getRecords();
			for(int loop=0; loop<parameter; loop++)
				for(SObject commandRecord : commands)
					executeCommand(
							(String) commandRecord.getField("Name"),
							(String) commandRecord.getField("mcloud__Command__c"),
							(String) commandRecord.getField("mcloud__CommandParameter__c"),
							(String) commandRecord.getField("mcloud__ProgramToRun__c"),
							(String) commandRecord.getField("mcloud__ForwardToRobot__c"),
							partnerConnection);
        }
    }
    
    /**
     * Uses the Jetty HTTP Client and Cometd libraries to connect to Saleforce Streaming API
     * @param config
     * @return
     * @throws Exception
     */
	private static BayeuxClient makeStreamingAPIConnection(final String sessionid, String serverUrl) 
	        throws Exception 
	{
	    HttpClient httpClient = new HttpClient();
	    httpClient.setConnectTimeout(20 * 1000); // Connection timeout
	    httpClient.setTimeout(120 * 1000); // Read timeout
	    httpClient.start();
	
	    // Determine the correct URL based on the Service Endpoint given during logon
	    URL soapEndpoint = new URL(serverUrl);
	    StringBuilder endpointBuilder = new StringBuilder()
	        .append(soapEndpoint.getProtocol())
	        .append("://")
	        .append(soapEndpoint.getHost());
	    if (soapEndpoint.getPort() > 0) endpointBuilder.append(":")
	        .append(soapEndpoint.getPort());
	    String endpoint = endpointBuilder.toString();
	    
	    // Ensure Session ID / oAuth token is passed in HTTP Header
	    Map<String, Object> options = new HashMap<String, Object>();
	    options.put(ClientTransport.TIMEOUT_OPTION, httpClient.getTimeout());
	    LongPollingTransport transport = new LongPollingTransport(options, httpClient) 
	            {
	                    @Override    
	                protected void customize(ContentExchange exchange) 
	                    {
	                    super.customize(exchange);
	                    exchange.addRequestHeader("Authorization", "OAuth " + sessionid);
	                }
	            };
	
	    // Construct Cometd BayeuxClient
	    BayeuxClient client = new BayeuxClient(new URL(endpoint + "/cometd/29.0").toExternalForm(), transport);
	            
	    // Add listener for handshaking
	    client.getChannel(Channel.META_HANDSHAKE).addListener
	        (new ClientSessionChannel.MessageListener() {
	
	        public void onMessage(ClientSessionChannel channel, Message message) {
	
	            boolean success = message.isSuccessful();
	            if (!success) {
	                String error = (String) message.get("error");
	                if (error != null) {
	                    System.out.println("Error during HANDSHAKE: " + error);
	                    System.out.println("Exiting...");
	                    System.exit(1);
	                }
	
	                Exception exception = (Exception) message.get("exception");
	                if (exception != null) {
	                    System.out.println("Exception during HANDSHAKE: ");
	                    exception.printStackTrace();
	                    System.out.println("Exiting...");
	                    System.exit(1);
	
	                }
	            }
	        }
	
	    });
	
	    // Add listener for connect
	    client.getChannel(Channel.META_CONNECT).addListener(
	        new ClientSessionChannel.MessageListener() {
	        public void onMessage(ClientSessionChannel channel, Message message) {
	
	            boolean success = message.isSuccessful();
	            if (!success) {
	                String error = (String) message.get("error");
	                if (error != null) {
	                    System.out.println("Error during CONNECT: " + error);
	                    System.out.println("Exiting...");
	                    System.exit(1);
	                }
	            }
	        }
	
	    });
	
	    // Add listener for subscribe
	    client.getChannel(Channel.META_SUBSCRIBE).addListener(
	        new ClientSessionChannel.MessageListener() {
	
	        public void onMessage(ClientSessionChannel channel, Message message) {
	
	            boolean success = message.isSuccessful();
	            if (!success) {
	                String error = (String) message.get("error");
	                if (error != null) {
	                    System.out.println("Error during SUBSCRIBE: " + error);
	                    System.out.println("Exiting...");
	                    System.exit(1);
	                }
	            }
	        }
	    });
	
	    // Begin handshaking
	    client.handshake();
	    boolean handshaken = client.waitFor(60 * 1000, BayeuxClient.State.CONNECTED);
	    if (!handshaken) {
	        System.out.println("Failed to handshake: " + client);
	        System.exit(1);
	    }
	
	    return client;
	}    
}
