/**
 * JBoss, Home of Professional Open Source
 * Copyright Red Hat, Inc., and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.aerogear.unifiedpush.message.sender;

import com.google.android.gcm.server.Constants;
import com.google.android.gcm.server.Message;
import com.google.android.gcm.server.Message.Builder;
import com.google.android.gcm.server.MulticastResult;
import com.google.android.gcm.server.Result;
import io.prometheus.client.Counter;
import org.jboss.aerogear.unifiedpush.api.AndroidVariant;
import org.jboss.aerogear.unifiedpush.api.Variant;
import org.jboss.aerogear.unifiedpush.api.VariantType;
import org.jboss.aerogear.unifiedpush.message.InternalUnifiedPushMessage;
import org.jboss.aerogear.unifiedpush.message.Priority;
import org.jboss.aerogear.unifiedpush.message.UnifiedPushMessage;
import org.jboss.aerogear.unifiedpush.message.holder.PushSenderResultHolder;
import org.jboss.aerogear.unifiedpush.message.jms.PushSenderResult;
import org.jboss.aerogear.unifiedpush.message.sender.fcm.ConfigurableFCMSender;
import org.jboss.aerogear.unifiedpush.service.ClientInstallationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.event.Event;
import javax.inject.Inject;
import java.io.IOException;
import java.util.*;

@SenderType(VariantType.ANDROID)
public class FCMPushNotificationSender implements PushNotificationSender {

    @Inject
    @PushSenderResult
    private Event<PushSenderResultHolder> pushSenderResultEvent;

    private static final Counter promPrushRequestsAndroid = Counter.build()
            .name("aerogear_ups_push_requests_android")
            .help("Total number of Android push batch requests.")
            .register();

    // collection of error codes we check for in the FCM response
    // in order to clean-up invalid or incorrect device tokens
    private static final Set<String> FCM_ERROR_CODES =
            new HashSet<>(Arrays.asList(
                    Constants.ERROR_INVALID_REGISTRATION,  // Bad registration_id.
                    Constants.ERROR_NOT_REGISTERED,        // The user has uninstalled the application or turned off notifications.
                    Constants.ERROR_MISMATCH_SENDER_ID)    // incorrect token, from a different project/sender ID
            );

    @Inject
    private ClientInstallationService clientInstallationService;

    private static final Logger logger = LoggerFactory.getLogger(FCMPushNotificationSender.class);

    /**
     * Sends FCM notifications ({@link UnifiedPushMessage}) to all devices, that are represented by
     * the {@link List} of tokens for the given {@link AndroidVariant}.
     */
    @Override
    public void sendPushMessage(Variant variant, Collection<String> tokens, UnifiedPushMessage pushMessage, String pushMessageInformationId, NotificationSenderCallback callback) {

        // no need to send empty list
        if (tokens.isEmpty()) {
            return;
        }

        final List<String> pushTargets = new ArrayList<>(tokens);
        final AndroidVariant androidVariant = (AndroidVariant) variant;

        // payload builder:
        Builder fcmBuilder = new Message.Builder();

        org.jboss.aerogear.unifiedpush.message.Message message = pushMessage.getMessage();
        // add the "recognized" keys...
        fcmBuilder.addData("alert", message.getAlert());
        fcmBuilder.addData("sound", message.getSound());
        fcmBuilder.addData("badge", String.valueOf(message.getBadge()));

        /*
        The Message defaults to a Normal priority.  High priority is used
        by FCM to wake up devices in Doze mode as well as apps in AppStandby
        mode.  This has no effect on devices older than Android 6.0
        */
        fcmBuilder.priority(
                message.getPriority() ==  Priority.HIGH ?
                                          Message.Priority.HIGH :
                                          Message.Priority.NORMAL
                           );

        // if present, apply the time-to-live metadata:
        int ttl = pushMessage.getConfig().getTimeToLive();
        if (ttl != -1) {
            fcmBuilder.timeToLive(ttl);
        }

        // iterate over the missing keys:
        message.getUserData().keySet()
                .forEach(key -> fcmBuilder.addData(key, String.valueOf(message.getUserData().get(key))));

        //add the aerogear-push-id
        fcmBuilder.addData(InternalUnifiedPushMessage.PUSH_MESSAGE_ID, pushMessageInformationId);

        Message fcmMessage = fcmBuilder.build();

        // send it out.....
        try {
            logger.debug("Sending transformed FCM payload: {}", fcmMessage);

            final ConfigurableFCMSender sender = new ConfigurableFCMSender(androidVariant.getGoogleKey());

            // we are about to send HTTP requests for all tokens of topics of this batch
            promPrushRequestsAndroid.inc();

            // send out a message to a batch of devices...
            processFCM(androidVariant, pushTargets, fcmMessage , sender);

            logger.debug("Message batch to FCM has been submitted");
            callback.onSuccess();

        } catch (Exception e) {
            // FCM exceptions:
            callback.onError(String.format("Error sending payload to FCM server: %s", e.getMessage()));
        }
    }

    /**
     * Process the HTTP POST to the FCM infrastructure for the given list of registrationIDs.
     */
    private void processFCM(AndroidVariant androidVariant, List<String> pushTargets, Message fcmMessage, ConfigurableFCMSender sender) throws IOException {


        // push targets can be registration IDs OR topics (starting /topic/), but they can't be mixed.
        if (pushTargets.get(0).startsWith(Constants.TOPIC_PREFIX)) {

            // perform the topic delivery

            for (String topic : pushTargets) {
                logger.info(String.format("Sent push notification to FCM topic: %s", topic));
                Result result = sender.sendNoRetry(fcmMessage, topic);

                logger.trace("Response from FCM topic request: {}", result);
            }
        } else {
            logger.info(String.format("Sent push notification to FCM Server for %d registrationIDs", pushTargets.size()));
            MulticastResult multicastResult = sender.sendNoRetry(fcmMessage, pushTargets);

            logger.trace("Response from FCM request: {}", multicastResult);

            // after sending, let's identify the inactive/invalid registrationIDs and trigger their deletion:
            PushSenderResultHolder resultHolder = new PushSenderResultHolder();
            resultHolder.setVariantId(androidVariant.getVariantID());
            resultHolder.setResult(multicastResult);
            resultHolder.setTargets(pushTargets);

            pushSenderResultEvent.fire(resultHolder);

            //cleanupInvalidRegistrationIDsForVariant(androidVariant.getVariantID(), multicastResult, pushTargets);
        }
    }


}
