package mainServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.*;
import java.util.Map.*;
import java.util.concurrent.TimeUnit;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonValue;
import javax.json.JsonWriter;

import mainServer.RTIConnectThread.MessageReceived;

public class RTILib {
	
	private RTISim thisSim;
	private Socket rtiSocket;
	private Socket dedicatedRtiSocket;
	private RTISimConnectThread readThread;
	private RTISimConnectThread writeThread;
	private String simName = "<<default sim name>>";
	public class Message implements Comparable{
		public String name = "";
		public String timestamp = "";
		public String source = "";
		public String content = "";
		public String originalMessage = "";
		
		public int compareTo(Object anotherMessage) {
			if (!(anotherMessage instanceof Message))
				return 0;
			return timestamp.compareTo(((Message)anotherMessage).timestamp);
		}
	}
	//private PriorityQueue<Message> messageQueue = new PriorityQueue<Message>();
	private ArrayList<Message> messageQueue = new ArrayList<Message>();
	private int settingsExists = -1;		// -1 => file doesn't exist, 0 = file doesn't exist but defaults are overwritten by RTIServer, 1 = file exists and defaults are overwritten
	private boolean tcpOn = false;
	
	public RTILib(RTISim rtiSim) {
		thisSim = rtiSim;
		simName = thisSim.getSimName();
	}
	
	// constructor to allow original sim class to NOT extend from RTISim.java: allows more flexibility in implementation
	public RTILib() {
		thisSim = null;
	}
	
	public void setSimName(String newName) {
		simName = newName;
	}
	
	public void setTcpOn(boolean tcp) {
		settingsExists = 1;
		tcpOn = tcp;
		
		if (tcpOn == true) {
			new java.util.Timer().scheduleAtFixedRate(
				new java.util.TimerTask() {
					@Override
					public void run() {
						checkTcpMessages();
					}
				}
			, 5000, 5000);
		}
	}
	
	public int connect() {
		printLine("asked to connect without a hostName or portNumber... can't really do anything, then.");
		return 0;
	}
	
	public int connect(String hostName, String portNumber) {
		
		printLine("trying to connect now...");
		try {
			rtiSocket = new Socket(hostName, Integer.parseInt(portNumber));
			
			BufferedReader in = new BufferedReader(new InputStreamReader(rtiSocket.getInputStream()));
			String dedicatedHost = in.readLine();
			String dedicatedPort = in.readLine();
			printLine("RTI reached. Now connecting to dedicated communication socket: " + dedicatedHost + " " + dedicatedPort);
			dedicatedRtiSocket = new Socket(dedicatedHost, Integer.parseInt(dedicatedPort));
			
			readThread = new RTISimConnectThread(this, dedicatedRtiSocket);
			readThread.start();
			
			JsonObject json = Json.createObjectBuilder()
					.add("simName", simName)
					.build();
			publish("RTI_InitializeSim", json.toString());
			//need to also include publish/subscribe info above ^... would be set with "subscribeTo()" and "publishTo()" functions?
			
			// do I really need a thread just to write? I only write in the "publish()" function.
			//writeThread = new RTISimConnectThread()
			printLine("Connected successfully.");
		} catch (NumberFormatException e) {
			printLine("   NumberFormatException error occurred... ");
			e.printStackTrace();
		} catch (UnknownHostException e) {
			printLine("   UnknownHostException error occurred...");
			e.printStackTrace();
		} catch (IOException e) {
			printLine("   IOException error occurred...");
			e.printStackTrace();
		}

		return 0;
	}
	
	public int disconnect() {
		readThread.closeConnection();
		return 0;
	}
	
	public int subscribeTo(String messageName) {
		JsonObject json = Json.createObjectBuilder()
				.add("subscribeTo", messageName)
				.build();
		publish("RTI_SubscribeTo",json.toString());
		return 0;
	}
	
	public int subscribeToMessagePlusHistory(String messageName) {
		JsonObject json = Json.createObjectBuilder()
				.add("subscribeTo", messageName)
				.build();
		publish("RTI_SubscribeToMessagePlusHistory",json.toString());
		return 0;
	}
	
