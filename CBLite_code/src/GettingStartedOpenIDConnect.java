import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

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

import kong.unirest.Cookie;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;

public class GettingStartedOpenIDConnect {

	private static final String STATE = "af0ifjsldk";
	private static final String NONCE = "34fasf3ds";

	private static final String AMPERSAND = "&amp;";
	private static final String AMPERSAND_CHAR = "&";
	private static final String ACTION_ATTRIBUTE = "action=\"";
	private static final String SG_COOKIE_NAME = "SyncGatewaySession";
	private static final String LOCATION_HEADER_NAME = "Location";
	private static final String DB_NAME = "travel-sample";
	
	// urls
	// Sync Gateway DB endpoint
	private static final String SG_DB_URL = "http://sync-gateway:4984/travel-sample/";
	// Keycloak (KC) endpoint
	private static final String KC_OIDC_AUTH_URL = "http://keycloak:8080/auth/realms/master/protocol/openid-connect/auth/"; 

	// Credentials of KC user "kc_fabrice".
	private static final String DB_USER = "kc_fabrice";
	private static final String DB_PASS = "password";

	private static final String SYNC_GATEWAY_URL = "ws://sync-gateway:4984/travel-sample";
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

		Endpoint targetEndpoint = new URLEndpoint(new URI(SYNC_GATEWAY_URL));
		ReplicatorConfiguration replConfig = new ReplicatorConfiguration(database, targetEndpoint);
		replConfig.setReplicatorType(ReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL);

		replConfig.setContinuous(true);

		// =======================================
		// Add OpenID Connect authentication here.
		// =======================================
		
		// get the id_token from user credentials
		String tokenID = getTokenID(DB_USER, DB_PASS);
		// create session storing the id_token (at SG level)
		// and save the sessionID inside a cookie
		Cookie cookie = createSessionCookie(tokenID);   
		replConfig.setAuthenticator(new SessionAuthenticator(cookie.getValue(), SG_COOKIE_NAME));

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
		// http://keycloak:8080/auth/realms/master/protocol/openid-connect/auth/?
		// response_type=id_token&client_id=SyncGateway&scope=openid+profile
		// &redirect_uri=http%3A%2F%2Flocalhost%3A4984%2Ftravel-sample%2F
		// &nonce=34fasf3ds&state=af0ifjsldkj&foo=bar/

		HttpResponse<String> response1 = Unirest
				.get(KC_OIDC_AUTH_URL)
				.header("accept", "application/json")
				.queryString("response_type", "id_token")
				.queryString("client_id", "SyncGateway")
				.queryString("scope", "id_token")
				.queryString("redirect_uri", SG_DB_URL)
				.queryString("nonce", NONCE)
				.queryString("state", STATE)
				.asString();

		// <form id="kc-form-login" onsubmit="login.disabled = true; return true;"
		// action="http://keycloak:8080/auth/realms/master/login-actions/authenticate?session_code=FlKWqRz58B_2YBXQRRtYbjokPFfKu5BaoUWUzaDlZw8&amp;execution=afca2cf6-c09f-4c7b-91f5-3cd7d3d69410&amp;client_id=SyncGateway&amp;
		// tab_id=-85fFZhlrcU" method="post">

		// get POST method
		URL postURL = extractPostURL(response1.getBody());

		String basePostURL = postURL.toString().split("\\?")[0];
		System.out.println("basePostURL = " + basePostURL);

		// Parse the queryString into Name-Value map
		Map<String, Object> mapQueryString = null;
		try {
			mapQueryString = splitQuery(postURL);
		} catch (UnsupportedEncodingException e) {
			System.err.println(e);
			;
		}

		// Run the Authentication POST request with the given username/password to
		// obtain the id_token.
		HttpResponse<String> response2 = Unirest
				.post(basePostURL)
				.header("accept", "application/json")
				.queryString(mapQueryString)
				.field("username", dbUser)
				.field("password", dbPass)
				.asString();

