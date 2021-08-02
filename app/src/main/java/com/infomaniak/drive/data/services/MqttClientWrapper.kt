/*
 * Infomaniak kDrive - Android
 * Copyright (C) 2021 Infomaniak Network SA
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.infomaniak.drive.data.services

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import com.google.gson.JsonParser
import com.infomaniak.drive.data.models.ActionNotification
import com.infomaniak.drive.data.models.ActionProgressNotification
import com.infomaniak.drive.data.models.IpsToken
import com.infomaniak.drive.utils.AccountUtils
import com.infomaniak.lib.core.utils.ApiController.gson
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*

object MqttClientWrapper : MqttCallback, LiveData<Any>() {

    private lateinit var appContext: Context
    private lateinit var clientId: String
    private var currentToken: IpsToken? = null
    private var isSubscribed: Boolean = false

    private const val MQTT_USER = "ips:ips-public"
    private const val MQTT_PASS = "8QC5EwBqpZ2Z"
    private const val MQTT_URI = "wss://info-mq.infomaniak.com/ws"

    private val client: MqttAndroidClient by lazy {
        MqttAndroidClient(appContext, MQTT_URI, clientId)
    }

    private fun topicFor(token: IpsToken) = "drive/${token.uuid}"

    private fun unsubscribe(topic: String) {
        client.unsubscribe(topic, null, null)
        isSubscribed = false
    }

    fun init(context: Context, clientId: String = "", onClientConnected: (success: Boolean) -> Unit) {
        this.appContext = context
        this.clientId = clientId

        client.setCallback(this)
        val options = MqttConnectOptions()
        options.userName = MQTT_USER
        options.password = MQTT_PASS.toCharArray()
        options.keepAliveInterval = 30
        options.isAutomaticReconnect = true

        try {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    onClientConnected(true)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    exception?.printStackTrace()
                    onClientConnected(false)
                }

            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    fun registerForNotifications(token: IpsToken) {
        currentToken?.let { unsubscribe(topicFor(token)) }
        currentToken = token
        if (!isSubscribed) {
            subscribe(topicFor(token))
        }
    }

    fun subscribe(topic: String, qos: Int = 1) {
        client.subscribe(topic, qos, null, null)
        isSubscribed = true
    }

    fun disconnect(listener: IMqttActionListener) {
        client.disconnect(null, listener)
    }

    override fun connectionLost(cause: Throwable?) {
        Log.e("MQTT Error", "Connection has been lost. Stacktrace below.")
        cause?.printStackTrace()
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        val isProgress = JsonParser.parseString(message.toString()).asJsonObject.has("progress")
        val notification = gson.fromJson(
            message.toString(),
            if (isProgress) ActionProgressNotification::class.java else ActionNotification::class.java
        )

        if (notification.driveId == AccountUtils.currentDriveId) {
            postValue(notification)
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
}