	public int subscribeToAll() {
		publish("RTI_SubscribeToAll", "");
		return 0;
	}
	
	public int subscribeToAllPlusHistory() {
		publish("RTI_SubscribeToAllPlusHistory","");
		return 0;
	}
	
	public int publishTo(String messageName) {
		JsonObject json = Json.createObjectBuilder()
				.add("publishTo", messageName)
				.build();
		publish("RTI_PublishTo",json.toString());
		return 0;
	}
	
	public int publish(String name, String content) {
		// combine into json object when sending
		printLine("\t\t\t PUBLISH THIS: " + name);
		try {
			long timestamp = System.currentTimeMillis();
			JsonObject json =  Json.createObjectBuilder()
					.add("name", name)
					.add("content", content)
					.add("timestamp", "" + timestamp)
					.add("source", simName)
					.add("tcp", "" + tcpOn)
					.build();
			PrintWriter out;
			out = new PrintWriter(dedicatedRtiSocket.getOutputStream(), true);
			out.println(json);
			out.flush();
			
			if (name.compareTo("RTI_ReceivedMessage") != 0) {
				handleTcpResponse(name, content, "" + timestamp, simName, json.toString());
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			printLine("   IOExceoption error happened here...");
			e.printStackTrace();
		}

		
		return 0;
	}
	
	public int sendWithoutAddingToTcp(String name, String content, String timestamp, String source) {
		printLine("\t\t\t PUBLISH THIS: " + name);
		try {
			JsonObject json =  Json.createObjectBuilder()
					.add("name", name)
					.add("content", content)
					.add("timestamp", "" + timestamp)
					.add("source", source)
					.add("tcp", "" + tcpOn)
					.build();
			PrintWriter out;
			out = new PrintWriter(dedicatedRtiSocket.getOutputStream(), true);
			out.println(json);
			out.flush();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			printLine("   IOExceoption error happened here...");
			e.printStackTrace();
		}
		
		return 0;
	}
	
	public int receivedMessage(String message) {
		String name = "";
		String content = "";
		String timestamp = "";
		String source = "";
		// handle tcp if server expects a response
		String tcp = "";
		
		JsonReader reader = Json.createReader(new StringReader(message));
		JsonObject json = reader.readObject();
		
		name = json.getString("name");
		content = json.getString("content");
		timestamp = json.getString("timestamp");
		source = json.getString("source");
		tcp = json.getString("tcp");
		
		if (name.compareTo("RTI_ReceivedMessage")==0) {
			// Tell sim it received the message, it can stop waiting for response now.
			setTcpResponse(true, content);
			return 0;
		}
		
		if (settingsExists == -1) {
			if (Boolean.parseBoolean(tcp) == true) {
				tcpOn = true;
				settingsExists = 0;
				publish("RTI_ReceivedMessage", message);
				
				// below code needs to be repeated in place where "settings" file would be (locally with RTILib)
					new java.util.Timer().scheduleAtFixedRate(
							new java.util.TimerTask() {
								@Override
								public void run() {
									checkTcpMessages();
								}
							}
							, 5000, 5000);
			}
		} else {
			if (Boolean.parseBoolean(tcp) == true) {
				publish("RTI_ReceivedMessage", message);
			}
		}
		
		//if thisSim is available, call upon api directly, else add message to an ordered queue (ordered based on timestamp)
		if (thisSim != null) {
			thisSim.receivedMessage(name, content, timestamp, source);
		} else {
			Message newMessage = new Message();
			newMessage.name = name;
			newMessage.content = content;
			newMessage.timestamp = timestamp;
			newMessage.source = source;
			newMessage.originalMessage = message;
			messageQueue.add(newMessage);
			/* (no point to sort list any more, we just iterate through whole array list anyway...)
			try {
				Collections.sort(messageQueue);
			} catch (Exception e) {
				printLine("For some reason, unable to sort the list due to Collections.sort(messageQueue) giving an error of NoSuchElementException... " + e.getMessage());
			}*/
			printLine("Received new message, messageQueue now has this many: " + messageQueue.size());
		}

		return 0;
	}
	
	public class MessageReceived{
		int sendAttempts = 0;
		boolean messageReceived = false;
		String name = "";
		String content = "";
		String timestamp = "";
		String source = "";
		String message = "";
		long originalTimeSent = 0;
	}
	ArrayList<MessageReceived> tcpMessageBuffer = new ArrayList<MessageReceived>();
	
	public void setTcpResponse(boolean setResponse, String message) {
		Iterator<MessageReceived> i = tcpMessageBuffer.iterator();
		while (i.hasNext()) {
			MessageReceived mr = i.next();
			if (mr.message.compareTo(message) == 0) {
				// we could just set the variable to "true" and handle removing elsewhere, 
				// or in best case scenario, we can remove here and never have to worry about checking to resend (list would be empty).
				i.remove();
			}
		}
	}
	
	private int handleTcpResponse(String name, String content, String timestamp, String source, String message) {
		if (tcpOn == false)
			return 0;
		
		MessageReceived newMessage = new MessageReceived();
		newMessage.sendAttempts = 1;
		newMessage.messageReceived = false;
		newMessage.name = name;
		newMessage.content = content;
		newMessage.timestamp = timestamp;
		newMessage.source = source;
		newMessage.message = message;
		newMessage.originalTimeSent = System.currentTimeMillis();
		tcpMessageBuffer.add(newMessage);
		
		return 0;
	}
	
	public void checkTcpMessages() {
		if (tcpMessageBuffer.isEmpty())
			return;
		
		Iterator<MessageReceived> it = tcpMessageBuffer.iterator();
		while (it.hasNext()) {
			MessageReceived mr = it.next();
			if (mr.sendAttempts >= 3) {
				it.remove();
			}
		}
		
		for (int i = 0; i < tcpMessageBuffer.size(); i++) {
			if (System.currentTimeMillis() - tcpMessageBuffer.get(i).originalTimeSent > 3000) {
				tcpMessageBuffer.get(i).sendAttempts++;
				sendWithoutAddingToTcp(tcpMessageBuffer.get(i).name, tcpMessageBuffer.get(i).content, tcpMessageBuffer.get(i).timestamp, tcpMessageBuffer.get(i).source);
			}
		}
	}
	
	public String getNextMessage() {
		String returnString = "";
		printLine("getNextMessage() called...");
		if (messageQueue.isEmpty()) {
			returnString = null;
			printLine("getNextMessage is null.");
		} else {
			//returnString = messageQueue.poll().originalMessage;
			returnString = messageQueue.get(0).originalMessage;
			messageQueue.remove(0);
			
			printLine("getNextMessage was NOT null.");
		}
		return returnString;
	}
	
	public String getNextMessage(int millisToWait) {
		String returnString = "";
		printLine("getNextMessage() called...");
		for (int i = 0; i < millisToWait; i+=10) {
			if (messageQueue.isEmpty() == false) {
				break;
			}
			try {
				TimeUnit.MILLISECONDS.sleep(10);
			} catch (InterruptedException e) {
				printLine("while trying to get next message, some error happened: " + e.getMessage());
			}
		}
		if (messageQueue.isEmpty()) {
			returnString = null;
			printLine("getNextMessage(millisToWait) is null.");
		} else {
			//returnString = messageQueue.poll().originalMessage;
			returnString = messageQueue.get(0).originalMessage;
			messageQueue.remove(0);
			
			printLine("getNextMessage(millisToWait) was NOT null.");
		}
		return returnString;
	}
	
	public String getNextMessage(Integer millisToWait) {
		String returnString = "";
		printLine("getNextMessage() called...");
		for (int i = 0; i < millisToWait; i+=10) {
			if (messageQueue.isEmpty() == false) {
				break;
			}
			try {
				TimeUnit.MILLISECONDS.sleep(10);
			} catch (InterruptedException e) {
				printLine("while trying to get next message, some error happened: " + e.getMessage());
			}
		}
		if (messageQueue.isEmpty()) {
			returnString = null;
			printLine("getNextMessage(millisToWait) is null.");
		} else {
			//returnString = messageQueue.poll().originalMessage;
			returnString = messageQueue.get(0).originalMessage;
			messageQueue.remove(0);
			
			printLine("getNextMessage(millisToWait) was NOT null.");
		}
		return returnString;
	}
	
	public String getNextMessage(String messageName) {
		String returnString = null;
		printLine("getNextMessage() called...");
		if (messageQueue.isEmpty()) {
			returnString = null;
			printLine("getNextMessage is null.");
		} else {
			//returnString = messageQueue.poll().originalMessage;
			for (int i = 0; i < messageQueue.size(); i++) {
				if (messageQueue.get(i).name.compareTo(messageName)==0) {
					returnString = messageQueue.get(i).originalMessage;
					messageQueue.remove(i);
					break;
				}
			}
			printLine("getNextMessage was NOT null.");
		}
		return returnString;
	}
	
	public String getNextMessage(String messageName, int millisToWait) {
		String returnString = null;
		//printLine("getNextMessage() called...");
		String debugErr = readThread.getDisconnectedErr();
		if (debugErr != null) {
			printLine("WARNING: did you know there might be a disconnect with the RTI? " + debugErr);
			System.out.println("WARNING: did you know there might be a disconnect with the RTI? " + debugErr);
		}
		
		String debugLine = "";
		//for debugging, print out queue before and after 
		for (int i = 0; i < messageQueue.size(); i++) {
			debugLine += messageQueue.get(i).name + "\t";
		}
		printLine("queue before checking message: size = " + messageQueue.size() + " ... " + debugLine);
		for (int j = 0; j < millisToWait; j+=10) {
			if (messageQueue.isEmpty() == false) {
				for (int i = 0; i < messageQueue.size(); i++) {
					if (messageQueue.get(i).name.compareTo(messageName)==0) {
						returnString = messageQueue.get(i).originalMessage;
						messageQueue.remove(i);
						break;
					}
				}
			}
			try {
				TimeUnit.MILLISECONDS.sleep(10);
			} catch (InterruptedException e) {
				printLine("while trying to get next message, some error happened: " + e.getMessage());
			}
		}
		//for debugging, print out queue before and after 
		debugLine = "";
		for (int i = 0; i < messageQueue.size(); i++) {
			debugLine += messageQueue.get(i).name + "\t";
		}
		//printLine("queue after checking message: size = " + messageQueue.size() + " ... " + debugLine);
		//printLine("confirm socket is still connected = " + readThread.rtiSocket.isConnected());
		return returnString;
	}
	
	public String waitForNextMessage() {
		String returnString = "";
		printLine("will immediately return message if there is one in the message buffer, else will wait until the queue gets a value.");
		while (messageQueue.isEmpty()) {
			
		}
		//returnString = messageQueue.poll().originalMessage;
		returnString = messageQueue.get(0).originalMessage;
		messageQueue.remove(0);
		
		return returnString;
	}
	
	public String getJsonObject(String name, String content) {
		String returnString = "";
		
		printLine("asked to read jsonValue from content (" + content + ")");
		
		if (content.compareTo("")==0 || content == null) {
			returnString = null;
			return returnString;
		}
		
		try {
			JsonReader reader = Json.createReader(new StringReader(content));
			JsonObject json = reader.readObject();
			
			if (json.containsKey(name)) {
				// extra formatting to remove quotes: "json.get(string)" returns different format from "json.getString(string)"
				JsonValue jvalue = json.get(name);
				if (jvalue.getValueType() == JsonValue.ValueType.STRING) {
					returnString = json.getString(name).toString();
				} else {
					returnString = json.get(name).toString();
				}
			}
			else {
				returnString = null;
			}
			
			printLine("asked to read jsonValue for " + name + " " + returnString);
		}
		catch (Exception e) {
			printLine("something went wrong when trying to get Json object. Returning null.");
			returnString = null;
			return returnString;
		}
		return returnString;
	}
	
	/*public String getJsonValue(String name, String content) {
		String returnString = "";
		
		printLine("asked to read jsonValue with content (" + content + ")");
		
		if (content.compareTo("")==0 || content == null) {
			returnString = null;
			return returnString;
		}
		
		JsonReader reader = Json.createReader(new StringReader(content));
		JsonObject json = reader.readObject();
		
		if (json.containsKey(name)) {
			// extra formatting to remove quotes: "json.get(string)" returns different format from "json.getString(string)"
			returnString = json.get(name).toString();
		}
		else {
			returnString = null;
		}
		
		printLine("asked to read jsonValue for " + name + " " + returnString);
		
		return returnString;
	}*/
	
	public String getJsonString(String name, String content) {
		String returnString = "";
		
		if (content == "" || content == null) {
			returnString = null;
			return returnString;
		}
		
		JsonReader reader = Json.createReader(new StringReader(content));
		JsonObject json = reader.readObject();
		
		if (json.containsKey(name)) {
			// extra formatting to remove quotes: "json.get(string)" returns different format from "json.getString(string)"
			returnString = json.getString(name).toString();
			
		}
		else {
			returnString = null;
		}
		return returnString;
	}
	
	public String[] getJsonArray(String content) {
		String [] returnString;
		
		if (content == "" || content == null) {
			returnString = new String[0];
			return returnString;
		}
		
		JsonReader reader = Json.createReader(new StringReader(content));
		JsonArray json = reader.readArray();
		
		returnString = new String[json.size()];
		for (int i = 0; i < returnString.length; i++) {
			returnString[i] = json.get(i).toString();
		}
		return returnString;
	}
	
	public String getStringNoQuotes(String content) {
		String returnString = "";
		
		int numOfQuotes = 0;
		for (int i = 0; i < content.length(); i++) {
			if (content.charAt(i) == '\"') {
				numOfQuotes++;
			}
		}
		if (numOfQuotes >= 2) {
			returnString = content.substring(1,content.length()-1);
		} else {
			returnString = content;
		}
		
		return returnString;
	}
	
	public String getMessageName(String originalMessage) {
		String returnString = "";
		JsonReader reader = Json.createReader(new StringReader(originalMessage));
		JsonObject json = reader.readObject();
		
		if (json.containsKey("name")) {
			// extra formatting to remove quotes: "json.get(string)" returns different format from "json.getString(string)"
			returnString = json.get("name").toString();
		}
		else {
			returnString = null;
		}
		return returnString;
	}
	
	public String getMessageTimestamp(String originalMessage) {
		String returnString = "";
		JsonReader reader = Json.createReader(new StringReader(originalMessage));
		JsonObject json = reader.readObject();
		
		if (json.containsKey("timestamp")) {
			// extra formatting to remove quotes: "json.get(string)" returns different format from "json.getString(string)"
			returnString = json.get("timestamp").toString();
		}
		else {
			returnString = null;
		}
		return returnString;
	}
	
	public String getMessageSource(String originalMessage) {
		String returnString = "";
		JsonReader reader = Json.createReader(new StringReader(originalMessage));
		JsonObject json = reader.readObject();
		
		if (json.containsKey("source")) {
			// extra formatting to remove quotes: "json.get(string)" returns different format from "json.getString(string)"
			returnString = json.get("source").toString();
		}
		else {
			returnString = null;
		}
		return returnString;
	}
	
	public String getMessageContent(String originalMessage) {
		String returnString = "";
		JsonReader reader = Json.createReader(new StringReader(originalMessage));
		JsonObject json = reader.readObject();
		
		if (json.containsKey("content")) {
			// extra formatting to remove quotes: "json.get(string)" returns different format from "json.getString(string)"
			returnString = json.getString("content").toString();
		}
		else {
			returnString = null;
		}
		return returnString;
	}
	
	public String setJsonObject(String originalJson, String nameNewObject, int contentNewObject) {
		return setJsonObject(originalJson, nameNewObject, "" + contentNewObject);
	}
	
	public String setJsonObject(String originalJson, String nameNewObject, float contentNewObject) {
		return setJsonObject(originalJson, nameNewObject, "" + contentNewObject);
	}
	
	public String setJsonObject(String originalJson, String nameNewObject, long contentNewObject) {
		return setJsonObject(originalJson, nameNewObject, "" + contentNewObject);
	}
	
	public String setJsonObject(String originalJson, String nameNewObject, double contentNewObject) {
		return setJsonObject(originalJson, nameNewObject, "" + contentNewObject);
	}
	
	public String setJsonObject(String originalJson, String nameNewObject, char contentNewObject) {
		return setJsonObject(originalJson, nameNewObject, "" + contentNewObject);
	}
	
	public String setJsonObject(String originalJson, String nameNewObject, boolean contentNewObject) {
		return setJsonObject(originalJson, nameNewObject, "" + contentNewObject);
	}
	
	public String setJsonObject(String originalJson, String nameNewObject, String contentNewObject) {
		String returnString =  "";
		
		JsonObject newJson;
		JsonObjectBuilder newJsonBuilder = Json.createObjectBuilder();
		if (originalJson != null && originalJson.compareTo("") != 0) {
			try {
				JsonReader reader = Json.createReader(new StringReader(originalJson));
				JsonObject json = reader.readObject();
				
				for (java.util.Map.Entry<String, JsonValue> entry: json.entrySet()) {
					newJsonBuilder.add(entry.getKey(), entry.getValue());
				}
			} catch (Exception e) {
				printLine("There was something wrong with the original JSON object, ignoring it and making a new object.");
			}
		}
		newJsonBuilder.add(nameNewObject, contentNewObject);
		newJson =  newJsonBuilder.build();
		returnString = newJson.toString();
		
		return returnString;
	}
	
	/*public String setJsonObject(String originalJson, String nameNewObject, Object contentNewArray) {
		String returnString = "";
		setJsonObject(originalJson, nameNewObject, contentNewArray.toString());
		return returnString;
	}*/
	
	/*public String setJsonArray(String originalJson, String nameNewObject, Object contentNewArray) {
		String returnString = "";
		setJsonArray(originalJson, nameNewObject, contentNewArray.toString());
		return returnString;
	}*/
	
	
	
	public String setJsonArray(String originalJson, String nameNewObject, String[] contentNewArray) {
		String returnString = "";
		
		JsonObject newJson;
		JsonArray newJsonArray;
		JsonObjectBuilder newJsonBuilder = Json.createObjectBuilder();
		JsonArrayBuilder newArrayBuilder = Json.createArrayBuilder();
		if (originalJson != null && originalJson != "") {
			try {
				JsonReader reader = Json.createReader(new StringReader(originalJson));
				JsonObject json = reader.readObject();
				for (java.util.Map.Entry<String, JsonValue> entry: json.entrySet()) {
					newJsonBuilder.add(entry.getKey(), entry.getValue());
				}
			} catch (Exception e) {
				printLine("There was something wrong with the original JSON object, ignoring it and making a new object.");
			}
		}
		for (int i = 0; i < contentNewArray.length; i++) {
			newArrayBuilder.add(contentNewArray[i]);
		}
		newJsonArray = newArrayBuilder.build();
		newJsonBuilder.add(nameNewObject, newJsonArray);
		newJson =  newJsonBuilder.build();
		returnString = newJson.toString();
		
		return returnString;
	}

	//private String version = "v0.54";
	public void printVersion() {
		printLine("SRTI Version - " + Version.version);
	}
	
	private boolean debugOut = false;
	public void setDebugOutput(boolean setDebugOut) {
		debugOut = setDebugOut;
		
		if (readThread != null)
			readThread.setDebugOutput(setDebugOut);
		if (writeThread != null)
			writeThread.setDebugOutput(setDebugOut);
	}
	
	private String tag = "RTILib";
	public void printLine(String line) {
		if (debugOut == false)
			return;
		
		System.out.println(String.format("%1$32s", "[" + tag + "]" + " --- ") + line);
	}

}