		// get the id_token
		List<String> locationHeaderList = response2.getHeaders().get(LOCATION_HEADER_NAME);
		if (locationHeaderList == null) {
			throw new IllegalArgumentException("locationHeaderList is null");
		}

		String locationHeader = locationHeaderList.get(0);

		if (locationHeader == null) {
			throw new IllegalArgumentException("locationHeader is null");
		}

		URL urlWithToken = null;
		try {
			urlWithToken = new URL(locationHeader);
		} catch (MalformedURLException e) {
			System.err.println(e);
		}

		Map<String, Object> refParams = splitRef(urlWithToken);

		String idTokenValue = (String) refParams.get("id_token");
		if (idTokenValue == null) {
			throw new IllegalArgumentException("id_token is missing");
		}

		return idTokenValue;
	}

	/**
	 * Get the POST URL contained in the HTML action attribute inside the submit
	 * form
	 * 
	 * Example : // <form id="kc-form-login" onsubmit="login.disabled = true; return
	 * true;" //
	 * action="http://keycloak:8080/auth/realms/master/login-actions/authenticate?session_code=FlKWqRz58B_2YBXQRRtYbjokPFfKu5BaoUWUzaDlZw8&amp;execution=afca2cf6-c09f-4c7b-91f5-3cd7d3d69410&amp;client_id=SyncGateway&amp;
	 * // tab_id=-85fFZhlrcU" method="post">
	 *
	 * return the full authentication url
	 *
	 * @param body
	 */
	private static URL extractPostURL(String body) {
		int index1 = body.indexOf(ACTION_ATTRIBUTE);

		if (index1 == -1) {
			throw new IllegalArgumentException("Form with POST url is missing in the returned page");
		}

		body = body.substring(index1 + ACTION_ATTRIBUTE.length());
		int index2 = body.indexOf("\"");

		URL url = null;
		try {
			url = new URL(body.substring(0, index2));
		} catch (MalformedURLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return url;
	}

	/**
	 * 
	 * Parse the queryString (?...) KV into Name-Value map
	 * 
	 * @param url
	 * @return
	 * @throws UnsupportedEncodingException
	 */
	public static Map<String, Object> splitQuery(URL url) throws UnsupportedEncodingException {
		Map<String, Object> query_pairs = new LinkedHashMap<String, Object>();
		String query = url.getQuery();
		String[] pairs = query.split(AMPERSAND);
		for (String pair : pairs) {
			int idx = pair.indexOf("=");
			query_pairs.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
					URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
		}
		return query_pairs;
	}

	
	/**
	 * 
	 * Parse the references (#...) KV into Name-Value map
	 * 
	 * @param url
	 * @return
	 * @throws UnsupportedEncodingException
	 */
	public static Map<String, Object> splitRef(URL url) {
		Map<String, Object> query_pairs = new LinkedHashMap<String, Object>();
		String query = url.getRef();
		String[] pairs = query.split(AMPERSAND_CHAR);
		for (String pair : pairs) {
			int idx = pair.indexOf("=");
			query_pairs.put(pair.substring(0, idx), pair.substring(idx + 1));
		}
		return query_pairs;
	}

	private static Cookie createSessionCookie(String idTokenValue) {

		HttpResponse<String> response3 = Unirest
				.post("http://sync-gateway:4984/travel-sample/_session")
				.header("Authorization", "Bearer " + idTokenValue)
				.asString();

		System.out.println(" >>>> idTokenValue = " + idTokenValue);
		System.out.println(" >>>> " + response3.getBody());

		Iterator<Cookie> it = response3.getCookies().iterator();
		Cookie resCookie = null;

		while (it.hasNext()) {
			Cookie cookie = it.next();
			if (SG_COOKIE_NAME.equals(cookie.getName())) {
				resCookie = cookie;
				break;
			}
		}

		return resCookie;
	}
}