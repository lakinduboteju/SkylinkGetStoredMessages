package sg.com.temasys.lakinduboteju.skylinkgetstoredmessages;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import sg.com.temasys.skylink.sdk.listener.LifeCycleListener;
import sg.com.temasys.skylink.sdk.listener.MessagesListener;
import sg.com.temasys.skylink.sdk.listener.RemotePeerListener;
import sg.com.temasys.skylink.sdk.rtc.SkylinkCallback;
import sg.com.temasys.skylink.sdk.rtc.SkylinkConfig;
import sg.com.temasys.skylink.sdk.rtc.SkylinkConnection;
import sg.com.temasys.skylink.sdk.rtc.SkylinkError;
import sg.com.temasys.skylink.sdk.rtc.SkylinkEvent;
import sg.com.temasys.skylink.sdk.rtc.SkylinkInfo;
import sg.com.temasys.skylink.sdk.rtc.UserInfo;

public class MainActivity extends AppCompatActivity implements LifeCycleListener, RemotePeerListener, MessagesListener {
    private static final String TAG = "slgetstoredmsgslog";

    private SkylinkConnection mSkylinkConnection;
    private WorkerThreadPool mWorkerThreadPool; // Thread pool to run background tasks
    private Handler mMainThreadHandler; // Handler used to post tasks to the main/UI thread from worker threads
    private TextView mMsgsTv; // TextView to show retrieved persistent messages
    private Button mSendHiBtn; // Button to send persistent Hi message to room

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mMsgsTv = findViewById(R.id.msgs_tv);
        mSendHiBtn = findViewById(R.id.send_hi_btn);

        // Create an Skylink connection instance
        mSkylinkConnection = SkylinkConnection.getInstance();

        // Prepare Skylink Config for messaging
        SkylinkConfig skylinkConfig = new SkylinkConfig();
        skylinkConfig.setAudioVideoSendConfig(SkylinkConfig.AudioVideoConfig.NO_AUDIO_NO_VIDEO);
        skylinkConfig.setAudioVideoReceiveConfig(SkylinkConfig.AudioVideoConfig.NO_AUDIO_NO_VIDEO);
        skylinkConfig.setSkylinkRoomSize(SkylinkConfig.SkylinkRoomSize.EXTRA_SMALL);
        skylinkConfig.setMaxRemotePeersConnected(3, SkylinkConfig.AudioVideoConfig.NO_AUDIO_NO_VIDEO);
        skylinkConfig.setP2PMessaging(true);
        skylinkConfig.setTimeout(SkylinkConfig.SkylinkAction.GET_MESSAGE_STORED, 5000);

        // Init Skylink connection
        mSkylinkConnection.init(skylinkConfig, getApplicationContext(), new SkylinkCallback() {
            @Override
            public void onError(SkylinkError skylinkError, HashMap<String, Object> hashMap) {
                Log.e(TAG, "Failed to init Skylink connection. " +
                        skylinkError.getDescription() + " " + hashMap.get(SkylinkEvent.CONTEXT_DESCRIPTION));
            }
        });

        // Set Skylink connection listeners
        mSkylinkConnection.setLifeCycleListener(this);
        mSkylinkConnection.setRemotePeerListener(this);
        mSkylinkConnection.setMessagesListener(this);

        mWorkerThreadPool = new WorkerThreadPool();

        mMainThreadHandler = new Handler(getMainLooper());
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSendHiBtn.setEnabled(false); // Disable send button until connected to room
        mMsgsTv.setText(R.string.waiting_for_stored_msgs);

        // Connect to Skylink room
        mSkylinkConnection.connectToRoom(getString(R.string.skylink_key_id),
                getString(R.string.skylink_key_secret),
                getString(R.string.skylink_room_name),
                Settings.System.getString(getContentResolver(), "device_name"), // Peer username
                new SkylinkCallback() {
                    @Override
                    public void onError(SkylinkError skylinkError, HashMap<String, Object> hashMap) {
                        Log.e(TAG, "Failed to connect to Skylink room. " +
                                skylinkError.getDescription() + " " + hashMap.get(SkylinkEvent.CONTEXT_DESCRIPTION));
                    }
                });

