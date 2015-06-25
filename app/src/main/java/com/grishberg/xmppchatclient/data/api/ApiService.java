package com.grishberg.xmppchatclient.data.api;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;


import com.grishberg.xmppchatclient.AppController;
import com.grishberg.xmppchatclient.data.db.AppContentProvider;
import com.grishberg.xmppchatclient.data.db.containers.ChatContainer;
import com.grishberg.xmppchatclient.data.db.containers.GroupContainer;
import com.grishberg.xmppchatclient.data.db.containers.MessageContainer;
import com.grishberg.xmppchatclient.data.db.containers.Relation;
import com.grishberg.xmppchatclient.data.db.containers.User;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.chat.Chat;
import org.jivesoftware.smack.chat.ChatManager;
import org.jivesoftware.smack.chat.ChatManagerListener;
import org.jivesoftware.smack.chat.ChatMessageListener;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.roster.RosterEntry;
import org.jivesoftware.smack.roster.RosterGroup;
import org.jivesoftware.smack.roster.RosterListener;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;

import java.util.Collection;
import java.util.Date;

public class ApiService extends Service implements
		ChatManagerListener
		, MessageListener
		, ChatMessageListener
		, RosterListener
		, ConnectionListener{
	private static final String TAG = "XmppChat.ApiService";
	public static final String ACTION_ON_CONNECTED_RESULT 			= "onConnectedResult";
	public static final String ACTION_ON_CONNECTION_STATUS_CHANGED 	= "onConnectionChanged";
	public static final String EXTRA_CONNECTION_STATUS 				= "connectionStatus";

	public static final int CONNECTION_STATUS_OK				= 0;
	public static final int CONNECTION_STATUS_BAD_PASSWORD		= 1;
	public static final int CONNECTION_STATUS_BAD_SERVER		= 2;
	public static final int CONNECTION_STATUS_ERROR_CONNECTION	= 3;

	private AbstractXMPPConnection 	mConnection;
	private ChatManager 		mChatManager;
	private Handler 			mConnectionHandler;
	private Roster 				mRoster;
	private String				mLogin;
	private String				mPassword;
	private String				mServer;
	private Thread				mConnectionThread;
	private int 				mStartMode = START_REDELIVER_INTENT;
	private boolean				mIsConnected;

	private MyBinder binder = new MyBinder();

	public ApiService() {
		mConnectionHandler	= new Handler();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG,"Start service");
		return mStartMode;
	}


	public void connect(String login, String password, String server ) {
		mLogin		= login;
		mPassword	= password;
		mServer		= server;
		mConnectionThread	= new Thread(new Runnable() {
			@Override
			public void run() {
				doConnect();
			}
		});
		mConnectionThread.start();
	}

	public void disconnect() {
		mConnection.disconnect();
	}

	private void doConnect(){
		try {
			// Create the configuration for this new connection
			XMPPTCPConnectionConfiguration.Builder configBuilder = XMPPTCPConnectionConfiguration.builder();
			configBuilder.setUsernameAndPassword(mLogin, mPassword);
			configBuilder.setResource("mobile");
			configBuilder.setServiceName(mServer);
			configBuilder.setHost(mServer);

			mConnection = new XMPPTCPConnection(configBuilder.build());
			mConnection.addConnectionListener(this);
			// Connect to the server
			mConnection.connect();
			// Log into the server
			mConnection.login();

			// setup chat manager
			mChatManager	= ChatManager.getInstanceFor(mConnection);
			if(mChatManager != null){
				mChatManager.addChatListener(this);
			}

			// setup roster
			mRoster			= Roster.getInstanceFor(mConnection);

			// get groups
			Collection<RosterGroup> groups = mRoster.getGroups();
			for (RosterGroup group : groups) {
				// add group to DB if not exists
				GroupContainer groupContainer = new GroupContainer(group.getName());
				Uri uri	= AppController.getAppContext().getContentResolver()
						.insert(AppContentProvider.CONTENT_URI_GROUPS,groupContainer.buildContentValues());
				long groupId = Long.valueOf(uri.getLastPathSegment());

				for (RosterEntry user : group.getEntries()) {

					User userContainer = new User(user.getUser(),user.getName(), groupId);
					AppController.getAppContext().getContentResolver()
							.insert(AppContentProvider.CONTENT_URI_USERS
									,userContainer.buildContentValues() );
					Log.d(TAG, "	roster user " + user.getUser());
				}
				Log.d(TAG, "roster group "+group.getName());
			}

			// Create a new presence. Pass in false to indicate we're unavailable._
			Presence presence = new Presence(Presence.Type.available);
			presence.setStatus("Working");
			// Send the packet (assume we have an XMPPConnection instance called "con").
			mConnection.sendStanza(presence);

			sendOnConnectedMessage(CONNECTION_STATUS_OK);
			Log.d(TAG,"on connected");
		}
		catch (SmackException.ConnectionException e){
			sendOnConnectedMessage(CONNECTION_STATUS_ERROR_CONNECTION);
		}
		catch (Exception e){
			e.printStackTrace();
			sendOnConnectedMessage(CONNECTION_STATUS_BAD_PASSWORD);
		}
	}

	/**
	 * event when incoming chat
	 * @param chat
	 * @param createdLocally
	 */
	@Override
	public void chatCreated(Chat chat, boolean createdLocally) {
		//TODO: add to DB
		ChatContainer chatContainer = new ChatContainer(chat.getParticipant(),null);
		AppController.getAppContext().getContentResolver()
				.insert(AppContentProvider.CONTENT_URI_CHATS,chatContainer.buildContentValues());
		Log.d(TAG, "on chat created");
		chat.addMessageListener(this);
	}

	/**
	 * event when incoming message
	 * @param message
	 */
	@Override
	public void processMessage(Message message) {
		//TODO: send to activity
		Log.d(TAG, "on received message");
	}


	/**
	 * event when icoming message from chat
	 * @param chat
	 * @param message
	 */
	@Override
	public void processMessage(Chat chat, Message message) {
		Log.d(TAG, "on message from chat");
		//1) get chat id
		ContentResolver contentResolver = AppController.getAppContext().getContentResolver();
		ChatContainer chatContainer = new ChatContainer(chat.getParticipant(),null);
		Uri chatUri = contentResolver
				.insert(AppContentProvider.CONTENT_URI_CHATS, chatContainer.buildContentValues());
		long chatId = Long.valueOf(chatUri.getLastPathSegment());
		//2) insert message by chat id

		User user 	= new User(chat.getParticipant(), null, 0);
		Uri userUri = contentResolver
				.insert(AppContentProvider.CONTENT_URI_USERS, user.buildContentValues());
		long userId = Long.valueOf(userUri.getLastPathSegment());

		// store relation
		Relation relation = new Relation(userId,chatId);
		contentResolver.insert(AppContentProvider.CONTENT_URI_RELATIONS, relation.buildContentValues());

		// store message to DB
		MessageContainer messageContainer = new MessageContainer(userId, chatId, new Date().getTime()
				,false,true,message.getBody(), message.getSubject());
		contentResolver.insert(AppContentProvider.CONTENT_URI_MESSAGES
				,messageContainer.buildContentValues());
		try {
			chat.sendMessage( message.getBody() );
		} catch (Exception e){

		}
	}

	//----------- Connection listener ---------------
	//TODO: send local broadcast
	@Override
	public void connected(XMPPConnection connection) {
	}

	@Override
	public void authenticated(XMPPConnection connection, boolean resumed) {
		mIsConnected = true;
	}

	@Override
	public void connectionClosed() {
		mIsConnected = false;
	}

	@Override
	public void connectionClosedOnError(Exception e) {
		mIsConnected = false;
	}

	@Override
	public void reconnectionSuccessful() {
		mIsConnected = true;
	}

	@Override
	public void reconnectingIn(int seconds) {
		mIsConnected = false;
	}

	@Override
	public void reconnectionFailed(Exception e) {
		mIsConnected = false;
	}

	//------ Roster events ------------

	@Override
	public void entriesAdded(Collection<String> addresses) {
		Log.d(TAG,"on entries added");
	}

	@Override
	public void entriesUpdated(Collection<String> addresses) {
		Log.d(TAG," on entries updated");
	}

	@Override
	public void entriesDeleted(Collection<String> addresses) {
		Log.d(TAG,"entries deleted");
	}

	@Override
	public void presenceChanged(Presence presence) {
		Log.d(TAG,"on presence change");

	}

	//------------------- end roster events --------------------

	public boolean isConnected() {
		return mIsConnected;
	}

	private void sendOnConnectionStatusChanged(int connectionStatus){
		Intent intent = new Intent(ACTION_ON_CONNECTION_STATUS_CHANGED);
		// You can also include some extra data.
		intent.putExtra(EXTRA_CONNECTION_STATUS, connectionStatus);
		LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

	}

	private void sendOnConnectedMessage(int msg){
		Intent intent = new Intent(ACTION_ON_CONNECTED_RESULT);
		// You can also include some extra data.
		intent.putExtra(EXTRA_CONNECTION_STATUS, msg);
		LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;

	}

	// service container for Activity
	public class MyBinder extends Binder{
		public ApiService getService() {
			return ApiService.this;
		}
	}
}
