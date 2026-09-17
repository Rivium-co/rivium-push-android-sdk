package co.rivium.push.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives Rivium Push messages over FCM. Declared in the add-on manifest.
 * If your app has its own FirebaseMessagingService, remove this one and
 * forward to [RiviumPushFcm] instead.
 */
class RiviumFcmMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        RiviumPushFcm.handleMessage(applicationContext, message)
    }

    override fun onNewToken(token: String) {
        RiviumPushFcm.onNewToken(applicationContext, token)
    }
}
