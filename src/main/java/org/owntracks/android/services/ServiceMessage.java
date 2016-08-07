package org.owntracks.android.services;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Log;

import org.owntracks.android.App;
import org.owntracks.android.messages.MessageBase;
import org.owntracks.android.messages.MessageCard;
import org.owntracks.android.messages.MessageCmd;
import org.owntracks.android.messages.MessageConfiguration;
import org.owntracks.android.messages.MessageLocation;
import org.owntracks.android.messages.MessageTransition;
import org.owntracks.android.messages.MessageUnknown;
import org.owntracks.android.model.FusedContact;
import org.owntracks.android.support.Events;
import org.owntracks.android.support.IncomingMessageProcessor;
import org.owntracks.android.support.Preferences;
import org.owntracks.android.support.StatisticsProvider;
import org.owntracks.android.support.Toasts;
import org.owntracks.android.support.interfaces.ProxyableService;
import org.owntracks.android.support.interfaces.ServiceMessageEndpoint;
import org.owntracks.android.support.interfaces.StatefulServiceMessageEndpoint;

import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import de.greenrobot.event.EventBus;
import timber.log.Timber;


public class ServiceMessage implements ProxyableService, IncomingMessageProcessor {
    private static final String TAG = "ServiceMessage";

    private static ServiceMessageEndpoint endpoint;
    private ThreadPoolExecutor incomingMessageProcessorExecutor;
    private static EndpointState endpointState = EndpointState.INITIAL;
    private Exception endpointError;
    private ServiceProxy context;

    public void reconnect() {
        if(endpoint instanceof StatefulServiceMessageEndpoint)
            StatefulServiceMessageEndpoint.class.cast(endpoint).reconnect();
    }

    public void disconnect() {
        if(endpoint instanceof StatefulServiceMessageEndpoint)
            StatefulServiceMessageEndpoint.class.cast(endpoint).disconnect();
    }

    public ServiceProxy getContext() {
        return context;
    }

    public enum EndpointState {
        INITIAL,
        IDLE,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        DISCONNECTED,
        DISCONNECTED_USERDISCONNECT,
        ERROR,
        ERROR_DATADISABLED,
        ERROR_CONFIGURATION;

        public String getLabel(Context context) {
            Resources res = context.getResources();
            int resId = res.getIdentifier(this.name(), "string", context.getPackageName());
            if (0 != resId) {
                return (res.getString(resId));
            }
            return (name());
        }
    }



    @Override
    public void onCreate(ServiceProxy c) {
        this.context = c;
        this.incomingMessageProcessorExecutor = new ThreadPoolExecutor(2,2,1,  TimeUnit.MINUTES,new LinkedBlockingQueue<Runnable>());
        onModeChanged(Preferences.getModeId());

    }



    private void onModeChanged(int mode) {
        Timber.v("mode:%s", mode);
        if(endpoint != null)
            endpoint.onDestroy();


        endpoint = instantiateEndpoint(mode);

        if(endpoint == null) {
            Timber.e("unable to instantiate service for mode:%s", mode);
        }
    }

    private ServiceMessageEndpoint instantiateEndpoint(int id) {
        ServiceMessageEndpoint p;
        switch (id) {
            case App.MODE_ID_HTTP_PRIVATE:
                p = new ServiceMessageHttp();
                break;
            case App.MODE_ID_MQTT_PRIVATE:
            case App.MODE_ID_MQTT_PUBLIC:
                p = new ServiceMessageMqtt();
                break;
            default:
                return null;
        }

        p.onCreate(context);
        p.onSetService(this);
        EventBus.getDefault().registerSticky(p);
        return p;
    }

    @Override
    public void onDestroy() {
    }

    @Override
    public void onStartCommand(Intent intent, int flags, int startId) {
        if(endpoint != null)
            endpoint.onStartCommand(intent, flags, startId);
    }

    @SuppressWarnings("unused")
    @Override
    public void onEvent(Events.Dummy event) {

    }

    @SuppressWarnings("unused")
    public void onEvent(Events.ModeChanged event) {
        onModeChanged(Preferences.getModeId());
    }

    private HashMap<Long, MessageBase> outgoingQueue = new HashMap<>();

    public void sendMessage(MessageBase message) {
        Log.v(TAG, "sendMessage() - endpoint:" + endpoint);

        message.setOutgoing();

        if(endpoint == null || !endpoint.isReady()) {
            Timber.e("no endpoint or endpoint does not yet accept messages");
            return;
        }

        if(endpoint.sendMessage(message)) {
            this.onMessageQueued(message);
        }
    }



