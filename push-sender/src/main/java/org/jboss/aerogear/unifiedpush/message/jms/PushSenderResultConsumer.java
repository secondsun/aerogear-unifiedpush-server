package org.jboss.aerogear.unifiedpush.message.jms;

import com.google.android.gcm.server.Constants;
import com.google.android.gcm.server.MulticastResult;
import com.google.android.gcm.server.Result;
import org.jboss.aerogear.unifiedpush.api.Installation;
import org.jboss.aerogear.unifiedpush.message.holder.PushSenderResultHolder;
import org.jboss.aerogear.unifiedpush.message.sender.FCMPushNotificationSender;
import org.jboss.aerogear.unifiedpush.service.ClientInstallationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.Stateless;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This class consumes {@link PushSenderResult} messages and preforms the appropriate follow up actions.
 */
@Stateless
public class PushSenderResultConsumer {

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


    public void handleResult(@Observes @PushSenderResult PushSenderResultHolder resultHolder) {
        cleanupInvalidRegistrationIDsForVariant(resultHolder.getVariantId(), resultHolder.getResult(), resultHolder.getTargets());
    }

    /**
     * <p>Walks over the {@code MulticastResult} from the FCM call and identifies the <code>index</code> of all {@code Result} objects that
     * indicate an <code>InvalidRegistration</code> error.
     *
     * <p>This <code>index</code> is used to find the matching <code>registration ID</code> in the List of all used <code>registrationIDs</code>.
     *
     * <p>Afterwards all 'invalid' registration IDs for the given <code>variantID</code> are being deleted from our database.
     *
     * @param variantID id of the actual {@code AndroidVariantEntity}.
     * @param multicastResult the results from the HTTP request to the Google Cloud.
     * @param registrationIDs list of all tokens that we submitted to FCM.
     */
    private void cleanupInvalidRegistrationIDsForVariant(String variantID, MulticastResult multicastResult, List<String> registrationIDs) {

        // get the FCM send results for all of the client devices:
        final List<Result> results = multicastResult.getResults();

        // storage for all the invalid registration IDs:
        final Set<String> inactiveTokens = new HashSet<>();

        // read the results:
        for (int i = 0; i < results.size(); i++) {
            // use the current index to access the individual results
            final Result result = results.get(i);

            final String errorCodeName = result.getErrorCodeName();
            if (errorCodeName != null) {
                logger.info(String.format("Processing [%s] error code from FCM response, for registration ID: [%s]", errorCodeName, registrationIDs.get(i)));
            }

            //after sending, lets find tokens that are inactive from now on and need to be replaced with the new given canonical id.
            //according to fcm documentation, google refreshes tokens after some time. So the previous tokens will become invalid.
            //When you send a notification to a registration id which is expired, for the 1st time the message(notification) will be delivered
            //but you will get a new registration id with the name canonical id. Which mean, the registration id you sent the message to has
            //been changed to this canonical id, so change it on your server side as well.

            //check if current index of result has canonical id
            String canonicalRegId = result.getCanonicalRegistrationId();
            if (canonicalRegId != null) {
                // same device has more than one registration id: update it, if needed!
                // let's see if the canonical id is already in our system:
                Installation installation = clientInstallationService.findInstallationForVariantByDeviceToken(variantID, canonicalRegId);

                if (installation != null) {
                    // ok, there is already a device, with newest/latest registration ID (aka canonical id)
                    // It is time to remove the old reg id, to avoid duplicated messages in the future!
                    inactiveTokens.add(registrationIDs.get(i));

                } else {
                    // since there is no registered device with newest/latest registration ID (aka canonical id),
                    // this means the new token/regId was never stored on the server. Let's update the device and change its token to new canonical id:
                    installation = clientInstallationService.findInstallationForVariantByDeviceToken(variantID,registrationIDs.get(i));
                    installation.setDeviceToken(canonicalRegId);

                    //update installation with the new token
                    logger.info(String.format("Based on returned canonical id from FCM, updating Android installations with registration id [%s] with new token [%s] ", registrationIDs.get(i), canonicalRegId));
                    clientInstallationService.updateInstallation(installation);
                }

            } else {
                // is there any 'interesting' error code, which requires a clean up of the registration IDs
                if (FCM_ERROR_CODES.contains(errorCodeName)) {

                    // Ok the result at INDEX 'i' represents a 'bad' registrationID

                    // Now use the INDEX of the _that_ result object, and look
                    // for the matching registrationID inside of the List that contains
                    // _all_ the used registration IDs and store it:
                    inactiveTokens.add(registrationIDs.get(i));
                }
            }
        }

        if (! inactiveTokens.isEmpty()) {
            // trigger asynchronous deletion:
            logger.info(String.format("Based on FCM response data and error codes, deleting %d invalid or duplicated Android installations", inactiveTokens.size()));
            clientInstallationService.removeInstallationsForVariantByDeviceTokens(variantID, inactiveTokens);
        }
    }

}
