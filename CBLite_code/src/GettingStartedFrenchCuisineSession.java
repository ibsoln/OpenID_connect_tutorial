import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.couchbase.lite.BasicAuthenticator;
import com.couchbase.lite.CouchbaseLite;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.DataSource;
import com.couchbase.lite.Database;
import com.couchbase.lite.DatabaseConfiguration;
import com.couchbase.lite.Document;
import com.couchbase.lite.DocumentReplication;
import com.couchbase.lite.DocumentReplicationListener;
import com.couchbase.lite.Endpoint;
import com.couchbase.lite.Expression;
import com.couchbase.lite.Meta;
import com.couchbase.lite.MutableDocument;
import com.couchbase.lite.Query;
import com.couchbase.lite.QueryBuilder;
import com.couchbase.lite.ReplicatedDocument;
import com.couchbase.lite.Replicator;
import com.couchbase.lite.ReplicatorConfiguration;
import com.couchbase.lite.Result;
import com.couchbase.lite.ResultSet;
import com.couchbase.lite.SelectResult;
import com.couchbase.lite.SessionAuthenticator;
import com.couchbase.lite.URLEndpoint;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;

public class GettingStartedFrenchCuisineSession {

	private static final String SET_COOKIE = "Set-Cookie";
	private static final String SG_COOKIE_NAME = "SyncGatewaySession";
	private static final String SGW_SESSION_URL = "http://localhost:4984/french_cuisine/_session";
	
	private static final String DB_NAME = "french_cuisine";
	/*
	 * Credentials declared this way purely for expediency in this demo - use OAUTH
	 * in production code
	 */
	private static final String DB_USER = "wolfgang"; // (Bretagne) OR wolfgang (Alsace) marius (PACA)
	private static final String DB_PASS = "password";

	private static final String SYNC_GATEWAY_URL =  "ws://localhost:4984/french_cuisine"; // "ws://52.174.108.107:4984/stime";
	private static final String DB_PATH = new File("").getAbsolutePath() + "/resources";