    public void onMessageDelivered(Long messageId) {

        MessageBase m = outgoingQueue.remove(messageId);
        StatisticsProvider.setInt(StatisticsProvider.SERVICE_MESSAGE_QUEUE_LENGTH, outgoingQueue.size());
        if(m == null) {
            Log.e(TAG, "onMessageDelivered()- messageId:"+messageId + ", error: called for unqueued message");
        } else {
            Log.v(TAG, "onMessageDelivered()-  messageId:" + m.getMessageId()+", queueLength:"+outgoingQueue.size());
            if(m instanceof MessageLocation) {
                de.greenrobot.event.EventBus.getDefault().post(m);
            }
        }
        Log.v(TAG, "onMessageDelivered()-  queueKeys:" +  outgoingQueue.keySet().toString());
    }

    private void onMessageQueued(MessageBase m) {
        outgoingQueue.put(m.getMessageId(), m);
        StatisticsProvider.setInt(StatisticsProvider.SERVICE_MESSAGE_QUEUE_LENGTH, outgoingQueue.size());

        Log.v(TAG, "onMessageQueued()- messageId:" + m.getMessageId()+", queueLength:"+outgoingQueue.size());
        if(m instanceof MessageLocation && MessageLocation.REPORT_TYPE_USER.equals(MessageLocation.class.cast(m).getT()))
            Toasts.showMessageQueued();
    }

    public void onMessageDeliveryFailed(Long messageId) {

        MessageBase m = outgoingQueue.remove(messageId);
        StatisticsProvider.setInt(StatisticsProvider.SERVICE_MESSAGE_QUEUE_LENGTH, outgoingQueue.size());

        if(m == null) {
            Timber.e("type:base, messageId:%s, error: called for unqueued message", messageId);
        } else {
            Timber.e("type:base, messageId:%s, queueLength:%s", messageId, outgoingQueue.size());
            if(m.getOutgoingTTL() > 0)  {
                Timber.e("type:base, messageId:%s, action: requeued",m.getMessageId() );
                sendMessage(m);
            } else {
                Timber.e("type:base, messageId:%s, action: discarded due to expired ttl",m.getMessageId() );
            }
        }
    }

    public void onMessageReceived(MessageBase message) {
        message.setIncomingProcessor(this);
        message.setIncoming();
        incomingMessageProcessorExecutor.execute(message);
    }

    public void onEndpointStateChanged(EndpointState newState, Exception e) {
        endpointState = newState;
        endpointError = e; 
        EventBus.getDefault().postSticky(new Events.EndpointStateChanged(newState, e));
    }


    @Override
    public void processIncomingMessage(MessageBase message) {
        Timber.v("type:base, key:%s", message.getContactKey());
    }

    public void processIncomingMessage(MessageUnknown message) {
        Timber.v("type:unknown, key:%s", message.getContactKey());
    }


    @Override
    public void processIncomingMessage(MessageLocation message) {
        Timber.v("type:location, key:%s", message.getContactKey());

        //GeocodingProvider.resolve(message);
        FusedContact c = App.getFusedContact(message.getContactKey());

        if (c == null) {
            c = new FusedContact(message.getContactKey());
            c.setMessageLocation(message);
            App.addFusedContact(c);
        } else {

            //Only update contact with new location if the location message is different from the one that is already set
            if(c.setMessageLocation(message))
                App.updateFusedContact(c);
        }
    }

    @Override
    public void processIncomingMessage(MessageCard message) {
        FusedContact c = App.getFusedContact(message.getContactKey());

        if (c == null) {
            c = new FusedContact(message.getContactKey());
            c.setMessageCard(message);
            App.addFusedContact(c);
        } else {
            c.setMessageCard(message);
            App.updateFusedContact(c);
        }
    }

    @Override
    public void processIncomingMessage(MessageCmd message) {
        if(!Preferences.getRemoteCommand()) {
            Timber.e("remote commands are disabled");
            return;
        }

        switch (message.getAction()) {
            case MessageCmd.ACTION_REPORT_LOCATION:
                ServiceProxy.getServiceLocator().reportLocationResponse();
                break;
            case MessageCmd.ACTION_WAYPOINTS:
                ServiceProxy.getServiceApplication().publishWaypointsMessage();
                break;
            case MessageCmd.ACTION_SET_WAYPOINTS:
                Preferences.importWaypointsFromJson(message.getWaypoints());
                break;
        }
    }

    @Override
    public void processIncomingMessage(MessageTransition message) {
        ServiceProxy.getServiceNotification().processMessage(message);
    }

    public void processIncomingMessage(MessageConfiguration message) {
        if(!Preferences.getRemoteConfiguration())
            return;

        Preferences.importFromMessage(message);
    }


}