        // Run background task to wait for Skylink room connection and get stored messages
        mWorkerThreadPool.runTask(() -> {
            // Wait until connected to Skylink room
            while (mSkylinkConnection.getSkylinkState() != SkylinkConnection.SkylinkState.CONNECTED) {
                try { Thread.sleep(500); } catch (InterruptedException e) {}
            }
            final String skylinkState = mSkylinkConnection.getSkylinkState().toString();
            runOnUiThread(() -> Toast.makeText(getApplicationContext(), skylinkState, Toast.LENGTH_SHORT).show()); // Displays CONNECTED toast message

            // After connected to Skylink room, get stored messages via Skylink connection (runs on main thread)
            mMainThreadHandler.post(() -> {
                // First, set message encrypt secrets to Skylink connection
                Map<String, String> msgEncryptSecrets = new HashMap<>(1);
                msgEncryptSecrets.put("key1", getString(R.string.skylink_msg_encrypt_secret));
                mSkylinkConnection.setEncryptSecretsMap(msgEncryptSecrets);

                // Then, request to get stored messages
                mSkylinkConnection.getStoredMessages(new SkylinkCallback.StoredMessages() {
                    @Override
                    public void onObtainStoredMessages(JSONArray jsonArray, Map<SkylinkError, JSONArray> errors) {
                        if (jsonArray == null && errors == null) {
                            mMsgsTv.setText(R.string.no_stored_msgs);
                            return;
                        }

                        // Has stored message errors?
                        if (errors != null) {
                            StringBuilder str = new StringBuilder();
                            for (Map.Entry<SkylinkError, JSONArray> e : errors.entrySet()) {
                                str.append(e.getKey().getDescription()).append(" : ").append(e.getValue().toString()).append("\n");
                            }
                            mMsgsTv.setText(str.toString());
                            return;
                        }

                        // Has stored message?
                        if (jsonArray != null) {
                            StringBuilder str = new StringBuilder();

                            // Process retrieved persistent messages
                            for (int i = 0; i < jsonArray.length(); i++) {
                                JSONObject msg = null;
                                String senderId = "Unknown", msgContent = "Unknown", timestamp = "Unknown";

                                Object msgObj = null;
                                try { msgObj = jsonArray.get(i); } catch (JSONException e) { Log.e(TAG, e.getMessage()); }

                                if (msgObj instanceof JSONArray) {
                                    try { msg = ((JSONArray) msgObj).getJSONObject(0); } catch (JSONException e) { Log.e(TAG, e.getMessage()); }
                                } else if (msgObj instanceof JSONObject) {
                                    msg = (JSONObject) msgObj;
                                } else {
                                    continue;
                                }

                                try { senderId = msg.getString("senderId"); } catch (JSONException e) { Log.e(TAG, e.getMessage()); }
                                try { msgContent = msg.getString("data"); } catch (JSONException e) { Log.e(TAG, e.getMessage()); }
                                try { timestamp = msg.getString("timeStamp"); } catch (JSONException e) { Log.e(TAG, e.getMessage()); }

                                str.append('[').append(senderId).append(']').append(" : ")
                                        .append('(').append(new Date(Long.parseLong(timestamp))).append(')').append(" : ")
                                        .append('"').append(msgContent).append('"').append("\n");
                            }

                            mMsgsTv.setText(str.toString()); // Show persistent messages in TextView
                        }
                    }
                });

                // Enable message send button after connected to room
                mSendHiBtn.setEnabled(true);
            });
        });
    }

    @Override
    protected void onPause() {
        // Disconnect from Skylink room
        mSkylinkConnection.disconnectFromRoom(new SkylinkCallback() {
            @Override
            public void onError(SkylinkError skylinkError, HashMap<String, Object> hashMap) {
                Log.e(TAG, "Failed to disconnect from Skylink room. " +
                        skylinkError.getDescription() + " " + hashMap.get(SkylinkEvent.CONTEXT_DESCRIPTION));
            }
        });

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mWorkerThreadPool.shutdown(); // Stop all background tasks immediately

        // Clear Skylink connection
        mSkylinkConnection.clearInstance();
        mSkylinkConnection = null;

        super.onDestroy();
    }

    // On-click listener of message send button
    public void onSendPersistentHiBtnPressed(View v) {
        mSkylinkConnection.setSelectedSecretId("key1");
        mSkylinkConnection.setMessagePersist(true);
        // Send a persistent 'Hi' to everyone in Skylink room
        mSkylinkConnection.sendServerMessage("Hi", null, new SkylinkCallback() {
            @Override
            public void onError(SkylinkError skylinkError, HashMap<String, Object> hashMap) {
                Log.e(TAG, "Failed to send persistent message. " +
                        skylinkError.getDescription() + " " + hashMap.get(SkylinkEvent.CONTEXT_DESCRIPTION));
                Toast.makeText(getApplicationContext(), "Send failed!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // LifeCycleListener callbacks

    @Override
    public void onConnectToRoomSucessful() {
        Log.d(TAG, "onConnectToRoomSucessful");
    }

    @Override
    public void onConnectToRoomFailed(String s) {
        Log.e(TAG, "onConnectToRoomFailed " + s);
    }

    @Override
    public void onDisconnectFromRoom(SkylinkEvent skylinkEvent, String s) {
        Log.d(TAG, "onDisconnectFromRoom " + s + " " + skylinkEvent.getDescription());
    }

    @Override
    public void onChangeRoomLockStatus(boolean b, String s) {
        Log.d(TAG, "onChangeRoomLockStatus " + s + " " + Boolean.toString(b));
    }

    @Override
    public void onReceiveInfo(SkylinkInfo skylinkInfo, HashMap<String, Object> hashMap) {
        Log.d(TAG, "onReceiveInfo " + skylinkInfo.getDescription() + " " + hashMap.get(SkylinkEvent.CONTEXT_DESCRIPTION));
    }

    @Override
    public void onReceiveWarning(SkylinkError skylinkError, HashMap<String, Object> hashMap) {
        Log.d(TAG, "onReceiveWarning " + skylinkError.getDescription() + " " + hashMap.get(SkylinkEvent.CONTEXT_DESCRIPTION));
    }

    @Override
    public void onReceiveError(SkylinkError skylinkError, HashMap<String, Object> hashMap) {
        Log.d(TAG, "onReceiveError " + skylinkError.getDescription() + " " + hashMap.get(SkylinkEvent.CONTEXT_DESCRIPTION));
    }

    // RemotePeerListener callbacks

    @Override
    public void onReceiveRemotePeerJoinRoom(String s, UserInfo userInfo) {
        Log.d(TAG, "onReceiveRemotePeerJoinRoom " + s + " " + userInfo.getUserData().toString());
    }

    @Override
    public void onConnectWithRemotePeer(String s, UserInfo userInfo, boolean b) {
    }

    @Override
    public void onRefreshRemotePeerConnection(String s, UserInfo userInfo, boolean b, boolean b1) {
    }

    @Override
    public void onReceiveRemotePeerUserData(Object o, String s) {
    }

    @Override
    public void onOpenRemotePeerDataConnection(String s) {
    }

    @Override
    public void onDisconnectWithRemotePeer(String s, UserInfo userInfo, boolean b) {
    }

    @Override
    public void onReceiveRemotePeerLeaveRoom(String s, SkylinkInfo skylinkInfo, UserInfo userInfo) {
    }

    @Override
    public void onErrorForRemotePeerConnection(SkylinkError skylinkError, HashMap<String, Object> hashMap) {
    }

    // MessagesListener callbacks

    @Override
    public void onReceiveServerMessage(Object o, boolean b, Long aLong, String s) {
        Log.d(TAG, "onReceiveServerMessage " + o.toString() + " " + s);

    }

    @Override
    public void onReceiveP2PMessage(Object o, boolean b, Long aLong, String s) {
    }
}