	public static void main(String[] args) throws CouchbaseLiteException, InterruptedException, URISyntaxException {
		Random RANDOM = new Random();

		Double randVn = RANDOM.nextDouble() + 1;

		String Prop_Id = "id";
		String Prop_Name = "name";
		String Prop_Price = "price";

		String Prop_Type = "type";
		String searchStringType = "product";

		String Prop_Channels = "channels";
		String channelValue = "PDV_Bretagne";

		// Initialize Couchbase Lite
		CouchbaseLite.init();

		// Get the database (and create it if it doesn’t exist).
		DatabaseConfiguration config = new DatabaseConfiguration();
		config.setDirectory(DB_PATH);
		// config.setEncryptionKey(new EncryptionKey(DB_PASS));
		Database database = new Database(DB_NAME, config);

		// Create a new document (i.e. a record) in the database.
		MutableDocument mutableDoc = new MutableDocument("produit_from_CBL_" + UUID.randomUUID()).setString(Prop_Type,
				"product");

		// Save it to the database.
		database.save(mutableDoc);

		// Update a document.
		mutableDoc = database.getDocument(mutableDoc.getId()).toMutable();
		mutableDoc.setDouble(Prop_Price, randVn);
		mutableDoc.setString(Prop_Name, "produit_local_DB");
		mutableDoc.setString(Prop_Channels, channelValue);
		database.save(mutableDoc);

		Document document = database.getDocument(mutableDoc.getId());
		// Log the document ID (generated by the database) and properties
		System.out.println("Document ID is :: " + document.getId());
		System.out.println("Name " + document.getString(Prop_Name));
		System.out.println("Price " + document.getDouble(Prop_Price));
		System.out.println("Channels " + document.getString(Prop_Channels));

		// Create a query to fetch documents of type "product".
		System.out.println("== Executing Query 1");
		Query query = QueryBuilder.select(SelectResult.all()).from(DataSource.database(database))
				.where(Expression.property(Prop_Type).equalTo(Expression.string(searchStringType)));
		ResultSet result = query.execute();
		System.out.println(
				String.format("Query returned %d rows of type %s", result.allResults().size(), searchStringType));

//		// Create a query to fetch all documents.
//		System.out.println("== Executing Query 2");
//		Query queryAll = QueryBuilder
//				.select(SelectResult.expression(Meta.id), SelectResult.property(Prop_Name),
//						SelectResult.property(Prop_Price), SelectResult.property(Prop_Type))
//				.from(DataSource.database(database));
//		try {
//			for (Result thisDoc : queryAll.execute()) {
//				numRows++;
//				System.out.println(String.format("%d ... Id: %s is learning: %s version: %.2f type is %s", numRows,
//						thisDoc.getString(Prop_Id), thisDoc.getString(Prop_Name), thisDoc.getDouble(Prop_Price),
//						thisDoc.getString(Prop_Type)));
//			}
//		} catch (CouchbaseLiteException e) {
//			e.printStackTrace();
//		}
//		System.out.println(String.format("Total rows returned by query = %d", numRows));

		Endpoint targetEndpoint = new URLEndpoint(new URI(SYNC_GATEWAY_URL));
		ReplicatorConfiguration replConfig = new ReplicatorConfiguration(database, targetEndpoint);
		replConfig.setReplicatorType(ReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL);

		replConfig.setContinuous(true);

		// get the id_token from user credentials
		String tokenID = getTokenID(DB_USER, DB_PASS);

		// Add authentication through Session.
		// replConfig.setAuthenticator(new SessionAuthenticator(tokenID, SG_COOKIE_NAME));
		replConfig.setAuthenticator(new SessionAuthenticator("c0c80f2f689fe4d6b9e836a25e0b7182fbcb530c", SG_COOKIE_NAME));
		
		//replConfig.setAuthenticator(new BasicAuthenticator(DB_USER, DB_PASS));
//		
//		List<String> list = new ArrayList<String>();
//		list.add("PDV_Bretagne");
//		replConfig.setChannels(list);
//		
//		if(null != replConfig.getChannels()){
//			for (String channel : replConfig.getChannels()) {
//				System.out.println("A channel for user " + DB_USER + " is : " + channel);
//			}
//		}

		// Create replicator (be sure to hold a reference somewhere that will prevent
		// the Replicator from being GCed)
		Replicator replicator = new Replicator(replConfig);

		// Listen to replicator change events.
		replicator.addChangeListener(change -> {
			if (change.getStatus().getError() != null) {
				System.err.println("Error code ::  " + change.getStatus().getError().getCode());
			}
		});

		replicator.addDocumentReplicationListener(new DocumentReplicationListener() {

			@Override
			public void replication(DocumentReplication documentReplication) {
				for (ReplicatedDocument rep : documentReplication.getDocuments()) {
					System.err.println("Document " + rep.getID() + " has been replicated !!");
				}
			}
		});

		// Start replication.
		replicator.start();

		// Check status of replication and wait till it is completed
		while (replicator.getStatus().getActivityLevel() != Replicator.ActivityLevel.STOPPED) {
			Thread.sleep(5000);

			int numRows = 0;
			// Create a query to fetch all documents.
			System.out.println("== Executing Query 3");
			Query queryAll = QueryBuilder.select(SelectResult.expression(Meta.id), SelectResult.property(Prop_Name),
					SelectResult.property(Prop_Price), SelectResult.property(Prop_Type),
					SelectResult.property(Prop_Channels)).from(DataSource.database(database));
			try {
				for (Result thisDoc : queryAll.execute()) {
					numRows++;
					System.out.println(String.format("%d ... Id: %s is learning: %s version: %.2f type is %s", numRows,
							thisDoc.getString(Prop_Id), thisDoc.getString(Prop_Name), thisDoc.getDouble(Prop_Price),
							thisDoc.getString(Prop_Type)));
				}
			} catch (CouchbaseLiteException e) {
				e.printStackTrace();
			}
			System.out.println(String.format("Total rows returned by query = %d", numRows));
		}

		System.out.println("Finish!");

		System.exit(0);
	}
	

	/**
	 * Compute tokenID from DBUSER / DBPASS
	 * 
	 * @param dbUser
	 * @param dbPass
	 * @return
	 */
	private static String getTokenID(String dbUser, String dbPass) {

		// Run the Authentication POST request with the given username/password to
		// obtain the id_token.
		HttpResponse<String> response2 = Unirest
				.post(SGW_SESSION_URL)
				.header("Content-type", "application/json")
				.body(new JSONObject("{'name':'paul','password':'password'}"))
				.asString();

		// get the id_token
		List<String> locationHeaderList = response2.getHeaders().get(SET_COOKIE);
		String sessionID = locationHeaderList.get(0);
		
		System.out.println("sessionID = " + sessionID);
		
		int index1 = sessionID.indexOf(SG_COOKIE_NAME+"=");
		sessionID = sessionID.substring(index1+SG_COOKIE_NAME.length()+1);

		sessionID = sessionID.substring(0, sessionID.indexOf(";"));
		
		return sessionID;
	}
}